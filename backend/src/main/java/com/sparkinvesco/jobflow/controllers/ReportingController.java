package com.sparkinvesco.jobflow.controllers;

import com.sparkinvesco.jobflow.services.ReportingService;
import com.sparkinvesco.jobflow.services.TrackerService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reporting")
public class ReportingController {

    private final ReportingService reporting;
    private final TrackerService tracker;

    public ReportingController(ReportingService reporting, TrackerService tracker) {
        this.reporting = reporting;
        this.tracker = tracker;
    }

    @GetMapping(value = "/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Integer> snapshot() throws IOException {
        return tracker.snapshot();
    }

    @GetMapping(value = "/daily", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> daily(@RequestParam(defaultValue = "30") int days) throws IOException {
        return reporting.daily(Math.max(1, Math.min(days, 365)));
    }

    @GetMapping(value = "/weekly", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> weekly(@RequestParam(defaultValue = "12") int weeks) throws IOException {
        return reporting.weekly(Math.max(1, Math.min(weeks, 104)));
    }

    @GetMapping(value = "/monthly", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> monthly(@RequestParam(defaultValue = "12") int months) throws IOException {
        return reporting.monthly(Math.max(1, Math.min(months, 60)));
    }
}
