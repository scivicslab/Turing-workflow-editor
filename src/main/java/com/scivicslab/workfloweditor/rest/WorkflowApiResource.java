package com.scivicslab.workfloweditor.rest;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.workfloweditor.rest.WorkflowResource.MatrixRow;
import com.scivicslab.workfloweditor.rest.WorkflowResource.WorkflowEvent;
import com.scivicslab.workfloweditor.service.WorkflowRunner;
import com.scivicslab.workfloweditor.service.WorkflowState;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * REST API for external control of the workflow editor.
 * Enables programmatic workflow manipulation from coder-agent or other tools.
 */
@Path("/api")
public class WorkflowApiResource {

    private static final Logger logger = Logger.getLogger(WorkflowApiResource.class.getName());

    @Inject
    WorkflowState state;

    @Inject
    WorkflowRunner runner;

    @Inject
    WorkflowResource workflowResource;

    // --- Workflow CRUD ---

    @GET
    @Path("/workflow")
    @Produces(MediaType.APPLICATION_JSON)
    public WorkflowDto getWorkflow() {
        return new WorkflowDto(state.getName(), state.getRows(), state.getMaxIterations());
    }

    @PUT
    @Path("/workflow")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> putWorkflow(WorkflowDto dto) {
        state.replaceAll(dto.name, dto.rows, dto.maxIterations != null ? dto.maxIterations : 100);
        return Map.of("status", "ok", "rowCount", state.size());
    }

    @POST
    @Path("/workflow/steps")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addStep(MatrixRow row, @QueryParam("index") Integer index) {
        try {
            int idx = state.addStep(row, index);
            return Response.ok(Map.of("status", "ok", "index", idx)).build();
        } catch (IndexOutOfBoundsException e) {
            return Response.status(400).entity(Map.of("status", "error", "message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/workflow/steps/{index}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateStep(@PathParam("index") int index, MatrixRow row) {
        try {
            state.updateStep(index, row);
            return Response.ok(Map.of("status", "ok")).build();
        } catch (IndexOutOfBoundsException e) {
            return Response.status(404).entity(Map.of("status", "error", "message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/workflow/steps/{index}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteStep(@PathParam("index") int index) {
        try {
            MatrixRow removed = state.deleteStep(index);
            return Response.ok(Map.of("status", "ok", "removed", removed)).build();
        } catch (IndexOutOfBoundsException e) {
            return Response.status(404).entity(Map.of("status", "error", "message", e.getMessage())).build();
        }
    }

    // --- YAML ---

    @GET
    @Path("/yaml/export")
    @Produces("text/plain")
    public String exportYaml() {
        return WorkflowRunner.toYaml(state.getName(), state.getRows());
    }

    @POST
    @Path("/yaml/import")
    @Consumes("text/plain")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> importYaml(String yaml) {
        WorkflowRunner.ParsedWorkflow parsed = WorkflowRunner.fromYaml(yaml);
        state.replaceAll(parsed.name(), parsed.rows(), state.getMaxIterations());
        return Map.of("status", "ok", "name", parsed.name(), "rowCount", parsed.rows().size());
    }

    // --- Direct YAML execution ---

    @POST
    @Path("/run/yaml")
    @Consumes("text/plain")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> runYaml(String yaml, @QueryParam("maxIterations") Integer maxIterations) {
        if (runner.isRunning()) {
            return Map.of("status", "error", "message", "Workflow already running");
        }

        WorkflowRunner.ParsedWorkflow parsed = WorkflowRunner.fromYaml(yaml);
        int maxIter = maxIterations != null && maxIterations > 0 ? maxIterations : state.getMaxIterations();
        state.replaceAll(parsed.name(), parsed.rows(), maxIter);

        var emitter = workflowResource.getSseEmitter();
        Thread.startVirtualThread(() -> runner.runYaml(yaml, maxIter, emitter));
        return Map.of("status", "started", "name", parsed.name());
    }

    // --- Status ---

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        return Map.of(
                "running", runner.isRunning(),
                "workflowName", state.getName(),
                "maxIterations", state.getMaxIterations(),
                "stepCount", state.size()
        );
    }

    // --- Transitions / sub-actions ---

    @GET
    @Path("/workflow/transitions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> getTransitions() {
        return state.getTransitions();
    }

    @POST
    @Path("/workflow/transitions/{tIndex}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addAction(@PathParam("tIndex") int tIndex, MatrixRow action,
                              @QueryParam("index") Integer aIndex) {
        try {
            int idx = state.addAction(tIndex, action, aIndex);
            return Response.ok(Map.of("status", "ok", "actionIndex", idx)).build();
        } catch (IndexOutOfBoundsException e) {
            return Response.status(404).entity(Map.of("status", "error", "message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/workflow/transitions/{tIndex}/actions/{aIndex}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateAction(@PathParam("tIndex") int tIndex, @PathParam("aIndex") int aIndex,
                                 MatrixRow action) {
        try {
            state.updateAction(tIndex, aIndex, action);
            return Response.ok(Map.of("status", "ok")).build();
        } catch (IndexOutOfBoundsException e) {
            return Response.status(404).entity(Map.of("status", "error", "message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/workflow/transitions/{tIndex}/actions/{aIndex}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAction(@PathParam("tIndex") int tIndex, @PathParam("aIndex") int aIndex) {
        try {
            MatrixRow removed = state.deleteAction(tIndex, aIndex);
            return Response.ok(Map.of("status", "ok", "removed", removed)).build();
        } catch (IndexOutOfBoundsException e) {
            return Response.status(404).entity(Map.of("status", "error", "message", e.getMessage())).build();
        }
    }

    // --- Loader shortcuts ---

    @POST
    @Path("/loader/load-jar")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> loadJar(Map<String, String> body) {
        String path = body.get("path");
        if (path == null || path.isBlank()) {
            return Map.of("status", "error", "message", "path is required");
        }
        try {
            var loader = runner.getSystem().getIIActor("loader");
            ActionResult result = loader.callByActionName("loadJar", path);
            return Map.of("status", result.isSuccess() ? "ok" : "error", "result", result.getResult());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @POST
    @Path("/loader/create-child")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> createChild(Map<String, String> body) {
        String name = body.get("name");
        String className = body.get("className");
        if (name == null || className == null) {
            return Map.of("status", "error", "message", "name and className are required");
        }
        try {
            // createChild expects: parentName, actorName, className
            String args = "[\"loader\", \"" + name + "\", \"" + className + "\"]";
            var loader = runner.getSystem().getIIActor("loader");
            ActionResult result = loader.callByActionName("createChild", args);
            return Map.of("status", result.isSuccess() ? "ok" : "error", "result", result.getResult());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // DTOs

    public static class WorkflowDto {
        public String name;
        public List<MatrixRow> rows;
        public Integer maxIterations;

        public WorkflowDto() {}

        public WorkflowDto(String name, List<MatrixRow> rows, int maxIterations) {
            this.name = name;
            this.rows = rows;
            this.maxIterations = maxIterations;
        }
    }
}
