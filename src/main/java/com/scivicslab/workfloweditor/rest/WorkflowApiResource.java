package com.scivicslab.workfloweditor.rest;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.workfloweditor.rest.WorkflowResource.MatrixRow;
import com.scivicslab.workfloweditor.rest.WorkflowResource.WorkflowEvent;
import com.scivicslab.workfloweditor.service.WorkflowRunner;
import com.scivicslab.workfloweditor.service.WorkflowRunner.StepDto;
import com.scivicslab.workfloweditor.service.WorkflowState;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        var dto = new WorkflowDto();
        dto.name = state.getName();
        dto.description = state.getDescription();
        dto.steps = WorkflowRunner.rowsToSteps(state.getRows());
        dto.maxIterations = state.getMaxIterations();
        return dto;
    }

    @PUT
    @Path("/workflow")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> putWorkflow(WorkflowDto dto) {
        if (dto.steps == null || dto.steps.isEmpty()) {
            return Map.of("status", "error", "message", "No workflow steps provided");
        }
        List<MatrixRow> rows = WorkflowRunner.stepsToRows(dto.steps);
        state.replaceAll(dto.name, rows, dto.maxIterations != null ? dto.maxIterations : 100);
        if (dto.description != null) {
            state.setDescription(dto.description);
        }
        notifyStateChanged("Workflow updated via API");
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
        return WorkflowRunner.toYamlStructured(
                state.getName(), state.getDescription(),
                WorkflowRunner.rowsToSteps(state.getRows()),
                state.getParams());
    }

    @POST
    @Path("/yaml/import")
    @Consumes("text/plain")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> importYaml(String yaml) {
        try {
            WorkflowRunner.ParsedWorkflow parsed = WorkflowRunner.fromYaml(yaml);
            List<MatrixRow> rows = WorkflowRunner.stepsToRows(parsed.steps());
            state.replaceAll(parsed.name(), rows, state.getMaxIterations());
            if (parsed.description() != null) {
                state.setDescription(parsed.description());
            }
            state.setParams(parsed.params());
            notifyStateChanged("YAML imported: " + parsed.name());
            return Map.of("status", "ok", "name", parsed.name(), "stepCount", parsed.steps().size());
        } catch (Exception e) {
            logger.log(java.util.logging.Level.WARNING, "YAML import failed", e);
            return Map.of("status", "error", "message", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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
        List<MatrixRow> rows = WorkflowRunner.stepsToRows(parsed.steps());
        int maxIter = maxIterations != null && maxIterations > 0 ? maxIterations : state.getMaxIterations();
        state.replaceAll(parsed.name(), rows, maxIter);

        notifyStateChanged("YAML workflow started: " + parsed.name());
        var emitter = workflowResource.getSseEmitter();
        Thread.startVirtualThread(() -> runner.runYaml(yaml, maxIter, null, emitter));
        return Map.of("status", "started", "name", parsed.name());
    }

    // --- Status ---

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        return Map.of(
                "running", runner.isRunning(),
                "paused", runner.isPaused(),
                "workflowName", state.getName(),
                "maxIterations", state.getMaxIterations(),
                "stepCount", state.size()
        );
    }

    @POST
    @Path("/resume")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> resume() {
        if (!runner.isPaused()) {
            return Map.of("status", "error", "message", "Workflow is not paused");
        }
        runner.resume();
        return Map.of("status", "ok", "message", "Workflow resumed");
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

    // --- Tabs (multiple workflows) ---

    @GET
    @Path("/tabs")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> listTabs() {
        return Map.of("tabs", state.listTabs(), "active", state.getActiveTab() != null ? state.getActiveTab() : "");
    }

    @POST
    @Path("/tabs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> createTab(Map<String, String> body) {
        String name = body != null ? body.get("name") : null;
        String created = state.createTab(name != null ? name : "workflow");
        return Map.of("status", "ok", "name", created);
    }

    @DELETE
    @Path("/tabs/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteTab(@PathParam("name") String name) {
        if (state.deleteTab(name)) {
            return Response.ok(Map.of("status", "ok")).build();
        }
        return Response.status(404).entity(Map.of("status", "error", "message", "Tab not found: " + name)).build();
    }

    @PUT
    @Path("/tabs/{name}/activate")
    @Produces(MediaType.APPLICATION_JSON)
    public Response activateTab(@PathParam("name") String name) {
        if (state.activateTab(name)) {
            return Response.ok(Map.of("status", "ok",
                    "name", state.getName(),
                    "steps", WorkflowRunner.rowsToSteps(state.getRows()),
                    "maxIterations", state.getMaxIterations())).build();
        }
        return Response.status(404).entity(Map.of("status", "error", "message", "Tab not found: " + name)).build();
    }

    @PUT
    @Path("/tabs/{name}/rename")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response renameTab(@PathParam("name") String name, Map<String, String> body) {
        String newName = body != null ? body.get("name") : null;
        String result = state.renameTab(name, newName);
        if (result != null) {
            return Response.ok(Map.of("status", "ok", "name", result)).build();
        }
        return Response.status(404).entity(Map.of("status", "error", "message", "Tab not found: " + name)).build();
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

    // --- Actor Tree ---

    @GET
    @Path("/actors/tree")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> getActorTree() {
        return runner.getActorTree();
    }

    @GET
    @Path("/actors/{name}/data")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getActorData(@PathParam("name") String name) {
        var system = runner.getSystem();
        if (!system.hasIIActor(name)) {
            return Response.status(404).entity(Map.of("error", "Actor not found: " + name)).build();
        }
        var actor = system.getIIActor(name);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("name", actor.getName());
        result.put("type", actor.getClass().getSimpleName());
        result.put("parent", actor.getParentName());
        result.put("children", new java.util.ArrayList<>(actor.getNamesOfChildren()));
        try {
            result.put("jsonState", actor.json().toString());
        } catch (Exception e) {
            result.put("jsonState", "{}");
        }
        return Response.ok(result).build();
    }

    // --- Plugins & Workflows Browser ---

    @GET
    @Path("/plugins/available")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> availablePlugins() {
        List<Map<String, Object>> plugins = new ArrayList<>();
        var repoBase = java.nio.file.Path.of(System.getProperty("user.home"), ".m2", "repository", "com", "scivicslab");
        if (!java.nio.file.Files.isDirectory(repoBase)) return plugins;

        try (var dirs = java.nio.file.Files.newDirectoryStream(repoBase, "actor-*")) {
            for (var artifactDir : dirs) {
                String artifactId = artifactDir.getFileName().toString();
                List<String> versions = new ArrayList<>();
                String latestVersion = null;
                String latestJar = null;

                try (var versionDirs = java.nio.file.Files.newDirectoryStream(artifactDir, java.nio.file.Files::isDirectory)) {
                    for (var versionDir : versionDirs) {
                        String ver = versionDir.getFileName().toString();
                        versions.add(ver);
                        // Find the main JAR (not -sources, -javadoc, -cli)
                        try (var jars = java.nio.file.Files.newDirectoryStream(versionDir, "*.jar")) {
                            for (var jar : jars) {
                                String jarName = jar.getFileName().toString();
                                if (!jarName.contains("-sources") && !jarName.contains("-javadoc")
                                        && !jarName.contains("-cli")) {
                                    latestVersion = ver;
                                    latestJar = jar.toAbsolutePath().toString();
                                }
                            }
                        }
                    }
                }

                if (!versions.isEmpty()) {
                    Map<String, Object> plugin = new LinkedHashMap<>();
                    plugin.put("artifactId", artifactId);
                    plugin.put("groupId", "com.scivicslab");
                    plugin.put("versions", versions);
                    plugin.put("latestVersion", latestVersion);
                    plugin.put("latestJar", latestJar);
                    plugin.put("coordinate", "com.scivicslab:" + artifactId + ":" + latestVersion);
                    plugins.add(plugin);
                }
            }
        } catch (IOException e) {
            logger.warning("Failed to scan plugins: " + e.getMessage());
        }
        return plugins;
    }

    @GET
    @Path("/workflows/available")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, String>> availableWorkflows() {
        List<Map<String, String>> workflows = new ArrayList<>();
        var worksDir = java.nio.file.Path.of(System.getProperty("user.home"), "works");
        if (!java.nio.file.Files.isDirectory(worksDir)) return workflows;

        // Scan ~/works/*/src/main/resources/{workflows,code}/*.yaml
        try (var projects = java.nio.file.Files.newDirectoryStream(worksDir, java.nio.file.Files::isDirectory)) {
            for (var project : projects) {
                String projectName = project.getFileName().toString();
                scanWorkflowDir(project.resolve("src/main/resources/workflows"), projectName, workflows);
                scanWorkflowDir(project.resolve("src/main/resources/code"), projectName, workflows);
            }
        } catch (IOException e) {
            logger.warning("Failed to scan workflows: " + e.getMessage());
        }

        // Also scan ~/works/testcluster-iac/*.yaml (direct YAML files)
        var testclusterDir = worksDir.resolve("testcluster-iac");
        if (java.nio.file.Files.isDirectory(testclusterDir)) {
            try (var yamls = java.nio.file.Files.newDirectoryStream(testclusterDir, "*.yaml")) {
                for (var yaml : yamls) {
                    addWorkflowEntry(yaml, "testcluster-iac", workflows);
                }
            } catch (IOException e) {
                // ignore
            }
        }

        return workflows;
    }

    private void scanWorkflowDir(java.nio.file.Path dir, String projectName, List<Map<String, String>> workflows) {
        if (!java.nio.file.Files.isDirectory(dir)) return;
        try (var yamls = java.nio.file.Files.newDirectoryStream(dir, "*.yaml")) {
            for (var yaml : yamls) {
                addWorkflowEntry(yaml, projectName, workflows);
            }
        } catch (IOException e) {
            // ignore
        }
    }

    private void addWorkflowEntry(java.nio.file.Path yaml, String projectName, List<Map<String, String>> workflows) {
        try {
            String content = java.nio.file.Files.readString(yaml);
            // Quick check: valid workflow YAML should contain "steps:" or "states:"
            if (!content.contains("steps:") && !content.contains("states:")) return;

            // Extract name from YAML
            String name = yaml.getFileName().toString().replace(".yaml", "");
            for (String line : content.lines().toList()) {
                if (line.startsWith("name:")) {
                    name = line.substring(5).trim().replace("\"", "").replace("'", "");
                    break;
                }
            }

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("file", yaml.getFileName().toString());
            entry.put("project", projectName);
            entry.put("path", yaml.toAbsolutePath().toString());
            workflows.add(entry);
        } catch (IOException e) {
            // skip unreadable files
        }
    }

    @POST
    @Path("/workflows/load")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> loadWorkflow(Map<String, String> body) {
        String path = body.get("path");
        if (path == null || path.isBlank()) {
            return Map.of("status", "error", "message", "path is required");
        }
        try {
            String yaml = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            WorkflowRunner.ParsedWorkflow parsed = WorkflowRunner.fromYaml(yaml);
            List<MatrixRow> rows = WorkflowRunner.stepsToRows(parsed.steps());
            state.replaceAll(parsed.name(), rows, state.getMaxIterations());
            if (parsed.description() != null) {
                state.setDescription(parsed.description());
            }
            state.setParams(parsed.params());
            state.setFilePath(path);
            notifyStateChanged("Workflow loaded: " + parsed.name());
            return Map.of("status", "ok", "name", parsed.name(), "stepCount", parsed.steps().size(), "yaml", yaml);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @POST
    @Path("/workflows/save")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> saveWorkflow() {
        String filePath = state.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return Map.of("status", "error", "message", "No file path known — load a workflow file first.");
        }
        try {
            String yaml = WorkflowRunner.toYamlStructured(
                    state.getName(), state.getDescription(),
                    WorkflowRunner.rowsToSteps(state.getRows()));
            rotateBackups(filePath, 5);
            java.nio.file.Files.writeString(java.nio.file.Path.of(filePath), yaml);
            notifyStateChanged("Workflow saved: " + filePath);
            return Map.of("status", "ok", "path", filePath);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    private void rotateBackups(String filePath, int maxBackups) throws java.io.IOException {
        java.nio.file.Path src = java.nio.file.Path.of(filePath);
        if (!java.nio.file.Files.exists(src)) return;
        // Drop the oldest slot if it exists
        java.nio.file.Path oldest = java.nio.file.Path.of(filePath + ".~" + maxBackups + "~");
        java.nio.file.Files.deleteIfExists(oldest);
        // Shift backups: .~(N-1)~ → .~N~, ..., .~1~ → .~2~
        for (int i = maxBackups - 1; i >= 1; i--) {
            java.nio.file.Path from = java.nio.file.Path.of(filePath + ".~" + i + "~");
            java.nio.file.Path to   = java.nio.file.Path.of(filePath + ".~" + (i + 1) + "~");
            if (java.nio.file.Files.exists(from)) {
                java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // Current file → .~1~
        java.nio.file.Files.copy(src, java.nio.file.Path.of(filePath + ".~1~"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // --- Helpers ---

    private void notifyStateChanged(String message) {
        var emitter = workflowResource.getSseEmitter();
        emitter.accept(new WorkflowEvent("state-changed", message, null, null));
    }

    // DTOs

    public static class WorkflowDto {
        public String name;
        public String description;
        public List<StepDto> steps;
        public Integer maxIterations;

        public WorkflowDto() {}
    }
}
