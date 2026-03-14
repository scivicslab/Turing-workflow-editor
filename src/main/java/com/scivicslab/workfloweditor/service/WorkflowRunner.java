package com.scivicslab.workfloweditor.service;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
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
    private volatile Interpreter currentInterpreter = null;

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
        Interpreter interp = currentInterpreter;
        if (interp != null) {
            interp.requestStop();
            interp.resume(); // Unblock if paused at breakpoint
        }
    }

    public void resume() {
        Interpreter interp = currentInterpreter;
        if (interp != null && interp.isPaused()) {
            interp.resume();
        }
    }

    public boolean isPaused() {
        Interpreter interp = currentInterpreter;
        return interp != null && interp.isPaused();
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
    public void run(String name, List<MatrixRow> rows, int maxIterations, Level logLevel, Consumer<WorkflowEvent> emitter) {
        if (!running.compareAndSet(false, true)) {
            emitter.accept(new WorkflowEvent("error", "Workflow already running", null, null));
            return;
        }
        stopRequested = false;

        // Set log level for workflow logger
        var workflowLogger = java.util.logging.Logger.getLogger("workflow");
        var prevLevel = workflowLogger.getLevel();
        if (logLevel != null) {
            workflowLogger.setLevel(logLevel);
        }

        // When OFF, filter out step/info events; pass output/completed/error/stopped
        final Consumer<WorkflowEvent> effectiveEmitter;
        if (logLevel == Level.OFF) {
            effectiveEmitter = event -> {
                String type = event.type();
                if (!"step".equals(type) && !"info".equals(type)) {
                    emitter.accept(event);
                }
            };
        } else {
            effectiveEmitter = emitter;
        }

        // Wire output listeners so actor output streams to SSE
        Consumer<String> outputForwarder = msg ->
                effectiveEmitter.accept(new WorkflowEvent("output", msg, null, null));
        setOutputListeners(outputForwarder);

        // Intercept stdout/stderr during execution
        var origOut = System.out;
        var origErr = System.err;
        System.setOut(new java.io.PrintStream(new OutputInterceptor(origOut, line ->
                effectiveEmitter.accept(new WorkflowEvent("output", line, null, null))), true));
        System.setErr(new java.io.PrintStream(new OutputInterceptor(origErr, line ->
                effectiveEmitter.accept(new WorkflowEvent("output", "[stderr] " + line, null, null))), true));

        try {
            String workflowName = name != null ? name : "workflow";
            String yaml = toYaml(workflowName, rows);
            logger.info("Generated YAML:\n" + yaml);
            effectiveEmitter.accept(new WorkflowEvent("info", "Workflow started: " + workflowName, null, null));

            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("workflow")
                    .team(system)
                    .build();
            currentInterpreter = interpreter;

            interpreter.setBreakpointListener((transition, state) ->
                    effectiveEmitter.accept(new WorkflowEvent("paused",
                            "Breakpoint at state: " + state, state, null)));

            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

            int iteration = 0;
            while (iteration < maxIterations && !stopRequested) {
                ActionResult result = interpreter.execCode();
                String currentState = interpreter.getCurrentState();

                if (result == null || !result.isSuccess()) {
                    if ("end".equals(currentState)) {
                        effectiveEmitter.accept(new WorkflowEvent("completed", "Workflow completed", currentState, null));
                    } else if (interpreter.isStopRequested()) {
                        effectiveEmitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", currentState, null));
                    } else {
                        String msg = result != null ? result.getResult() : "No matching transition from state: " + currentState;
                        effectiveEmitter.accept(new WorkflowEvent("error", msg, currentState, null));
                    }
                    break;
                }

                effectiveEmitter.accept(new WorkflowEvent("step", result.getResult(), currentState, null));
                iteration++;

                if ("end".equals(interpreter.getCurrentState())) {
                    effectiveEmitter.accept(new WorkflowEvent("completed", "Workflow completed", "end", null));
                    break;
                }
            }

            if (iteration >= maxIterations) {
                effectiveEmitter.accept(new WorkflowEvent("error", "Max iterations reached (" + maxIterations + ")", null, null));
            }
            if (stopRequested && !interpreter.isStopRequested()) {
                effectiveEmitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", null, null));
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Workflow execution failed", e);
            effectiveEmitter.accept(new WorkflowEvent("error", "Execution failed: " + e.getMessage(), null, null));
        } finally {
            currentInterpreter = null;
            System.setOut(origOut);
            System.setErr(origErr);
            setOutputListeners(null);
            workflowLogger.setLevel(prevLevel);
            running.set(false);
        }
    }

    /**
     * OutputStream that intercepts line-by-line output and forwards to a callback,
     * while also writing to the original stream.
     */
    private static class OutputInterceptor extends java.io.OutputStream {
        private final java.io.OutputStream original;
        private final Consumer<String> lineCallback;
        private final StringBuilder buffer = new StringBuilder();

        OutputInterceptor(java.io.OutputStream original, Consumer<String> lineCallback) {
            this.original = original;
            this.lineCallback = lineCallback;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            original.write(b);
            if (b == '\n') {
                String line = buffer.toString();
                buffer.setLength(0);
                if (!line.isEmpty()) {
                    lineCallback.accept(line);
                }
            } else {
                buffer.append((char) b);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) throws java.io.IOException {
            original.write(buf, off, len);
            for (int i = off; i < off + len; i++) {
                if (buf[i] == '\n') {
                    String line = buffer.toString();
                    buffer.setLength(0);
                    if (!line.isEmpty()) {
                        lineCallback.accept(line);
                    }
                } else {
                    buffer.append((char) buf[i]);
                }
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            original.flush();
            if (buffer.length() > 0) {
                lineCallback.accept(buffer.toString());
                buffer.setLength(0);
            }
        }
    }

    /**
     * Runs a workflow from raw YAML string.
     */
    public void runYaml(String yaml, int maxIterations, Level logLevel, Consumer<WorkflowEvent> emitter) {
        if (!running.compareAndSet(false, true)) {
            emitter.accept(new WorkflowEvent("error", "Workflow already running", null, null));
            return;
        }
        stopRequested = false;

        // Set log level for workflow logger
        var workflowLogger = java.util.logging.Logger.getLogger("workflow");
        var prevLevel = workflowLogger.getLevel();
        if (logLevel != null) {
            workflowLogger.setLevel(logLevel);
        }

        // When OFF, filter out step/info events; pass output/completed/error/stopped/paused
        final Consumer<WorkflowEvent> effectiveEmitter;
        if (logLevel == Level.OFF) {
            effectiveEmitter = event -> {
                String type = event.type();
                if (!"step".equals(type) && !"info".equals(type)) {
                    emitter.accept(event);
                }
            };
        } else {
            effectiveEmitter = emitter;
        }

        Consumer<String> outputForwarder = msg ->
                effectiveEmitter.accept(new WorkflowEvent("output", msg, null, null));
        setOutputListeners(outputForwarder);

        var origOut = System.out;
        var origErr = System.err;
        System.setOut(new java.io.PrintStream(new OutputInterceptor(origOut, line ->
                effectiveEmitter.accept(new WorkflowEvent("output", line, null, null))), true));
        System.setErr(new java.io.PrintStream(new OutputInterceptor(origErr, line ->
                effectiveEmitter.accept(new WorkflowEvent("output", "[stderr] " + line, null, null))), true));

        try {
            logger.info("Running YAML directly:\n" + yaml);
            effectiveEmitter.accept(new WorkflowEvent("info", "Workflow started (YAML)", null, null));

            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("workflow")
                    .team(system)
                    .build();
            currentInterpreter = interpreter;

            interpreter.setBreakpointListener((transition, state) ->
                    effectiveEmitter.accept(new WorkflowEvent("paused",
                            "Breakpoint at state: " + state, state, null)));

            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

            int iteration = 0;
            while (iteration < maxIterations && !stopRequested) {
                ActionResult result = interpreter.execCode();
                String currentState = interpreter.getCurrentState();

                if (result == null || !result.isSuccess()) {
                    if ("end".equals(currentState)) {
                        effectiveEmitter.accept(new WorkflowEvent("completed", "Workflow completed", currentState, null));
                    } else if (interpreter.isStopRequested()) {
                        effectiveEmitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", currentState, null));
                    } else {
                        String msg = result != null ? result.getResult() : "No matching transition from state: " + currentState;
                        effectiveEmitter.accept(new WorkflowEvent("error", msg, currentState, null));
                    }
                    break;
                }

                effectiveEmitter.accept(new WorkflowEvent("step", result.getResult(), currentState, null));
                iteration++;

                if ("end".equals(interpreter.getCurrentState())) {
                    effectiveEmitter.accept(new WorkflowEvent("completed", "Workflow completed", "end", null));
                    break;
                }
            }

            if (iteration >= maxIterations) {
                effectiveEmitter.accept(new WorkflowEvent("error", "Max iterations reached (" + maxIterations + ")", null, null));
            }
            if (stopRequested) {
                effectiveEmitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", null, null));
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Workflow execution failed", e);
            effectiveEmitter.accept(new WorkflowEvent("error", "Execution failed: " + e.getMessage(), null, null));
        } finally {
            currentInterpreter = null;
            System.setOut(origOut);
            System.setErr(origErr);
            setOutputListeners(null);
            workflowLogger.setLevel(prevLevel);
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
     * Parses POJO-actor YAML back into structured steps with description/label/note.
     */
    @SuppressWarnings("unchecked")
    public static ParsedWorkflow fromYaml(String yaml) {
        Yaml snakeYaml = new Yaml();
        Map<String, Object> doc = snakeYaml.load(yaml);

        String name = doc.containsKey("name") ? String.valueOf(doc.get("name")) : "workflow";
        String description = doc.containsKey("description") ? String.valueOf(doc.get("description")) : null;
        List<StepDto> stepDtos = new ArrayList<>();

        List<Map<String, Object>> steps = (List<Map<String, Object>>) doc.get("steps");
        if (steps == null) return new ParsedWorkflow(name, description, stepDtos);

        for (Map<String, Object> step : steps) {
            List<String> states = ((List<?>) step.get("states")).stream()
                    .map(String::valueOf).toList();
            String from = states.size() > 0 ? states.get(0) : "";
            String to = states.size() > 1 ? states.get(1) : "";
            String label = step.containsKey("label") ? String.valueOf(step.get("label")) : null;
            String note = step.containsKey("note") ? String.valueOf(step.get("note")) : null;

            List<ActionDto> actionDtos = new ArrayList<>();
            List<Map<String, Object>> actions = (List<Map<String, Object>>) step.get("actions");
            if (actions != null) {
                for (Map<String, Object> action : actions) {
                    String actor = String.valueOf(action.getOrDefault("actor", ""));
                    String method = String.valueOf(action.getOrDefault("method", ""));
                    String args = action.containsKey("arguments")
                            ? String.valueOf(action.get("arguments")) : null;
                    actionDtos.add(new ActionDto(actor, method, args));
                }
            }

            Long stepDelay = step.containsKey("delay") ? ((Number) step.get("delay")).longValue() : null;
            Boolean stepBreakpoint = step.containsKey("breakpoint") ? (Boolean) step.get("breakpoint") : null;
            stepDtos.add(new StepDto(from, to, label, note, stepDelay, stepBreakpoint, actionDtos));
        }

        return new ParsedWorkflow(name, description, stepDtos);
    }

    /**
     * Converts structured steps to YAML including description/label/note.
     */
    public static String toYamlStructured(String name, String description, List<StepDto> steps) {
        var sb = new StringBuilder();
        sb.append("name: ").append(yamlEscape(name)).append("\n");
        if (description != null && !description.isEmpty()) {
            sb.append("description: ").append(yamlEscape(description)).append("\n");
        }
        sb.append("steps:\n");

        for (var step : steps) {
            sb.append("- states: [\"").append(escapeYamlString(step.from()))
              .append("\", \"").append(escapeYamlString(step.to())).append("\"]\n");
            if (step.label() != null && !step.label().isEmpty()) {
                sb.append("  label: ").append(yamlEscape(step.label())).append("\n");
            }
            if (step.note() != null && !step.note().isEmpty()) {
                sb.append("  note: ").append(yamlEscape(step.note())).append("\n");
            }
            if (step.delay() != null && step.delay() > 0) {
                sb.append("  delay: ").append(step.delay()).append("\n");
            }
            if (step.breakpoint() != null && step.breakpoint()) {
                sb.append("  breakpoint: true\n");
            }
            sb.append("  actions:\n");
            for (var action : step.actions()) {
                sb.append("    - actor: ").append(yamlEscape(action.actor())).append("\n");
                sb.append("      method: ").append(yamlEscape(action.method())).append("\n");
                if (action.arguments() != null && !action.arguments().isEmpty()) {
                    String args = action.arguments();
                    if (args.startsWith("[") || args.startsWith("{")) {
                        sb.append("      arguments: ").append(args).append("\n");
                    } else {
                        sb.append("      arguments: \"").append(escapeYamlString(args)).append("\"\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Converts structured steps to flat MatrixRow list for backward compatibility.
     */
    public static List<MatrixRow> stepsToRows(List<StepDto> steps) {
        List<MatrixRow> rows = new ArrayList<>();
        for (var step : steps) {
            boolean first = true;
            for (var action : step.actions()) {
                if (first) {
                    rows.add(new MatrixRow(step.from(), step.to(), action.actor(), action.method(), action.arguments()));
                    first = false;
                } else {
                    rows.add(new MatrixRow("", "", action.actor(), action.method(), action.arguments()));
                }
            }
        }
        return rows;
    }

    /**
     * Converts flat MatrixRow list to structured steps (loses label/note).
     */
    public static List<StepDto> rowsToSteps(List<MatrixRow> rows) {
        List<StepDto> steps = new ArrayList<>();
        String curFrom = null, curTo = null;
        List<ActionDto> curActions = null;

        for (var row : rows) {
            boolean isNew = row.from() != null && !row.from().isEmpty()
                    && row.to() != null && !row.to().isEmpty();
            if (isNew) {
                if (curFrom != null && curActions != null) {
                    steps.add(new StepDto(curFrom, curTo, null, null, null, null, curActions));
                }
                curFrom = row.from();
                curTo = row.to();
                curActions = new ArrayList<>();
            }
            if (curActions != null) {
                curActions.add(new ActionDto(row.actor(), row.method(), row.arguments()));
            }
        }
        if (curFrom != null && curActions != null) {
            steps.add(new StepDto(curFrom, curTo, null, null, null, null, curActions));
        }
        return steps;
    }

    public record ParsedWorkflow(String name, String description, List<StepDto> steps) {}
    public record StepDto(String from, String to, String label, String note, Long delay, Boolean breakpoint, List<ActionDto> actions) {}
    public record ActionDto(String actor, String method, String arguments) {}
}
