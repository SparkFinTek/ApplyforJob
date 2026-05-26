package com.sparkinvesco.jobflow.services;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes daily / weekly / monthly metrics from tracker.xlsx.
 * The shape mirrors the Reporting sheet in tracker.xlsx so the React UI
 * matches what the user already sees in Excel.
 */
@Service
public class ReportingService {

    private final TrackerService tracker;

    public ReportingService(TrackerService tracker) {
        this.tracker = tracker;
    }

    public List<Map<String, Object>> daily(int days) throws IOException {
        return periodBuckets(days, "day");
    }

    public List<Map<String, Object>> weekly(int weeks) throws IOException {
        return periodBuckets(weeks, "week");
    }

    public List<Map<String, Object>> monthly(int months) throws IOException {
        return periodBuckets(months, "month");
    }

    private List<Map<String, Object>> periodBuckets(int n, String unit) throws IOException {
        List<Map<String, Object>> applications = tracker.readApplications();
        List<Map<String, Object>> result = new ArrayList<>(n);
        LocalDate today = LocalDate.now();

        for (int i = n - 1; i >= 0; i--) {
            LocalDate start, end;
            String label;
            switch (unit) {
                case "day" -> {
                    start = today.minusDays(i);
                    end = start.plusDays(1);
                    label = start.toString();
                }
                case "week" -> {
                    LocalDate thisMon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    start = thisMon.minusWeeks(i);
                    end = start.plusWeeks(1);
                    label = "Week of " + start;
                }
                case "month" -> {
                    YearMonth ym = YearMonth.from(today).minusMonths(i);
                    start = ym.atDay(1);
                    end = ym.plusMonths(1).atDay(1);
                    label = ym.toString();
                }
                default -> throw new IllegalArgumentException("unit: " + unit);
            }

            int submitted = 0, interviewed = 0, yet = 0, inProgress = 0, top10 = 0;
            for (Map<String, Object> row : applications) {
                LocalDate d = TrackerService.toLocalDate(row.get("applicationDate"));
                if (d == null) continue;
                if (d.isBefore(start) || !d.isBefore(end)) continue;
                submitted++;
                String s = String.valueOf(row.getOrDefault("status", ""));
                if (TrackerService.INTERVIEWED.contains(s)) interviewed++;
                if (TrackerService.YET_TO_INTERVIEW.contains(s)) yet++;
                if (TrackerService.IN_PROGRESS.contains(s)) inProgress++;
                Object cnt = row.get("applicantCountAtSubmit");
                if (cnt instanceof Number num && num.intValue() > 0 && num.intValue() < 10) top10++;
            }

            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("period", label);
            bucket.put("start", start.toString());
            bucket.put("end", end.toString());
            bucket.put("submitted", submitted);
            bucket.put("interviewed", interviewed);
            bucket.put("yetToBeInterviewed", yet);
            bucket.put("inProgress", inProgress);
            bucket.put("top10Hits", top10);
            result.add(bucket);
        }
        return result;
    }
}
