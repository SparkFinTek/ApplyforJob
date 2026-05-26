package com.sparkinvesco.jobflow.controllers;

import com.sparkinvesco.jobflow.services.TrackerService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applications")
public class ApplicationsController {

    private final TrackerService tracker;

    public ApplicationsController(TrackerService tracker) {
        this.tracker = tracker;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> all() throws IOException {
        return tracker.readApplications();
    }

    @GetMapping(value = "/{rowId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> one(@PathVariable int rowId) throws IOException {
        return tracker.readApplications().stream()
                .filter(r -> Integer.valueOf(rowId).equals(r.get("rowId")))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("rowId " + rowId + " not found"));
    }
}
