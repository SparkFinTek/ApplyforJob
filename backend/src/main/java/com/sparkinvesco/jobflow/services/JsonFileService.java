package com.sparkinvesco.jobflow.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Read / write the JSON files in Project Gamma.
 * Writes are atomic-ish via tmp+rename to avoid partial writes if the JVM dies mid-flush.
 * Files are NEVER deleted by this service (per project rules).
 */
@Service
public class JsonFileService {

    private final ObjectMapper mapper = new ObjectMapper();

    public synchronized Map<String, Object> readJson(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path);
        }
        return mapper.readValue(path.toFile(), Map.class);
    }

    /**
     * Atomic, synchronized write. Concurrent callers serialize on this method,
     * and each call uses a UUID-suffixed tmp path so two in-flight writes can
     * never step on each other's tmp file.
     */
    public synchronized void writeJson(Path path, Object payload) throws IOException {
        Files.createDirectories(path.getParent());
        String suffix = ".tmp." + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path tmp = path.resolveSibling(path.getFileName().toString() + suffix);
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter()
                              .writeValueAsBytes(payload);
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            // If atomic move succeeded, tmp no longer exists; if it failed, clean up.
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    public String readRaw(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
