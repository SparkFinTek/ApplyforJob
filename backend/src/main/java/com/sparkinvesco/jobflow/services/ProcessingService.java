package com.sparkinvesco.jobflow.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-posting orchestration.
 *
 * Loads the pending JD + config, applies the hard filters from Spark's
 * workflow rules, then delegates execution to WorkflowExecutor which walks
 * workflow.json and dispatches each connector in order.
 *
 * Filter rules (any failure marks the pending item as processed+skipped
 * with a reason; the file is never deleted):
 *   1. NOT reposted.
 *   2. Strictly fewer than 10 applicants when the count is known.
 *   3. Title contains a target phrase (Director / VP of Engineering).
 *
 * The actual application steps (match, tailor, render PDF, archive,
 * tracker append) come from workflow.json — this service does not encode them.
 */
@Service
public class ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);

    private final GammaPaths paths;
    private final WorkflowExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProcessingService(GammaPaths paths, WorkflowExecutor executor) {
        this.paths = paths;
        this.executor = executor;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> processPending(String id) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);

        Path pendingFile = paths.root().resolve("pending").resolve(id + ".json");
        if (!Files.exists(pendingFile)) {
            result.put("ok", false);
            result.put("reason", "pending file not found");
            return result;
        }
        Map<String, Object> pending = mapper.readValue(pendingFile.toFile(), Map.class);
        if (Boolean.TRUE.equals(pending.get("processed"))) {
            result.put("ok", false);
            result.put("reason", "already processed");
            return result;
        }

        String company = strOrEmpty(pending.get("company"));
        String title = strOrEmpty(pending.get("title"));
        String jd = strOrEmpty(pending.get("jd"));
        String postingUrl = strOrEmpty(pending.get("postingUrl"));
        String location = strOrEmpty(pending.get("location"));
        String workMode = strOrEmpty(pending.get("workMode"));
        Object applicantCount = pending.get("applicantCount");

        if (company.isBlank() || title.isBlank() || jd.isBlank()) {
            result.put("ok", false);
            result.put("reason", "company, title, or JD is missing on the pending item");
            return result;
        }

        // Hard filters
        if (Boolean.TRUE.equals(pending.get("reposted"))) {
            markSkipped(pendingFile, pending, "reposted_listing");
            result.put("ok", false);
            result.put("reason", "reposted listing — excluded by policy");
            return result;
        }
        if (applicantCount instanceof Number cnt && cnt.intValue() >= 10) {
            markSkipped(pendingFile, pending, "applicant_count_ge_10");
            result.put("ok", false);
            result.put("reason", "applicant count " + cnt.intValue() + " ≥ 10 — past the top-10 window");
            return result;
        }
        // Strict posting-age window — defense-in-depth on top of the LinkedIn URL filter.
        // If LinkedIn UI was overridden to "Past 24 hours" but our policy is 2 hours,
        // we reject any posting whose age exceeds the configured ceiling.
        Object postedMin = pending.get("postedMinutesAgo");
        if (postedMin instanceof Number pm) {
            int maxAge = readPostingMaxAgeMinutes();
            if (pm.intValue() > maxAge) {
                markSkipped(pendingFile, pending, "posting_too_old");
                result.put("ok", false);
                result.put("reason", "posted " + pm.intValue() + " min ago — outside the " + maxAge + "-min window");
                return result;
            }
        }
        if (!titleMatchesTargets(title)) {
            markSkipped(pendingFile, pending, "title_not_in_targets");
            result.put("ok", false);
            result.put("reason", "title does not match target phrases (Director/VP of Engineering)");
            return result;
        }

        // Build the initial execution context for WorkflowExecutor.
        Map<String, Object> config = readConfigSafe();
        Map<String, Object> ownerInfo = (Map<String, Object>) config.getOrDefault("owner", Map.of());

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("pendingId", id);
        ctx.put("company", company);
        ctx.put("title", title);
        ctx.put("jdText", jd);
        ctx.put("postingUrl", postingUrl);
        ctx.put("location", location);
        ctx.put("workMode", workMode);
        ctx.put("applicantCount", applicantCount);
        ctx.put("ownerInfo", ownerInfo);
        ctx.put("useBaseResume", Boolean.TRUE.equals(pending.get("useBaseResume")));
        Object minScore = ((Map<String, Object>) config.getOrDefault("targeting", Map.of()))
                .getOrDefault("minMatchScore", 0.05);
        ctx.put("minMatchScore", minScore);

        Map<String, Object> exec;
        try {
            exec = executor.executePerPosting(ctx);
        } catch (Exception e) {
            log.error("Workflow execution failed for pending {}: {}", id, e.getMessage(), e);
            result.put("ok", false);
            result.put("reason", "workflow_execution_failed: " + e.getMessage());
            return result;
        }

        // Mark the pending item as processed (even on workflow skip — the file is never deleted).
        boolean skipped = "skip".equals(exec.get("terminated"));
        boolean errored = "error".equals(exec.get("terminated"));
        pending.put("processed", true);
        pending.put("processedAt", Instant.now().toString());
        if (skipped) pending.put("skipReason", "workflow_skip");
        if (errored) {
            pending.put("skipReason", "workflow_error");
            pending.put("lastError", exec.get("lastError"));
        }
        pending.put("trackerRowId", exec.get("trackerRowId"));
        pending.put("archivePath", exec.get("archivePath"));
        pending.put("matchScore", exec.get("matchScore"));
        Path tmp = pendingFile.resolveSibling(id + ".json.tmp");
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pending));
        Files.move(tmp, pendingFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        result.put("ok", !errored);
        result.put("workflowSteps", exec.get("steps"));
        result.put("matchScore", exec.get("matchScore"));
        result.put("archivePath", exec.get("archivePath"));
        result.put("trackerRowId", exec.get("trackerRowId"));
        result.put("terminated", exec.get("terminated"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private int readPostingMaxAgeMinutes() {
        try {
            Map<String, Object> config = readConfigSafe();
            Map<String, Object> targeting = (Map<String, Object>) config.getOrDefault("targeting", Map.of());
            Object v = targeting.get("postingMaxAgeMinutes");
            if (v instanceof Number n) return n.intValue();
        } catch (Exception ignored) {}
        return 120; // sensible default per Spark's stated workflow
    }

    private Map<String, Object> readConfigSafe() {
        try {
            if (Files.exists(paths.config())) {
                return mapper.readValue(paths.config().toFile(), Map.class);
            }
        } catch (IOException e) {
            log.warn("Failed to read config.json: {}", e.getMessage());
        }
        return Map.of();
    }

    private String strOrEmpty(Object v) { return v == null ? "" : v.toString().trim(); }

    private void markSkipped(Path pendingFile, Map<String, Object> pending, String reason) throws IOException {
        pending.put("processed", true);
        pending.put("processedAt", Instant.now().toString());
        pending.put("skipped", true);
        pending.put("skipReason", reason);
        Path tmp = pendingFile.resolveSibling(pendingFile.getFileName().toString() + ".tmp");
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pending));
        Files.move(tmp, pendingFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static final List<String> TARGET_PHRASES = List.of(
            // "of" variants
            "director of engineering",
            "vp of engineering",
            "vice president of engineering",
            // comma variants (common LinkedIn form, e.g. "VP, Engineering")
            "director, engineering",
            "vp, engineering",
            "vice president, engineering",
            // bare juxtaposition (rare but seen)
            "vp engineering",
            "vice president engineering"
    );

    private boolean titleMatchesTargets(String title) {
        String lc = title.toLowerCase();
        for (String t : TARGET_PHRASES) if (lc.contains(t)) return true;
        return false;
    }
}
