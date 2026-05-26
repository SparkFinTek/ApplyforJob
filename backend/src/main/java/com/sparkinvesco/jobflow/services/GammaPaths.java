package com.sparkinvesco.jobflow.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves absolute paths to the Project Gamma working files.
 * The root is configured via jobflow.gamma.root in application.yml.
 */
@Component
public class GammaPaths {

    private final Path root;

    public GammaPaths(@Value("${jobflow.gamma.root:..}") String rootValue) {
        this.root = Paths.get(rootValue).toAbsolutePath().normalize();
    }

    public Path root()           { return root; }
    public Path config()         { return root.resolve("config.json"); }
    public Path workflow()       { return root.resolve("workflow.json"); }
    public Path state()          { return root.resolve("state.json"); }
    public Path tracker()        { return root.resolve("tracker.xlsx"); }
    public Path resumesFolder()  { return root.resolve("resumes"); }
    public Path archivesFolder() { return root.resolve("archives"); }
}
