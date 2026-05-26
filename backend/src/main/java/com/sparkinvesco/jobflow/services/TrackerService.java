package com.sparkinvesco.jobflow.services;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads tracker.xlsx → Applications sheet and surfaces it as a list of maps.
 * Read-only for now. Mutations come later via append/update endpoints.
 */
@Service
public class TrackerService {

    private static final String SHEET = "Applications";
    private static final List<String> COLUMNS = Arrays.asList(
            "applicationDate", "company", "jobTitle", "location", "workMode",
            "postingUrl", "applicantCountAtSubmit", "applicationPath", "resumeUsed",
            "resumeFolder", "confirmationId", "status", "lastStatusChange",
            "recruiterContact", "notes"
    );

    public static final Set<String> INTERVIEWED = Set.of("Interview Scheduled", "Offer", "Hired");
    public static final Set<String> YET_TO_INTERVIEW = Set.of("Applied", "Acknowledged", "In Review", "Recruiter Outreach");
    public static final Set<String> IN_PROGRESS = Set.of(
            "Applied", "Acknowledged", "In Review", "Recruiter Outreach", "Interview Scheduled", "Offer");
    public static final Set<String> TERMINAL = Set.of("Hired", "Rejected", "Withdrawn");

    private final GammaPaths paths;

    public TrackerService(GammaPaths paths) {
        this.paths = paths;
    }

    /**
     * Append one application row to the Applications sheet. Preserves formulas,
     * data validation, and named ranges. Writes atomically.
     *
     * @return the row number written (1-based)
     */
    public synchronized int appendApplicationRow(Map<String, Object> values) throws IOException {
        Path tracker = paths.tracker();
        if (!Files.exists(tracker)) {
            throw new IOException("tracker.xlsx not found at " + tracker);
        }
        try (InputStream is = Files.newInputStream(tracker);
             org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheet(SHEET);
            if (sheet == null) throw new IOException("Sheet '" + SHEET + "' not found in tracker.xlsx");

            // Find the next empty row by scanning column B (Company). Header is row 0.
            // The starter tracker pre-formats body rows 2..1000 with empty cells (BLANK type),
            // so we must treat BLANK cells and empty STRING cells as "this row is free".
            int targetRow = 1;
            while (true) {
                Row r = sheet.getRow(targetRow);
                if (r == null) break;
                Cell c = r.getCell(1); // column B = Company
                if (c == null) break;
                CellType ct = c.getCellType();
                if (ct == CellType.BLANK) break;
                if (ct == CellType.STRING && c.getStringCellValue().isBlank()) break;
                targetRow++;
            }
            Row row = sheet.getRow(targetRow);
            if (row == null) row = sheet.createRow(targetRow);

            // Column index → key in COLUMNS list (matches the schema).
            for (int c = 0; c < COLUMNS.size(); c++) {
                String key = COLUMNS.get(c);
                Object v = values.get(key);
                Cell cell = row.getCell(c);
                if (cell == null) cell = row.createCell(c);
                writeCellValue(cell, v);
            }

            // Atomic save: write to .tmp then rename
            Path tmp = tracker.resolveSibling(tracker.getFileName().toString() + ".tmp");
            try (java.io.OutputStream os = Files.newOutputStream(tmp)) {
                wb.write(os);
            }
            Files.move(tmp, tracker,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return targetRow + 1; // user-facing row number is 1-based
        }
    }

    private void writeCellValue(Cell cell, Object v) {
        if (v == null) {
            cell.setBlank();
            return;
        }
        if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v instanceof Boolean b) {
            cell.setCellValue(b);
        } else if (v instanceof java.util.Date d) {
            cell.setCellValue(d);
        } else if (v instanceof java.time.Instant i) {
            cell.setCellValue(java.util.Date.from(i));
        } else if (v instanceof java.time.LocalDateTime ldt) {
            cell.setCellValue(java.util.Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant()));
        } else {
            cell.setCellValue(String.valueOf(v));
        }
    }

    public List<Map<String, Object>> readApplications() throws IOException {
        if (!Files.exists(paths.tracker())) {
            return new ArrayList<>();
        }
        try (InputStream is = Files.newInputStream(paths.tracker());
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheet(SHEET);
            if (sheet == null) return new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("rowId", r);
                boolean hasContent = false;
                for (int c = 0; c < COLUMNS.size(); c++) {
                    Cell cell = row.getCell(c);
                    Object value = cellValue(cell);
                    if (value != null && !value.toString().isBlank()) hasContent = true;
                    obj.put(COLUMNS.get(c), value);
                }
                if (hasContent) rows.add(obj);
            }
            return rows;
        }
    }

    /**
     * Bucket counts for the Reporting "snapshot" endpoint.
     */
    public Map<String, Integer> snapshot() throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("Applied", "Acknowledged", "In Review", "Recruiter Outreach",
                "Interview Scheduled", "Offer", "Hired", "Rejected", "Withdrawn", "Stalled")) {
            counts.put(s, 0);
        }
        int interviewed = 0, yet = 0, inProgress = 0, total = 0, top10 = 0;
        for (Map<String, Object> row : readApplications()) {
            total++;
            Object status = row.get("status");
            String s = status == null ? "" : status.toString();
            counts.merge(s, 1, Integer::sum);
            if (INTERVIEWED.contains(s)) interviewed++;
            if (YET_TO_INTERVIEW.contains(s)) yet++;
            if (IN_PROGRESS.contains(s)) inProgress++;
            Object cnt = row.get("applicantCountAtSubmit");
            if (cnt instanceof Number n && n.intValue() > 0 && n.intValue() < 10) top10++;
        }
        Map<String, Integer> out = new LinkedHashMap<>(counts);
        out.put("totalApplications", total);
        out.put("inProgress", inProgress);
        out.put("interviewed", interviewed);
        out.put("yetToBeInterviewed", yet);
        out.put("top10Hits", top10);
        return out;
    }

    private Object cellValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toInstant().toString();
                }
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) yield (long) d;
                yield d;
            }
            case FORMULA -> {
                CellType cached = cell.getCachedFormulaResultType();
                yield switch (cached) {
                    case STRING -> cell.getStringCellValue();
                    case NUMERIC -> cell.getNumericCellValue();
                    case BOOLEAN -> cell.getBooleanCellValue();
                    default -> null;
                };
            }
            default -> null;
        };
    }

    /**
     * Convert an Object that may be Instant ISO-string or epoch millis into a LocalDate (Eastern).
     */
    public static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue()).atZone(ZoneId.of("America/New_York")).toLocalDate();
        }
        try {
            return Instant.parse(v.toString()).atZone(ZoneId.of("America/New_York")).toLocalDate();
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(v.toString().substring(0, 10));
            } catch (Exception ignored2) {
                return null;
            }
        }
    }
}
