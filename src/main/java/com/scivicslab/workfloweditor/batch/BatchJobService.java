package com.scivicslab.workfloweditor.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class BatchJobService {

    private static final Logger logger = Logger.getLogger(BatchJobService.class.getName());

    @Inject
    BatchJobService self;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "workflow.cli.jar", defaultValue = "")
    String cliJarPath;

    @Transactional
    public BatchJob create(String name, String yaml, Map<String, String> parameters) {
        BatchJob job = new BatchJob();
        job.id = UUID.randomUUID().toString();
        job.name = name != null ? name : "job";
        job.yaml = yaml;
        job.parameters = toJson(parameters);
        job.status = "PENDING";
        job.createdAt = Instant.now();
        job.persist();
        return job;
    }

    public BatchJob findById(String id) {
        return BatchJob.findById(id);
    }

    @SuppressWarnings("unchecked")
    public List<BatchJob> listRecent() {
        return BatchJob.find("ORDER BY createdAt DESC").page(0, 20).list();
    }

    @Transactional
    public boolean delete(String id) {
        BatchJob job = BatchJob.findById(id);
        if (job == null || "RUNNING".equals(job.status)) return false;
        job.delete();
        return true;
    }

    @Transactional
    public String startJob(String id) {
        BatchJob job = BatchJob.findById(id);
        if (job == null) return "error: not found";
        if ("RUNNING".equals(job.status)) return "error: already running";

        job.status = "RUNNING";
        job.startedAt = Instant.now();

        String yaml = job.yaml;
        Map<String, String> params = fromJson(job.parameters);

        Thread.startVirtualThread(() -> executeJob(id, yaml, params));
        return "started";
    }

    private void executeJob(String jobId, String yaml, Map<String, String> params) {
        Path yamlFile = null;
        Path varsFile = null;
        try {
            yamlFile = Files.createTempFile("job-" + jobId + "-", ".yaml");
            Files.writeString(yamlFile, yaml);

            varsFile = Files.createTempFile("job-" + jobId + "-vars-", ".yaml");
            StringBuilder varsYaml = new StringBuilder("vars:\n");
            if (params != null) {
                for (var entry : params.entrySet()) {
                    String val = entry.getValue() != null ? entry.getValue().replace("\n", "\\n") : "";
                    varsYaml.append("  ").append(entry.getKey()).append(": ").append(val).append("\n");
                }
            }
            Files.writeString(varsFile, varsYaml.toString());

            if (cliJarPath.isBlank()) {
                self.updateJobResult(jobId, "FAILED", -1, "workflow.cli.jar not configured");
                return;
            }

            List<String> cmd = List.of(
                "java", "-jar", cliJarPath,
                "run",
                "-w", yamlFile.toAbsolutePath().toString(),
                "-V", varsFile.toAbsolutePath().toString()
            );

            logger.info("Executing batch job " + jobId + ": " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String log = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.waitFor();

            self.updateJobResult(jobId, exitCode == 0 ? "DONE" : "FAILED", exitCode, log);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Batch job execution failed: " + jobId, e);
            self.updateJobResult(jobId, "FAILED", -1, e.getMessage());
        } finally {
            if (yamlFile != null) try { Files.deleteIfExists(yamlFile); } catch (Exception ignored) {}
            if (varsFile != null) try { Files.deleteIfExists(varsFile); } catch (Exception ignored) {}
        }
    }

    @Transactional
    public void updateJobResult(String jobId, String status, int exitCode, String log) {
        BatchJob job = BatchJob.findById(jobId);
        if (job != null) {
            job.status = status;
            job.exitCode = exitCode;
            job.log = log;
            job.finishedAt = Instant.now();
        }
    }

    @Transactional
    public boolean updateStatus(String id, String status, Integer exitCode, String log) {
        BatchJob job = BatchJob.findById(id);
        if (job == null) return false;
        job.status = status;
        if (exitCode != null) job.exitCode = exitCode;
        if (log != null) job.log = log;
        if ("RUNNING".equals(status) && job.startedAt == null) job.startedAt = Instant.now();
        if ("DONE".equals(status) || "FAILED".equals(status)) job.finishedAt = Instant.now();
        return true;
    }

    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        try { return objectMapper.writeValueAsString(map); } catch (Exception e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); } catch (Exception e) { return Map.of(); }
    }
}
