package com.sparkinvesco.jobflow.controllers;

import com.sparkinvesco.jobflow.services.GammaPaths;
import com.sparkinvesco.jobflow.services.JsonFileService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConfigController {

    private final GammaPaths paths;
    private final JsonFileService json;

    public ConfigController(GammaPaths paths, JsonFileService json) {
        this.paths = paths;
        this.json = json;
    }

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getConfig() throws IOException {
        return json.readJson(paths.config());
    }

    @PutMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> putConfig(@RequestBody Map<String, Object> body) throws IOException {
        json.writeJson(paths.config(), body);
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/workflow", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getWorkflow() throws IOException {
        return json.readJson(paths.workflow());
    }

    @PutMapping(value = "/workflow", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> putWorkflow(@RequestBody Map<String, Object> body) throws IOException {
        json.writeJson(paths.workflow(), body);
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getState() throws IOException {
        return json.readJson(paths.state());
    }
}
