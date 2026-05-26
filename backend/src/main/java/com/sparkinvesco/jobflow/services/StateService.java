package com.sparkinvesco.jobflow.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes state.json. Concurrency-safe via synchronized methods —
 * good enough for single-user localhost; revisit if we ever multi-tenant.
 */
@Service
public class StateService {

    private final GammaPaths paths;
    private final ObjectMapper mapper = new ObjectMapper();

    public StateService(GammaPaths paths) {
        this.paths = paths;
    }

    public synchronized Map<String, Object> read() throws IOException {
        Path file = paths.state();
        if (!Files.exists(file)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("version", "1");
            empty.put("lastPollAt", null);
            empty.put("applications", new LinkedHashMap<>());
            empty.put("skipped", new java.util.ArrayList<>());
            empty.put("errors", new java.util.ArrayList<>());
            return empty;
        }
        return mapper.readValue(file.toFile(), Map.class);
    }

    public synchronized void write(Map<String, Object> state) throws IOException {
        Path file = paths.state();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state));
        Files.move(tmp, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("unchecked")
    public synchronized void recordApplication(String key, Map<String, Object> entry) throws IOException {
        Map<String, Object> state = read();
        Map<String, Object> apps = (Map<String, Object>) state.computeIfAbsent("applications", k -> new LinkedHashMap<>());
        apps.put(key, entry);
        state.put("lastPollAt", Instant.now().toString());
        write(state);
    }
}
