package com.sparkinvesco.jobflow.controllers;

import com.sparkinvesco.jobflow.services.GammaPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final GammaPaths paths;

    public HealthController(GammaPaths paths) {
        this.paths = paths;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("gammaRoot", paths.root().toString());
        m.put("configFound", Files.exists(paths.config()));
        m.put("workflowFound", Files.exists(paths.workflow()));
        m.put("trackerFound", Files.exists(paths.tracker()));
        m.put("stateFound", Files.exists(paths.state()));
        return m;
    }
}
