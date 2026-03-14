package com.scivicslab.workfloweditor.mcp;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.workfloweditor.rest.WorkflowResource;
import com.scivicslab.workfloweditor.service.WorkflowRunner;
import com.scivicslab.workfloweditor.service.WorkflowState;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MCP tools for the Turing Workflow Editor.
 *
 * <p>Allows external MCP clients (e.g., coder-agent) to manage and execute
 * workflows programmatically.</p>
 */
public class WorkflowTools {

    @Inject
    WorkflowState state;

    @Inject
    WorkflowRunner runner;

    @Tool(description = "Run a YAML workflow and return the execution result. "
            + "This is a synchronous call that waits for completion.")
    String runWorkflow(
            @ToolArg(description = "The YAML workflow definition to execute") String yaml,
            @ToolArg(description = "Maximum iterations (default: 100)") int maxIterations
    ) {
        if (runner.isRunning()) {
            return "Error: A workflow is already running.";
        }

        int maxIter = maxIterations > 0 ? maxIterations : 100;
        List<String> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread.startVirtualThread(() -> {
            try {
                runner.runYaml(yaml, maxIter, null, event -> {
                    events.add("[" + event.type() + "] " + event.message());
                });
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(5, TimeUnit.MINUTES)) {
                return "Error: Workflow timed out. Events so far:\n" + String.join("\n", events);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted.";
        }

        return String.join("\n", events);
    }

    @Tool(description = "Get the current workflow status (running, name, step count)")
    String getStatus() {
        return String.format("running=%s, paused=%s, name=%s, steps=%d, maxIterations=%d",
                runner.isRunning(),
                runner.isPaused(),
                state.getName(),
                state.size(),
                state.getMaxIterations());
    }

    @Tool(description = "Stop the currently running workflow")
    String stopWorkflow() {
        if (!runner.isRunning()) {
            return "No workflow is currently running.";
        }
        runner.stop();
        return "Stop requested.";
    }

    @Tool(description = "Resume a workflow paused at a breakpoint")
    String resumeWorkflow() {
        if (!runner.isPaused()) {
            return "Workflow is not paused.";
        }
        runner.resume();
        return "Workflow resumed.";
    }

    @Tool(description = "Export the current workflow as YAML")
    String exportYaml() {
        return WorkflowRunner.toYamlStructured(
                state.getName(), state.getDescription(),
                WorkflowRunner.rowsToSteps(state.getRows()));
    }

    @Tool(description = "Import a YAML workflow into the editor")
    String importYaml(
            @ToolArg(description = "The YAML workflow to import") String yaml
    ) {
        WorkflowRunner.ParsedWorkflow parsed = WorkflowRunner.fromYaml(yaml);
        var rows = WorkflowRunner.stepsToRows(parsed.steps());
        state.replaceAll(parsed.name(), rows, state.getMaxIterations());
        if (parsed.description() != null) {
            state.setDescription(parsed.description());
        }
        return "Imported: " + parsed.name() + " (" + parsed.steps().size() + " steps)";
    }

    @Tool(description = "List all registered actors and their available actions")
    String listActors() {
        List<Map<String, Object>> tree = runner.getActorTree();
        StringBuilder sb = new StringBuilder();
        for (var actor : tree) {
            sb.append(actor.get("name")).append(" (").append(actor.get("type")).append("): ");
            sb.append(actor.get("actions")).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Invoke an actor action directly by name")
    String invokeActor(
            @ToolArg(description = "The actor name") String actorName,
            @ToolArg(description = "The action name to invoke") String actionName,
            @ToolArg(description = "Arguments for the action") String args
    ) {
        try {
            var actor = runner.getSystem().getIIActor(actorName);
            if (actor == null) {
                return "Error: Actor not found: " + actorName;
            }
            ActionResult result = actor.callByActionName(actionName, args != null ? args : "");
            return (result.isSuccess() ? "OK" : "FAIL") + ": " + result.getResult();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List available workflow tabs")
    String listTabs() {
        return String.join(", ", state.listTabs());
    }
}
