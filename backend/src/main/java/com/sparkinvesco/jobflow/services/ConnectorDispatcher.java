package com.sparkinvesco.jobflow.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dispatches a connector name to its implementation. Each connector
 * receives the node's params and the current execution context, returns
 * a result map that's merged back into context.
 */
@Service
public class ConnectorDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ConnectorDispatcher.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final GammaPaths paths;
    private final TailoringService tailoring;
    private final PdfRenderService pdfRender;
    private final TrackerService tracker;
    private final StateService state;
    private final PdfTextExtractor pdfText;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConnectorDispatcher(GammaPaths paths,
                               TailoringService tailoring,
                               PdfRenderService pdfRender,
                               TrackerService tracker,
                               StateService state,
                               PdfTextExtractor pdfText) {
        this.paths = paths;
        this.tailoring = tailoring;
        this.pdfRender = pdfRender;
        this.tracker = tracker;
        this.state = state;
        this.pdfText = pdfText;
    }

    public Map<String, Object> dispatch(String connector, Map<String, Object> params, Map<String, Object> ctx) throws IOException {
        log.info("connector dispatch: {} with params keys {}", connector, params.keySet());
        return switch (connector) {
            case "noop" -> Map.of("ok", true, "note", "no-op");
            case "claude.match_resume" -> matchResume(params, ctx);
            case "claude.tailor_resume" -> tailorResume(params, ctx);
            case "claude.export_pdf" -> exportPdf(params, ctx);
            case "fs.archive_to_company" -> archiveToCompany(params, ctx);
            case "xlsx.append_tracker_row" -> appendTrackerRow(params, ctx);
            case "human.handoff" -> humanHandoff(params, ctx);
            case "chrome.linkedin_search", "chrome.read_posting" -> chromeReadOnly(connector, ctx);
            case "chrome.easy_apply", "chrome.ats_apply" -> chromeSubmissionHandoff(connector, ctx);
            case "claude.classify_email", "xlsx.update_status" ->
                    notImplemented(connector, "Email pipeline. Phase 2.B.");
            default -> notImplemented(connector, "Unknown connector");
        };
    }

    // ---------- claude.match_resume ----------
    private Map<String, Object> matchResume(Map<String, Object> params, Map<String, Object> ctx) throws IOException {
        Path resume = pickBaseResume(params);
        String jdText = String.valueOf(ctx.getOrDefault("jdText", ""));
        if (resume == null) {
            return Map.of("ok", false, "skip", true, "reason", "no_resume_in_resumes_folder");
        }
        if (jdText.isBlank()) {
            return Map.of("ok", false, "skip", true, "reason", "empty_jd");
        }
        // Extract real text from the PDF (PDFBox) — raw-byte reads return mostly
        // compressed gibberish for PDFs and produce useless scores.
        String resumeText = pdfText.extractText(resume).toLowerCase();
        String jdLc = jdText.toLowerCase();
        Set<String> resumeTokens = tokenize(resumeText);
        Set<String> jdTokens = tokenize(jdLc);
        long overlap = jdTokens.stream().filter(resumeTokens::contains).count();
        // JD-coverage score: "what fraction of meaningful JD terms does the resume cover?"
        // More meaningful than resume-coverage for screening; also more stable across resume sizes.
        double score = jdTokens.isEmpty() ? 0.0
                : Math.min(1.0, (double) overlap / Math.max(1, jdTokens.size()));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("matchScore", score);
        r.put("matchOverlap", overlap);
        r.put("matchResumeTokens", resumeTokens.size());
        r.put("matchJdTokens", jdTokens.size());
        r.put("baseResume", paths.root().relativize(resume).toString());
        r.put("baseResumePath", resume.toString());
        return r;
    }

    private Path pickBaseResume(Map<String, Object> params) throws IOException {
        Path dir = paths.resumesFolder();
        if (!Files.isDirectory(dir)) return null;
        try (var s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".pdf") || n.endsWith(".docx");
            }).sorted().findFirst().orElse(null);
        }
    }

    // ---------- claude.tailor_resume ----------
    @SuppressWarnings("unchecked")
    private Map<String, Object> tailorResume(Map<String, Object> params, Map<String, Object> ctx) throws IOException {
        Path baseResume = Path.of(String.valueOf(ctx.get("baseResumePath")));
        String jdText = String.valueOf(ctx.getOrDefault("jdText", ""));
        Map<String, Object> ownerInfo = (Map<String, Object>) ctx.getOrDefault("ownerInfo", Map.of());

        // User-driven choice: skip the LLM and submit the base resume verbatim.
        if (Boolean.TRUE.equals(ctx.get("useBaseResume"))) {
            return Map.of(
                    "ok", true,
                    "tailored", false,
                    "tailoredHtml", "",
                    "fallback", "user_choice",
                    "reason", "User chose 'use base resume as-is' for this posting"
            );
        }
        if (!tailoring.isAvailable()) {
            return Map.of(
                    "ok", true,
                    "tailored", false,
                    "tailoredHtml", "",
                    "fallback", "base_resume",
                    "reason", "ANTHROPIC_API_KEY not set — submitted base resume as-is"
            );
        }

        String html = tailoring.tailorToHtml(baseResume, jdText, ownerInfo);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("tailored", true);
        r.put("tailoredHtml", html);
        r.put("htmlBytes", html.length());
        return r;
    }

    // ---------- claude.export_pdf ----------
    private Map<String, Object> exportPdf(Map<String, Object> params, Map<String, Object> ctx) throws IOException {
        String html = String.valueOf(ctx.getOrDefault("tailoredHtml", ""));
        Path baseResume = Path.of(String.valueOf(ctx.get("baseResumePath")));
        String company = String.valueOf(ctx.getOrDefault("company", "Unknown"));
        String title = String.valueOf(ctx.getOrDefault("title", "Posting"));
        String authorName = String.valueOf(((Map<String, Object>) ctx.getOrDefault("ownerInfo", Map.of())).getOrDefault("name", "Spark"));

        // Build target path under the OS temp area. archive_to_company will move it.
        String dateStr = LocalDateTime.now(ZoneId.of("America/New_York")).format(DATE_FMT);
        String stem = "Resume" + sanitize(authorName).replace("_", "") + "_" + sanitize(title) + "_" + dateStr;
        Path tmpOut = Files.createTempFile("jobflow-pdf-", ".pdf");

        if (html.isBlank()) {
            // Fallback path — copy the base resume into the temp location verbatim.
            Files.copy(baseResume, tmpOut, StandardCopyOption.REPLACE_EXISTING);
            return Map.of(
                    "ok", true,
                    "rendered", false,
                    "renderedPdfPath", tmpOut.toString(),
                    "resumeFilename", stem + ".pdf",
                    "fallback", "base_resume"
            );
        }
        pdfRender.renderToFile(html, tmpOut, authorName);
        return Map.of(
                "ok", true,
                "rendered", true,
                "renderedPdfPath", tmpOut.toString(),
                "resumeFilename", stem + ".pdf"
        );
    }

    // ---------- fs.archive_to_company ----------
    private Map<String, Object> archiveToCompany(Map<String, Object> params, Map<String, Object> ctx) throws IOException {
        Path tmpPdf = Path.of(String.valueOf(ctx.get("renderedPdfPath")));
        String company = String.valueOf(ctx.getOrDefault("company", "Unknown"));
        String filename = String.valueOf(ctx.getOrDefault("resumeFilename", "resume.pdf"));

        Path companyDir = paths.archivesFolder().resolve(sanitize(company));
        Files.createDirectories(companyDir);
        Path target = uniquePath(companyDir.resolve(filename));
        Files.move(tmpPdf, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

        // Sidecar metadata
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("company", company);
        meta.put("title", ctx.get("title"));
        meta.put("location", ctx.get("location"));
        meta.put("workMode", ctx.get("workMode"));
        meta.put("postingUrl", ctx.get("postingUrl"));
        meta.put("applicantCountAtSubmit", ctx.get("applicantCount"));
        meta.put("submittedAt", Instant.now().toString());
        meta.put("matchScore", ctx.get("matchScore"));
        meta.put("tailored", ctx.getOrDefault("tailoredHtml", "").toString().length() > 0);
        meta.put("source", "react-ui");
        Path metaFile = target.resolveSibling(target.getFileName().toString().replaceFirst("\\.pdf$", "") + ".json");
        Files.write(metaFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));

        return Map.of(
                "ok", true,
                "archivePath", paths.root().relativize(target).toString(),
                "archiveFolder", paths.root().relativize(companyDir).toString(),
                "metaPath", paths.root().relativize(metaFile).toString()
        );
    }

    // ---------- xlsx.append_tracker_row ----------
    private Map<String, Object> appendTrackerRow(Map<String, Object> params, Map<String, Object> ctx) throws IOException {
        // If a chrome submission connector ran but no human-approved submit happened yet,
        // record the row as "Ready for Submit" not "Applied". A subsequent
        // /api/pending/{id}/mark-submitted call flips it to "Applied".
        boolean awaiting = Boolean.TRUE.equals(ctx.get("awaitingSubmission"));
        String status = awaiting ? "Ready for Submit" : "Applied";

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("applicationDate", java.util.Date.from(Instant.now()));
        row.put("company", ctx.get("company"));
        row.put("jobTitle", ctx.get("title"));
        row.put("location", ctx.get("location"));
        row.put("workMode", ctx.get("workMode"));
        row.put("postingUrl", ctx.get("postingUrl"));
        Object applicantCount = ctx.get("applicantCount");
        row.put("applicantCountAtSubmit", applicantCount instanceof Number n ? n.intValue() : null);
        row.put("applicationPath", null);
        row.put("resumeUsed", ctx.get("resumeFilename"));
        row.put("resumeFolder", ctx.get("archiveFolder"));
        row.put("confirmationId", null);
        row.put("status", status);
        row.put("lastStatusChange", java.util.Date.from(Instant.now()));
        row.put("recruiterContact", null);
        boolean tailored = !String.valueOf(ctx.getOrDefault("tailoredHtml", "")).isBlank();
        StringBuilder notes = new StringBuilder();
        notes.append(tailored
                ? "Tailored by Claude API. "
                : "Base resume as-is (no LLM tailoring). ");
        notes.append(awaiting
                ? "Awaiting human-approved Submit click — call mark-submitted after submitting."
                : "Local pipeline only.");
        row.put("notes", notes.toString());
        int rowNum = tracker.appendApplicationRow(row);

        // Update state.json too
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("company", ctx.get("company"));
        entry.put("title", ctx.get("title"));
        entry.put("url", ctx.get("postingUrl"));
        entry.put("preparedAt", Instant.now().toString());
        if (!awaiting) entry.put("submittedAt", Instant.now().toString());
        entry.put("resumeUsed", ctx.get("resumeFilename"));
        entry.put("archivePath", ctx.get("archiveFolder"));
        entry.put("status", status);
        entry.put("lastStatusChange", Instant.now().toString());
        entry.put("applicantCountAtSubmit", applicantCount);
        entry.put("trackerRowId", rowNum);
        entry.put("watching", true);
        entry.put("awaitingSubmission", awaiting);
        String key = String.valueOf(ctx.getOrDefault("postingUrl", ctx.get("pendingId")));
        if (key.isBlank()) key = String.valueOf(ctx.get("pendingId"));
        state.recordApplication(key, entry);

        return Map.of("ok", true, "trackerRowId", rowNum, "status", status);
    }

    // ---------- human.handoff ----------
    private Map<String, Object> humanHandoff(Map<String, Object> params, Map<String, Object> ctx) {
        return Map.of("ok", true, "handoff", true, "reason", String.valueOf(params.getOrDefault("reason", "user_attention_required")));
    }

    // ---------- helpers ----------
    private Map<String, Object> notImplemented(String connector, String reason) {
        return Map.of("ok", false, "skipped", true, "reason", reason, "connector", connector);
    }

    /**
     * Read-only Chrome connectors (search, read posting): work happens via Cowork's
     * Claude-in-Chrome extension when Spark is in chat. From Java's perspective these
     * are no-ops that pass through; the workflow continues to local-only steps.
     */
    private Map<String, Object> chromeReadOnly(String connector, Map<String, Object> ctx) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("connector", connector);
        r.put("note", "read-only Chrome connector — handled interactively in Cowork chat, no autonomous action");
        return r;
    }

    /**
     * Submission Chrome connectors (easy_apply, ats_apply): mark the application as
     * awaiting human-approved submit. The workflow STILL continues to archive + tracker
     * so Spark has the prepared PDF and a queued row, but the row is recorded with status
     * "Ready for Submit" instead of "Applied". A separate /api/pending/{id}/mark-submitted
     * call flips it to Applied after the actual Submit click in the browser.
     */
    private Map<String, Object> chromeSubmissionHandoff(String connector, Map<String, Object> ctx) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("connector", connector);
        r.put("awaitingSubmission", true);
        r.put("submissionInstruction",
                "Open " + ctx.getOrDefault("postingUrl", "<posting URL>") + " in your browser. " +
                "Use the tailored PDF at archives/<Company>/. Pause before clicking Submit; " +
                "after submitting, POST to /api/pending/{id}/mark-submitted with the confirmation ID.");
        return r;
    }

    private static final Set<String> STOPWORDS = Set.of(
            "the","and","with","this","that","from","have","will","they","their","them",
            "your","yours","ours","into","over","under","also","such","than","then","when",
            "what","which","while","were","been","being","does","doing","more","most","some",
            "other","each","about","across","along","because","between","during","without",
            "through","upon","onto","very","just","only");

    private Set<String> tokenize(String s) {
        Set<String> out = new HashSet<>();
        for (String tok : s.split("[^a-zA-Z0-9]+")) {
            if (tok.length() < 4) continue;
            String lc = tok.toLowerCase();
            if (STOPWORDS.contains(lc)) continue;
            out.add(lc);
        }
        return out;
    }

    private String sanitize(String name) {
        return name.trim().replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private Path uniquePath(Path desired) {
        if (!Files.exists(desired)) return desired;
        String name = desired.getFileName().toString();
        String stem = name.replaceFirst("\\.[^.]+$", "");
        String ext = name.substring(stem.length());
        for (int v = 2; v < 999; v++) {
            Path candidate = desired.resolveSibling(stem + "_v" + v + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return desired.resolveSibling(stem + "_" + Instant.now().toEpochMilli() + ext);
    }
}
