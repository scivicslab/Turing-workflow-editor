package com.scivicslab.workfloweditor.rest;

import com.scivicslab.workfloweditor.batch.BatchJob;
import com.scivicslab.workfloweditor.batch.BatchJobService;
import com.scivicslab.workfloweditor.service.WorkflowRunner;
import com.scivicslab.workfloweditor.service.WorkflowState;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/batch")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class BatchJobResource {

    @Inject
    BatchJobService service;

    @Inject
    WorkflowState state;

    @POST
    @Path("/jobs")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreateJobRequest req) {
        String yaml = WorkflowRunner.toYamlStructured(
            state.getName(), state.getDescription(), state.getSteps());
        BatchJob job = service.create(state.getName(), yaml,
            req != null ? req.parameters : null);
        return Response.ok(toDto(job)).build();
    }

    @GET
    @Path("/jobs")
    public List<Map<String, Object>> list() {
        return service.listRecent().stream()
            .map(this::toSummaryDto)
            .collect(Collectors.toList());
    }

    @GET
    @Path("/jobs/{id}")
    public Response getById(@PathParam("id") String id) {
        BatchJob job = service.findById(id);
        if (job == null) return Response.status(404)
            .entity(Map.of("error", "not found")).build();
        return Response.ok(toDto(job)).build();
    }

    @POST
    @Path("/jobs/{id}/run")
    public Response run(@PathParam("id") String id) {
        String result = service.startJob(id);
        if (result.startsWith("error:")) {
            return Response.status(400)
                .entity(Map.of("error", result.substring(7).trim())).build();
        }
        return Response.ok(Map.of("jobId", id, "status", "RUNNING")).build();
    }

    @DELETE
    @Path("/jobs/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!service.delete(id)) {
            return Response.status(400)
                .entity(Map.of("error", "Cannot delete (not found or running)")).build();
        }
        return Response.ok(Map.of("status", "deleted")).build();
    }

    @POST
    @Path("/jobs/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateStatus(@PathParam("id") String id, UpdateStatusRequest req) {
        if (req == null || req.status == null) {
            return Response.status(400).entity(Map.of("error", "status required")).build();
        }
        boolean ok = service.updateStatus(id, req.status, req.exitCode, req.log);
        if (!ok) return Response.status(404).entity(Map.of("error", "not found")).build();
        return Response.ok(Map.of("jobId", id, "status", req.status)).build();
    }

    private Map<String, Object> toDto(BatchJob job) {
        var m = new LinkedHashMap<String, Object>();
        m.put("jobId", job.id);
        m.put("name", job.name);
        m.put("status", job.status);
        m.put("yaml", job.yaml);
        m.put("parameters", job.parameters);
        m.put("createdAt", job.createdAt != null ? job.createdAt.toString() : null);
        m.put("startedAt", job.startedAt != null ? job.startedAt.toString() : null);
        m.put("finishedAt", job.finishedAt != null ? job.finishedAt.toString() : null);
        m.put("exitCode", job.exitCode);
        m.put("log", job.log);
        return m;
    }

    private Map<String, Object> toSummaryDto(BatchJob job) {
        var m = new LinkedHashMap<String, Object>();
        m.put("jobId", job.id);
        m.put("name", job.name);
        m.put("status", job.status);
        m.put("createdAt", job.createdAt != null ? job.createdAt.toString() : null);
        return m;
    }

    @RegisterForReflection
    public static class CreateJobRequest {
        public Map<String, String> parameters;
    }

    @RegisterForReflection
    public static class UpdateStatusRequest {
        public String status;
        public Integer exitCode;
        public String log;
    }
}
