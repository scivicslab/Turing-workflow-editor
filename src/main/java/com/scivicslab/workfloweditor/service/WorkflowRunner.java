package com.scivicslab.workfloweditor.service;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;
import com.scivicslab.workfloweditor.rest.WorkflowResource.MatrixRow;
import com.scivicslab.workfloweditor.rest.WorkflowResource.WorkflowEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts a matrix of rows into POJO-actor YAML and executes the workflow.
 * A single ActorSystem is shared across the entire application.
 */
@ApplicationScoped
public class WorkflowRunner {

    private static final Logger logger = Logger.getLogger(WorkflowRunner.class.getName());

    private IIActorSystem system;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;

    @PostConstruct
    void init() {
        system = new IIActorSystem("workflow");
        system.addIIActor(new LogActor("log", system));
        system.addIIActor(new ShellActor("shell", system));
        system.addIIActor(new LoaderActor("loader", system));
    }

    public IIActorSystem getSystem() {
        return system;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void stop() {
        stopRequested = true;
    }

    /**
     * Returns actor tree info for the ActorSystem.
     * Each entry: { name, type, parent, children, actions }
     */
    public List<Map<String, Object>> getActorTree() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (IIActorRef<?> actor : system.getTopLevelActors()) {
            collectActorInfo(actor, result);
        }
        return result;
    }

    private void collectActorInfo(IIActorRef<?> actor, List<Map<String, Object>> result) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", actor.getName());
        info.put("type", actor.getClass().getSimpleName());
        info.put("parent", actor.getParentName());

        List<String> children = new ArrayList<>(actor.getNamesOfChildren());
        info.put("children", children);

        List<String> actions = discoverActions(actor);
        info.put("actions", actions);

        result.add(info);

        for (String childName : children) {
            if (system.hasIIActor(childName)) {
                collectActorInfo(system.getIIActor(childName), result);
            }
        }
    }

    private List<String> discoverActions(IIActorRef<?> actor) {
        List<String> actions = new ArrayList<>();
        for (Method method : actor.getClass().getMethods()) {
            Action action = method.getAnnotation(Action.class);
            if (action != null) {
                actions.add(action.value());
            }
        }
        // Built-in JSON state actions available on all IIActorRef
        actions.add("putJson");
        actions.add("getJson");
        actions.add("hasJson");
        actions.add("clearJson");
        actions.add("printJson");
        return actions;
    }

    /**
     * Runs a workflow from matrix rows.
     */
    public void run(String name, List<MatrixRow> rows, int maxIterations, Consumer<WorkflowEvent> emitter) {
        if (!running.compareAndSet(false, true)) {
            emitter.accept(new WorkflowEvent("error", "Workflow already running", null, null));
            return;
        }
        stopRequested = false;

        // Wire output listeners so actor output streams to SSE
        Consumer<String> outputForwarder = msg ->
                emitter.accept(new WorkflowEvent("output", msg, null, null));
        setOutputListeners(outputForwarder);

        try {
            String yaml = toYaml(name != null ? name : "workflow", rows);
            logger.info("Generated YAML:\n" + yaml);
            emitter.accept(new WorkflowEvent("info", "Workflow started: " + (name != null ? name : "workflow"), null, null));

            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("workflow")
                    .team(system)
                    .build();

            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

            int iteration = 0;
            while (iteration < maxIterations && !stopRequested) {
                ActionResult result = interpreter.execCode();
                String currentState = interpreter.getCurrentState();

                if (result == null || !result.isSuccess()) {
                    if ("end".equals(currentState)) {
                        emitter.accept(new WorkflowEvent("completed", "Workflow completed", currentState, null));
                    } else {
                        String msg = result != null ? result.getResult() : "No matching transition from state: " + currentState;
                        emitter.accept(new WorkflowEvent("error", msg, currentState, null));
                    }
                    break;
                }

                emitter.accept(new WorkflowEvent("step", result.getResult(), currentState, null));
                iteration++;

                if ("end".equals(interpreter.getCurrentState())) {
                    emitter.accept(new WorkflowEvent("completed", "Workflow completed", "end", null));
                    break;
                }
            }

            if (iteration >= maxIterations) {
                emitter.accept(new WorkflowEvent("error", "Max iterations reached (" + maxIterations + ")", null, null));
            }
            if (stopRequested) {
                emitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", null, null));
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Workflow execution failed", e);
            emitter.accept(new WorkflowEvent("error", "Execution failed: " + e.getMessage(), null, null));
        } finally {
            setOutputListeners(null);
            running.set(false);
        }
    }

    /**
     * Runs a workflow from raw YAML string.
     */
    public void runYaml(String yaml, int maxIterations, Consumer<WorkflowEvent> emitter) {
        if (!running.compareAndSet(false, true)) {
            emitter.accept(new WorkflowEvent("error", "Workflow already running", null, null));
            return;
        }
        stopRequested = false;

        Consumer<String> outputForwarder = msg ->
                emitter.accept(new WorkflowEvent("output", msg, null, null));
        setOutputListeners(outputForwarder);

        try {
            logger.info("Running YAML directly:\n" + yaml);
            emitter.accept(new WorkflowEvent("info", "Workflow started (YAML)", null, null));

            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("workflow")
                    .team(system)
                    .build();

            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

            int iteration = 0;
            while (iteration < maxIterations && !stopRequested) {
                ActionResult result = interpreter.execCode();
                String currentState = interpreter.getCurrentState();

                if (result == null || !result.isSuccess()) {
                    if ("end".equals(currentState)) {
                        emitter.accept(new WorkflowEvent("completed", "Workflow completed", currentState, null));
                    } else {
                        String msg = result != null ? result.getResult() : "No matching transition from state: " + currentState;
                        emitter.accept(new WorkflowEvent("error", msg, currentState, null));
                    }
                    break;
                }

                emitter.accept(new WorkflowEvent("step", result.getResult(), currentState, null));
                iteration++;

                if ("end".equals(interpreter.getCurrentState())) {
                    emitter.accept(new WorkflowEvent("completed", "Workflow completed", "end", null));
                    break;
                }
            }

            if (iteration >= maxIterations) {
                emitter.accept(new WorkflowEvent("error", "Max iterations reached (" + maxIterations + ")", null, null));
            }
            if (stopRequested) {
                emitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", null, null));
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Workflow execution failed", e);
            emitter.accept(new WorkflowEvent("error", "Execution failed: " + e.getMessage(), null, null));
        } finally {
            setOutputListeners(null);
            running.set(false);
        }
    }

    private void setOutputListeners(Consumer<String> listener) {
        for (IIActorRef<?> actor : system.getTopLevelActors()) {
            if (actor instanceof ShellActor shell) {
                shell.setOutputListener(listener);
            } else if (actor instanceof LogActor log) {
                log.setOutputListener(listener);
            }
        }
    }

    /**
     * Converts matrix rows to POJO-actor YAML format.
     */
    public static String toYaml(String name, List<MatrixRow> rows) {
        var sb = new StringBuilder();
        sb.append("name: ").append(yamlEscape(name)).append("\n");
        sb.append("steps:\n");

        boolean inTransition = false;

        for (var row : rows) {
            boolean isNewTransition = row.from() != null && !row.from().isEmpty()
                    && row.to() != null && !row.to().isEmpty();

            if (isNewTransition) {
                sb.append("  - states: [\"").append(escapeYamlString(row.from()))
                  .append("\", \"").append(escapeYamlString(row.to())).append("\"]\n");
                sb.append("    actions:\n");
                inTransition = true;
            }

            if (inTransition) {
                appendAction(sb, row);
            }
        }

        return sb.toString();
    }

    private static void appendAction(StringBuilder sb, MatrixRow row) {
        sb.append("      - actor: ").append(yamlEscape(row.actor())).append("\n");
        sb.append("        method: ").append(yamlEscape(row.method())).append("\n");
        if (row.arguments() != null && !row.arguments().isEmpty()) {
            String args = row.arguments();
            if (args.startsWith("[") || args.startsWith("{")) {
                sb.append("        arguments: ").append(args).append("\n");
            } else {
                sb.append("        arguments: \"").append(escapeYamlString(args)).append("\"\n");
            }
        }
    }

    private static String yamlEscape(String s) {
        if (s == null) return "\"\"";
        if (s.contains(":") || s.contains("#") || s.contains("\"") || s.contains("'")
                || s.startsWith(" ") || s.endsWith(" ")) {
            return "\"" + escapeYamlString(s) + "\"";
        }
        return s;
    }

    private static String escapeYamlString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Parses POJO-actor YAML back into a workflow name and matrix rows.
     */
    @SuppressWarnings("unchecked")
    public static ParsedWorkflow fromYaml(String yaml) {
        Yaml snakeYaml = new Yaml();
        Map<String, Object> doc = snakeYaml.load(yaml);

        String name = doc.containsKey("name") ? String.valueOf(doc.get("name")) : "workflow";
        List<MatrixRow> rows = new ArrayList<>();

        List<Map<String, Object>> steps = (List<Map<String, Object>>) doc.get("steps");
        if (steps == null) return new ParsedWorkflow(name, rows);

        for (Map<String, Object> step : steps) {
            List<String> states = ((List<?>) step.get("states")).stream()
                    .map(String::valueOf).toList();
            String from = states.size() > 0 ? states.get(0) : "";
            String to = states.size() > 1 ? states.get(1) : "";

            List<Map<String, Object>> actions = (List<Map<String, Object>>) step.get("actions");
            if (actions == null) continue;

            boolean first = true;
            for (Map<String, Object> action : actions) {
                String actor = String.valueOf(action.getOrDefault("actor", ""));
                String method = String.valueOf(action.getOrDefault("method", ""));
                String args = action.containsKey("arguments")
                        ? String.valueOf(action.get("arguments")) : "";

                if (first) {
                    rows.add(new MatrixRow(from, to, actor, method, args));
                    first = false;
                } else {
                    rows.add(new MatrixRow("", "", actor, method, args));
                }
            }
        }

        return new ParsedWorkflow(name, rows);
    }

    public record ParsedWorkflow(String name, List<MatrixRow> rows) {}
}
