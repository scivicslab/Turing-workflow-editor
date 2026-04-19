package com.scivicslab.workfloweditor.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.workfloweditor.service.WorkflowRunner;
import com.scivicslab.workfloweditor.service.WorkflowState;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.scivicslab.workfloweditor.service.WorkflowRunner.StepDto;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.Yaml;

/**
 * REST + SSE endpoint for workflow editing and execution.
 */
@Path("/api")
@ApplicationScoped
public class WorkflowResource {

    private static final Logger logger = Logger.getLogger(WorkflowResource.class.getName());

    @Inject
    WorkflowRunner runner;

    @Inject
    WorkflowState workflowState;

    @Inject
    Vertx vertx;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "workflow.params.base-dir", defaultValue = "/home/devteam/works")
    String paramsBaseDir;

    private final java.util.concurrent.CopyOnWriteArrayList<HttpServerResponse> sseConnections = new java.util.concurrent.CopyOnWriteArrayList<>();

    void registerSseRoute(@Observes Router router) {
        router.get("/api/events").handler(this::handleSseConnect);
    }

    private void handleSseConnect(RoutingContext rc) {
        var response = rc.response();
        response.setChunked(true);
        response.putHeader("Content-Type", "text/event-stream");
        response.putHeader("Cache-Control", "no-cache");
        response.putHeader("X-Accel-Buffering", "no");

        sseConnections.add(response);
        response.write("retry: 30000\n\n");

        vertx.setPeriodic(15_000, id -> {
            if (response.ended()) {
                vertx.cancelTimer(id);
                return;
            }
            response.write(": heartbeat\n\n");
        });

        response.closeHandler(v -> {
            sseConnections.remove(response);
        });

        logger.info("SSE connected (total: " + sseConnections.size() + ")");
    }

    /**
     * Returns the actor tree: all registered actors, their types, children, and available actions.
     */
    @GET
    @Path("/actors")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> actors() {
        return runner.getActorTree();
    }

    /**
     * Runs a workflow defined as a matrix of rows.
     */
    @POST
    @Path("/run")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> run(RunRequest request) {
        if (runner.isRunning()) {
            return Map.of("status", "error", "message", "Workflow already running");
        }

        int maxIter = request.maxIterations != null && request.maxIterations > 0 ? request.maxIterations : 100;
        Level logLevel = parseLogLevel(request.logLevel);

        if (request.steps != null) {
            // Use structured steps (supports delay/breakpoint)
            final String yaml = WorkflowRunner.applyParameters(
                WorkflowRunner.toYamlStructured(request.name, null, request.steps),
                request.parameters);
            List<MatrixRow> rows = WorkflowRunner.stepsToRows(request.steps);
            workflowState.replaceAll(request.name, rows, maxIter);
            Thread.startVirtualThread(() -> {
                try {
                    runner.runYaml(yaml, maxIter, logLevel, this::emitSse);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Workflow failed", e);
                    emitSse(new WorkflowEvent("error", e.getMessage(), null, null));
                }
            });
        } else {
            return Map.of("status", "error", "message", "No workflow steps provided");
        }

        return Map.of("status", "started");
    }

    /**
     * Returns parameter metadata (description, default) for the active workflow.
     * Used by the Run dialog to show helpful hints.
     */
    @GET
    @Path("/params/meta")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getParamsMeta() {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : workflowState.getParams().entrySet()) {
            var meta = new LinkedHashMap<String, String>();
            var pm = entry.getValue();
            meta.put("description", pm.description() != null ? pm.description() : "");
            meta.put("default", pm.defaultValue() != null ? pm.defaultValue() : "");
            result.put(entry.getKey(), meta);
        }
        return result;
    }

    /**
     * Lists overlay-conf.yaml files under the configured base directory.
     */
    @GET
    @Path("/params/files")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> listParamFiles() {
        var result = new ArrayList<String>();
        try (var stream = Files.walk(Paths.get(paramsBaseDir), 4)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                  .map(java.nio.file.Path::toString)
                  .sorted()
                  .forEach(result::add);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to list param files", e);
        }
        return result;
    }

    /**
     * Parses YAML parameter content sent from the browser and returns its vars section.
     */
    @POST
    @Path("/params/parse")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseParamContent(String content) {
        try {
            Map<String, Object> data = new Yaml().load(content);
            Object vars = data != null ? data.get("vars") : null;
            var result = new LinkedHashMap<String, Object>();
            result.put("vars", vars instanceof Map ? vars : Map.of());
            result.put("raw", content);
            return result;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse param content", e);
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Stops a running workflow.
     */
    @POST
    @Path("/stop")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> stop() {
        runner.stop();
        return Map.of("status", "stopped");
    }

    /**
     * Tells the browser to reload the workflow editor UI.
     */
    @POST
    @Path("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> refresh() {
        emitSse(new WorkflowEvent("state-changed", "Refresh requested", null, null));
        return Map.of("status", "ok");
    }

    private void emitSse(WorkflowEvent event) {
        logger.info("emitSse called: type=" + event.type() + " message=" + event.message());
        if (sseConnections.isEmpty()) {
            logger.warning("SSE event DROPPED (no connections): type=" + event.type());
            return;
        }
        vertx.runOnContext(v -> {
            try {
                String json = objectMapper.writeValueAsString(event);
                String data = "data: " + json + "\n\n";
                for (var resp : sseConnections) {
                    if (!resp.ended()) {
                        resp.write(data);
                    }
                }
                logger.info("SSE event BROADCAST to " + sseConnections.size() + " connections: type=" + event.type());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to write SSE event", e);
            }
        });
    }

    /**
     * Exposes the SSE emitter for use by other resource classes (e.g., WorkflowApiResource).
     */
    public Consumer<WorkflowEvent> getSseEmitter() {
        return this::emitSse;
    }

    /**
     * Sets the log level for the workflow logger.
     */
    @jakarta.ws.rs.PUT
    @Path("/log-level")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> setLogLevel(Map<String, String> body) {
        String levelStr = body != null ? body.get("level") : null;
        Level level = parseLogLevel(levelStr);
        java.util.logging.Logger.getLogger("workflow").setLevel(level);
        return Map.of("status", "ok", "level", level.getName());
    }

    private static Level parseLogLevel(String levelStr) {
        if (levelStr == null || levelStr.isEmpty()) return Level.INFO;
        try {
            return Level.parse(levelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Level.INFO;
        }
    }

    // DTOs

    @RegisterForReflection
    public static class RunRequest {
        public String name;
        public List<StepDto> steps;
        public Integer maxIterations;
        public String logLevel;
        public Map<String, String> parameters;
    }

    @RegisterForReflection
    public record MatrixRow(String from, String to, String actor, String method, String arguments) {}

    @RegisterForReflection
    public record WorkflowEvent(String type, String message, String state, String action,
                                String actorName, Map<String, Object> data) {
        /** Backward-compatible constructor for existing call sites. */
        public WorkflowEvent(String type, String message, String state, String action) {
            this(type, message, state, action, null, null);
        }
    }
}
