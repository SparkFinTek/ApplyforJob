package com.sparkinvesco.jobflow.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkinvesco.jobflow.services.GammaPaths;
import com.sparkinvesco.jobflow.services.ProcessingService;
import com.sparkinvesco.jobflow.services.SubmissionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Pending JDs queued from the React /search page.
 *
 * Each capture is written to <gammaRoot>/pending/<id>.json. Claude reads the folder
 * when Spark says "process pending", runs the per-posting flow on each item, and
 * marks the file with processed=true (it is NEVER deleted, per project rules).
 */
@RestController
@RequestMapping("/api/pending")
public class PendingController {

    private final GammaPaths paths;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessingService processing;
    private final SubmissionService submission;

    public PendingController(GammaPaths paths, ProcessingService processing, SubmissionService submission) {
        this.paths = paths;
        this.processing = processing;
        this.submission = submission;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> list() throws IOException {
        Path dir = paths.root().resolve("pending");
        if (!Files.exists(dir)) return List.of();
        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p -> {
                try {
                    Map<String, Object> obj = mapper.readValue(p.toFile(), Map.class);
                    obj.put("id", p.getFileName().toString().replace(".json", ""));
                    items.add(obj);
                } catch (IOException ignored) {}
            });
        }
        return items;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body) throws IOException {
        Path dir = paths.root().resolve("pending");
        Files.createDirectories(dir);
        String id = String.valueOf(Instant.now().toEpochMilli());
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("id", id);
        obj.put("capturedAt", Instant.now().toString());
        obj.put("title", body.get("title"));
        obj.put("company", body.get("company"));
        obj.put("location", body.get("location"));
        obj.put("workMode", body.get("workMode"));
        obj.put("postingUrl", body.get("postingUrl"));
        obj.put("applicantCount", body.get("applicantCount"));
        obj.put("postedMinutesAgo", body.get("postedMinutesAgo"));
        obj.put("reposted", body.get("reposted"));
        obj.put("useBaseResume", body.get("useBaseResume"));
        obj.put("jd", body.get("jd"));
        obj.put("processed", false);
        Path file = dir.resolve(id + ".json");
        Path tmp = dir.resolve(id + ".json.tmp");
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj));
        Files.move(tmp, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return ResponseEntity.ok(obj);
    }

    @PostMapping(value = "/{id}/process", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> process(@PathVariable String id) throws IOException {
        return processing.processPending(id);
    }

    /**
     * Re-run an already-processed pending row with an optional strategy override.
     * Body: { useBaseResume?: boolean }
     * Resets processed/skipped/etc. on the pending file (keeps original capture data),
     * applies the new useBaseResume if provided, then runs the standard process flow.
     * Will not re-run an item that's already been submitted (submitted: true).
     */
    @PostMapping(value = "/{id}/reprocess", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> reprocess(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) throws IOException {
        Path pendingFile = paths.root().resolve("pending").resolve(id + ".json");
        if (!Files.exists(pendingFile)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("reason", "pending file not found");
            return err;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = mapper.readValue(pendingFile.toFile(), Map.class);

        if (Boolean.TRUE.equals(pending.get("submitted"))) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("reason", "already submitted — won't re-run a submitted application. Capture a new posting instead.");
            return err;
        }

        // Strip the per-run state (keep original capture fields) so processPending starts fresh.
        for (String k : List.of(
                "processed", "processedAt", "trackerRowId", "archivePath",
                "matchScore", "skipped", "skipReason", "lastError")) {
            pending.remove(k);
        }
        pending.put("processed", false);

        // Apply strategy override if provided.
        if (body != null && body.containsKey("useBaseResume")) {
            pending.put("useBaseResume", Boolean.TRUE.equals(body.get("useBaseResume")));
        }
        pending.put("_lastReprocessAt", java.time.Instant.now().toString());

        // Atomic save.
        Path tmp = pendingFile.resolveSibling(id + ".json.tmp");
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pending));
        Files.move(tmp, pendingFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        // Now run the normal process flow.
        return processing.processPending(id);
    }

    @PostMapping(value = "/process-all", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> processAll() throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> p : list()) {
            if (Boolean.TRUE.equals(p.get("processed"))) continue;
            String id = (String) p.get("id");
            try {
                results.add(processing.processPending(id));
            } catch (Exception e) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                r.put("ok", false);
                r.put("reason", "exception: " + e.getMessage());
                results.add(r);
            }
        }
        return results;
    }

    @PostMapping(value = "/{id}/mark-submitted", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> markSubmitted(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) throws IOException {
        return submission.markSubmitted(id, body);
    }

    @PostMapping(value = "/{id}/done", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> markDone(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) throws IOException {
        Path file = paths.root().resolve("pending").resolve(id + ".json");
        if (!Files.exists(file)) throw new RuntimeException("pending id not found: " + id);
        Map<String, Object> obj = mapper.readValue(file.toFile(), Map.class);
        obj.put("processed", true);
        obj.put("processedAt", Instant.now().toString());
        if (body != null) {
            obj.put("trackerRowId", body.get("trackerRowId"));
            obj.put("archivePath", body.get("archivePath"));
            obj.put("note", body.get("note"));
        }
        Path tmp = file.resolveSibling(id + ".json.tmp");
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj));
        Files.move(tmp, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return obj;
    }
}
