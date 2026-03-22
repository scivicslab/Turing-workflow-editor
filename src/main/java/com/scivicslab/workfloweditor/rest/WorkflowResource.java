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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

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
            String yaml = WorkflowRunner.toYamlStructured(request.name, null, request.steps);
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
            List<MatrixRow> rows = request.rows;
            workflowState.replaceAll(request.name, rows, maxIter);
            final List<MatrixRow> finalRows = rows;
            Thread.startVirtualThread(() -> {
                try {
                    runner.run(request.name, finalRows, maxIter, logLevel, this::emitSse);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Workflow failed", e);
                    emitSse(new WorkflowEvent("error", e.getMessage(), null, null));
                }
            });
        }

        return Map.of("status", "started");
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
        public List<MatrixRow> rows;
        public List<StepDto> steps;
        public Integer maxIterations;
        public String logLevel;
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
