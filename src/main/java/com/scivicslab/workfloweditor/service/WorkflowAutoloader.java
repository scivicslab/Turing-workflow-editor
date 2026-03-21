package com.scivicslab.workfloweditor.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads a workflow YAML file at startup when -Dworkflow.autoload=/path/to/file.yaml is specified.
 * The file is parsed and imported into the editor state so the UI shows it immediately.
 * Works with both JVM mode and native binary (Quarkus parses -D flags as config properties).
 */
@ApplicationScoped
public class WorkflowAutoloader {

    private static final Logger LOG = Logger.getLogger(WorkflowAutoloader.class.getName());

    @Inject
    WorkflowState state;

    @ConfigProperty(name = "workflow.autoload")
    Optional<String> autoloadPath;

    void onStart(@Observes StartupEvent ev) {
        String path = autoloadPath.orElse(null);
        if (path == null || path.isBlank()) {
            return;
        }

        // Resolve ~ to home directory
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }

        var file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            LOG.warning("workflow.autoload file not found: " + path);
            return;
        }

        try {
            String yaml = Files.readString(file);
            var parsed = WorkflowRunner.fromYaml(yaml);
            var rows = WorkflowRunner.stepsToRows(parsed.steps());
            state.replaceAll(parsed.name(), rows, state.getMaxIterations());
            if (parsed.description() != null) {
                state.setDescription(parsed.description());
            }
            LOG.info("Autoloaded workflow from " + path + ": " + parsed.name()
                    + " (" + parsed.steps().size() + " steps)");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to autoload workflow from " + path, e);
        }
    }
}
