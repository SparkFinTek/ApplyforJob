package com.sparkinvesco.jobflow.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Walks workflow.json node-by-node, dispatching connectors and following edges.
 * The Overview layer is the executable graph; sub-flows are documentation.
 *
 * Per-posting entry point: starts at the matcher node (id "o6") with the JD
 * already in context, walks until it reaches a terminal node or a node it
 * cannot continue past.
 *
 * Decision nodes are evaluated inline by node id (the workflow has named
 * decisions whose semantics are well-known). When a decision evaluates,
 * the executor follows the outgoing edge whose label matches the chosen branch.
 *
 * Nodes whose connector isn't implemented yet (chrome.*, claude.classify_email,
 * etc.) are recorded as "skipped: not implemented" and the executor follows
 * the first outgoing edge — letting the rest of the flow continue.
 */
@Service
public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);
    private static final String OVERVIEW_LAYER_ID = "overview";
    private static final String PER_POSTING_START_NODE = "o6"; // "Match against base resumes"
    private static final int MAX_STEPS = 100;

    private final GammaPaths paths;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConnectorDispatcher dispatcher;

    public WorkflowExecutor(GammaPaths paths, ConnectorDispatcher dispatcher) {
        this.paths = paths;
        this.dispatcher = dispatcher;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> executePerPosting(Map<String, Object> initialContext) throws IOException {
        Map<String, Object> wf = mapper.readValue(paths.workflow().toFile(), Map.class);
        List<Map<String, Object>> layers = (List<Map<String, Object>>) wf.get("layers");
        Map<String, Object> overview = layers.stream()
                .filter(l -> OVERVIEW_LAYER_ID.equals(l.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("workflow.json missing 'overview' layer"));

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) overview.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) overview.get("edges");
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        for (Map<String, Object> n : nodes) nodesById.put((String) n.get("id"), n);

        // Per-step audit log accumulates inside the context.
        Map<String, Object> ctx = new LinkedHashMap<>(initialContext);
        List<Map<String, Object>> steps = new ArrayList<>();
        ctx.put("steps", steps);

        Map<String, Object> current = nodesById.get(PER_POSTING_START_NODE);
        int safety = 0;
        while (current != null && safety++ < MAX_STEPS) {
            String nodeId = (String) current.get("id");
            String type = (String) current.get("type");
            String connector = String.valueOf(current.getOrDefault("connector", "noop"));
            String label = String.valueOf(current.getOrDefault("label", ""));
            boolean approval = Boolean.TRUE.equals(current.get("approval"));

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("nodeId", nodeId);
            step.put("connector", connector);
            step.put("label", label.replace("\n", " "));
            steps.add(step);

            // Approval gate — for the v1 we don't pause real work because there's
            // a UI button click already; we just record that this gate would have fired.
            if (approval) {
                step.put("approval", "auto-approved (UI click counts as approval)");
            }

            if ("decision".equals(type)) {
                String branch = evaluateDecision(nodeId, ctx);
                step.put("branch", branch);
                current = followEdge(edges, nodesById, nodeId, branch);
                continue;
            }

            // Execute the connector
            try {
                Map<String, Object> params = (Map<String, Object>) current.getOrDefault("params", Map.of());
                Map<String, Object> result = dispatcher.dispatch(connector, params, ctx);
                step.put("result", summarizeResult(result));
                // Connector results are merged into context under the node id, AND key fields lifted up.
                ctx.put(nodeId, result);
                liftKnownFields(ctx, result);

                // Skip-and-stop signals: if matcher returned skip, stop here.
                if (Boolean.TRUE.equals(result.get("skip"))) {
                    step.put("terminated", "skip");
                    ctx.put("terminated", "skip");
                    return ctx;
                }
            } catch (Exception e) {
                log.error("Connector {} failed on node {}: {}", connector, nodeId, e.getMessage(), e);
                step.put("error", e.getMessage());
                ctx.put("terminated", "error");
                ctx.put("lastError", connector + " on " + nodeId + ": " + e.getMessage());
                return ctx;
            }

            // Process / data nodes: follow first outgoing edge.
            current = followFirstEdge(edges, nodesById, nodeId);
        }
        return ctx;
    }

    /** Decisions are well-known by node id. Branch label must match an outgoing edge label. */
    @SuppressWarnings("unchecked")
    private String evaluateDecision(String nodeId, Map<String, Object> ctx) {
        switch (nodeId) {
            case "o3": // "Any new postings?"
                return "Yes"; // per-posting entry point — we already have one
            case "o7": { // "Score >= threshold?"
                Map<String, Object> match = (Map<String, Object>) ctx.getOrDefault("o6", Map.of());
                Object score = match.get("matchScore");
                Object threshold = ctx.getOrDefault("minMatchScore", 0.05);
                double s = score instanceof Number n ? n.doubleValue() : 0.0;
                double t = threshold instanceof Number n ? n.doubleValue() : 0.05;
                return s >= t ? "Yes" : "No";
            }
            case "o11": // "Easy Apply available?"
                // Chrome connectors aren't implemented yet — always route to "No" so we proceed
                // through the archive + tracker path without trying to submit.
                return "No";
            case "o13": // "Account creation required?"
                return "No";
            default:
                return "Yes"; // safe default: take the affirmative branch
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> followEdge(List<Map<String, Object>> edges,
                                           Map<String, Map<String, Object>> nodesById,
                                           String fromId, String label) {
        for (Map<String, Object> e : edges) {
            if (fromId.equals(e.get("from"))) {
                String edgeLabel = String.valueOf(e.getOrDefault("label", ""));
                if (label.equalsIgnoreCase(edgeLabel)) {
                    return nodesById.get((String) e.get("to"));
                }
            }
        }
        // No matching label — fall back to the first outgoing edge.
        return followFirstEdge(edges, nodesById, fromId);
    }

    private Map<String, Object> followFirstEdge(List<Map<String, Object>> edges,
                                                Map<String, Map<String, Object>> nodesById,
                                                String fromId) {
        for (Map<String, Object> e : edges) {
            if (fromId.equals(e.get("from"))) {
                return nodesById.get((String) e.get("to"));
            }
        }
        return null;
    }

    /** Promote select connector outputs to top-level context keys for easier reuse downstream. */
    private void liftKnownFields(Map<String, Object> ctx, Map<String, Object> result) {
        for (String k : List.of(
                // matcher outputs — needed by tailor + export
                "matchScore", "baseResume", "baseResumePath",
                // tailor outputs — needed by export
                "tailoredHtml", "tailored", "fallback",
                // export outputs — needed by archive
                "renderedPdfPath", "resumeFilename", "rendered",
                // archive outputs — needed by tracker
                "archivePath", "archiveFolder", "metaPath",
                // chrome handoff — flips tracker row status to "Ready for Submit"
                "awaitingSubmission", "submissionInstruction",
                // tracker output
                "trackerRowId")) {
            if (result.containsKey(k)) ctx.put(k, result.get(k));
        }
    }

    /** Trim large fields from the audit log so it doesn't bloat the response. */
    private Map<String, Object> summarizeResult(Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : result.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.length() > 240) {
                out.put(e.getKey(), s.substring(0, 240) + "…(" + s.length() + " chars)");
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
