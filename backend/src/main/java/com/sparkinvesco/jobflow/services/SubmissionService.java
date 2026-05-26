package com.sparkinvesco.jobflow.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flips a previously-prepared application from "Ready for Submit" → "Applied"
 * after the human-approved Submit click in the browser. Updates both the
 * tracker row and state.json atomically.
 *
 * Inputs (from request body):
 *   - confirmationId  (string, optional) — ATS confirmation number if shown
 *   - applicationPath (string, optional) — "LinkedIn Easy Apply", "Workday",
 *     "Greenhouse", "Lever", "iCIMS", "Taleo", "SmartRecruiters", "Other"
 *   - note            (string, optional) — free-text observations
 */
@Service
public class SubmissionService {

    private final GammaPaths paths;
    private final StateService state;
    private final ObjectMapper mapper = new ObjectMapper();

    public SubmissionService(GammaPaths paths, StateService state) {
        this.paths = paths;
        this.state = state;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> markSubmitted(String pendingId, Map<String, Object> body) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", pendingId);

        Path pendingFile = paths.root().resolve("pending").resolve(pendingId + ".json");
        if (!Files.exists(pendingFile)) {
            result.put("ok", false);
            result.put("reason", "pending file not found");
            return result;
        }
        Map<String, Object> pending = mapper.readValue(pendingFile.toFile(), Map.class);

        Object trackerRowIdObj = pending.get("trackerRowId");
        if (!(trackerRowIdObj instanceof Number)) {
            result.put("ok", false);
            result.put("reason", "no trackerRowId on pending — was the application prepared?");
            return result;
        }
        int trackerRowId = ((Number) trackerRowIdObj).intValue();

        String confirmationId = body == null ? null : asStr(body.get("confirmationId"));
        String applicationPath = body == null ? null : asStr(body.get("applicationPath"));
        String note = body == null ? null : asStr(body.get("note"));

        // 1. Update tracker.xlsx — set Status, ApplicationPath, ConfirmationId, last change
        int updatedRow = updateTrackerRow(trackerRowId, applicationPath, confirmationId, note);

        // 2. Update state.json — flip status, set submittedAt, awaitingSubmission=false
        String stateKey = asStr(pending.get("postingUrl"));
        if (stateKey.isBlank()) stateKey = pendingId;
        Map<String, Object> stateMap = state.read();
        Map<String, Object> apps = (Map<String, Object>) stateMap.computeIfAbsent("applications", k -> new LinkedHashMap<>());
        Map<String, Object> entry = (Map<String, Object>) apps.get(stateKey);
        if (entry == null) {
            entry = new LinkedHashMap<>();
            apps.put(stateKey, entry);
        }
        entry.put("status", "Applied");
        entry.put("submittedAt", Instant.now().toString());
        entry.put("lastStatusChange", Instant.now().toString());
        entry.put("awaitingSubmission", false);
        if (confirmationId != null && !confirmationId.isBlank()) entry.put("confirmationId", confirmationId);
        if (applicationPath != null && !applicationPath.isBlank()) entry.put("applicationPath", applicationPath);
        state.write(stateMap);

        // 3. Update pending file — record submitted=true with details
        pending.put("submitted", true);
        pending.put("submittedAt", Instant.now().toString());
        if (confirmationId != null) pending.put("confirmationId", confirmationId);
        if (applicationPath != null) pending.put("applicationPath", applicationPath);
        if (note != null) pending.put("submitNote", note);
        Path tmp = pendingFile.resolveSibling(pendingId + ".json.tmp");
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pending));
        Files.move(tmp, pendingFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        result.put("ok", true);
        result.put("trackerRowId", updatedRow);
        result.put("statusBefore", "Ready for Submit");
        result.put("statusAfter", "Applied");
        return result;
    }

    /** Update specific cells in the existing tracker row. Atomic write. */
    private synchronized int updateTrackerRow(int rowId, String applicationPath, String confirmationId, String note) throws IOException {
        Path tracker = paths.tracker();
        try (InputStream is = Files.newInputStream(tracker);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheet("Applications");
            if (sheet == null) throw new IOException("Applications sheet not found");
            // rowId is 1-based (user-facing). POI is 0-based.
            Row row = sheet.getRow(rowId - 1);
            if (row == null) throw new IOException("tracker row " + rowId + " does not exist");

            // Column indexes (0-based) per the schema:
            //  H = 7 = applicationPath
            //  K = 10 = confirmationId
            //  L = 11 = status
            //  M = 12 = lastStatusChange
            //  O = 14 = notes
            if (applicationPath != null && !applicationPath.isBlank()) {
                Cell c = row.getCell(7);
                if (c == null) c = row.createCell(7);
                c.setCellType(CellType.STRING);
                c.setCellValue(applicationPath);
            }
            if (confirmationId != null && !confirmationId.isBlank()) {
                Cell c = row.getCell(10);
                if (c == null) c = row.createCell(10);
                c.setCellValue(confirmationId);
            }
            // Status → "Applied"
            Cell statusCell = row.getCell(11);
            if (statusCell == null) statusCell = row.createCell(11);
            statusCell.setCellValue("Applied");
            // lastStatusChange → now
            Cell lscCell = row.getCell(12);
            if (lscCell == null) lscCell = row.createCell(12);
            lscCell.setCellValue(java.util.Date.from(Instant.now()));
            // Notes → append
            if (note != null && !note.isBlank()) {
                Cell n = row.getCell(14);
                String existing = (n != null && n.getCellType() == CellType.STRING) ? n.getStringCellValue() : "";
                if (n == null) n = row.createCell(14);
                n.setCellValue((existing.isBlank() ? "" : existing + " | ") + note);
            }

            Path tmp = tracker.resolveSibling(tracker.getFileName().toString() + ".tmp");
            try (java.io.OutputStream os = Files.newOutputStream(tmp)) {
                wb.write(os);
            }
            Files.move(tmp, tracker,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return rowId;
        }
    }

    private String asStr(Object v) {
        return v == null ? null : v.toString();
    }
}
