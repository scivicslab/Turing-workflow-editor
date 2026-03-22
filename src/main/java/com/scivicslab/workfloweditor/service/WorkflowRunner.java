package com.scivicslab.workfloweditor.service;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.workfloweditor.rest.WorkflowResource.MatrixRow;
import com.scivicslab.workfloweditor.rest.WorkflowResource.WorkflowEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
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

    private static final String DEFAULT_INTERPRETER_NAME = "interpreter";

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
        system.addIIActor(new MilestoneActor("milestone", system));

        // Create default interpreter actor - the editor UI is bound to this interpreter
        Interpreter defaultInterp = new Interpreter.Builder()
                .loggerName("workflow")
                .team(system)
                .build();
        system.addIIActor(new InterpreterIIAR(DEFAULT_INTERPRETER_NAME, defaultInterp, system));
        currentInterpreter = defaultInterp;
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

        // Detect if this actor wraps an Interpreter
        boolean isInterpreter = actor.getClass().getSimpleName().equals("InterpreterIIAR");
        info.put("isInterpreter", isInterpreter);

        if (isInterpreter) {
            Interpreter interp = getInterpreterObject(actor);
            if (interp != null) {
                info.put("currentState", interp.getCurrentState());
                info.put("workflowFile", getInterpreterWorkflowFile(actor));
            }
        }

        // Milestone actor: include latest message and history
        if (actor instanceof MilestoneActor ms) {
            String latest = ms.getLatestMessage();
            if (latest != null) info.put("milestoneMessage", latest);
            info.put("milestoneHistory", ms.getHistory());
        }

        // Determine status — for interpreters, derive from currentState
        if (isInterpreter) {
            String st = (String) info.get("currentState");
            if ("end".equals(st)) info.put("status", "COMPLETED");
            else if (running.get()) info.put("status", "RUNNING");
            else info.put("status", "IDLE");
        } else {
            info.put("status", "IDLE");
        }

        List<String> children = new ArrayList<>(actor.getNamesOfChildren());
        info.put("children", children);

        List<Map<String, String>> actions = discoverActions(actor);
        info.put("actions", actions);

        result.add(info);

        for (String childName : children) {
            if (system.hasIIActor(childName)) {
                collectActorInfo(system.getIIActor(childName), result);
            }
        }
    }

    /**
     * Extracts the wrapped Interpreter from an InterpreterIIAR actor via reflection.
     */
    private Interpreter getInterpreterObject(IIActorRef<?> actor) {
        try {
            var field = actor.getClass().getSuperclass().getSuperclass().getDeclaredField("object");
            field.setAccessible(true);
            Object obj = field.get(actor);
            if (obj instanceof Interpreter interp) {
                return interp;
            }
        } catch (Exception e) {
            logger.fine("Could not get interpreter for " + actor.getName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Extracts the current state from an InterpreterIIAR actor via reflection.
     */
    private String getInterpreterState(IIActorRef<?> actor) {
        Interpreter interp = getInterpreterObject(actor);
        return interp != null ? interp.getCurrentState() : null;
    }

    /**
     * Extracts the workflow file name from an InterpreterIIAR actor.
     * Currently returns the actor name as a best-effort identifier since
     * Interpreter does not track the loaded file name.
     * Sub-workflow child interpreters are named "subwf-{baseName}-{timestamp}-{random}".
     */
    private String getInterpreterWorkflowFile(IIActorRef<?> actor) {
        String name = actor.getName();
        if (name != null && name.startsWith("subwf-")) {
            // Extract base name from "subwf-{baseName}-{timestamp}-{random}"
            String rest = name.substring(6); // remove "subwf-"
            int dashIdx = rest.lastIndexOf('-');
            if (dashIdx > 0) {
                String beforeRandom = rest.substring(0, dashIdx);
                int dashIdx2 = beforeRandom.lastIndexOf('-');
                if (dashIdx2 > 0) {
                    return beforeRandom.substring(0, dashIdx2) + ".yaml";
                }
            }
            return rest + ".yaml";
        }
        return null;
    }

    /**
     * Determines the status of an actor: RUNNING, COMPLETED, IDLE, or ERROR.
     */
    private String determineActorStatus(IIActorRef<?> actor, boolean isInterpreter) {
        if (!isInterpreter) return "IDLE";
        String state = getInterpreterState(actor);
        if ("end".equals(state)) return "COMPLETED";
        if (running.get()) return "RUNNING";
        return "IDLE";
    }

    /**
     * Emits an actor-tree snapshot as an SSE event.
     * Called after each step execution so the frontend can update the tree in real-time.
     */
    private void emitActorTree(Consumer<WorkflowEvent> emitter) {
        try {
            List<Map<String, Object>> tree = getActorTree();
            emitter.accept(new WorkflowEvent("actor-tree", null, null, null, null, Map.of("actors", tree)));
        } catch (Exception e) {
            logger.fine("Could not emit actor tree: " + e.getMessage());
        }
    }

    private void resetMilestone() {
        for (IIActorRef<?> actor : system.getTopLevelActors()) {
            if (actor instanceof MilestoneActor ms) {
                ms.reset();
            }
        }
    }

    private List<Map<String, String>> discoverActions(IIActorRef<?> actor) {
        List<Map<String, String>> actions = new ArrayList<>();
        String javadocBaseUrl = resolveJavadocBaseUrl(actor.getClass());
        String classPath = actor.getClass().getName().replace('.', '/');

        for (Method method : actor.getClass().getMethods()) {
            Action action = method.getAnnotation(Action.class);
            if (action != null) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", action.value());
                if (javadocBaseUrl != null) {
                    // Build javadoc URL: baseUrl/package/ClassName.html#methodName(params)
                    String params = buildJavadocParams(method);
                    entry.put("javadocUrl", javadocBaseUrl + "/" + classPath + ".html#" + method.getName() + "(" + params + ")");
                }
                actions.add(entry);
            }
        }
        // Built-in JSON state actions available on all IIActorRef
        for (String name : List.of("putJson", "getJson", "hasJson", "clearJson", "printJson")) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", name);
            actions.add(entry);
        }
        return actions;
    }

    private String resolveJavadocBaseUrl(Class<?> clazz) {
        try {
            var url = clazz.getClassLoader().getResource("META-INF/javadoc.properties");
            if (url == null) return null;
            var props = new java.util.Properties();
            try (var in = url.openStream()) {
                props.load(in);
            }
            return props.getProperty("javadoc.baseUrl");
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not load javadoc.properties for " + clazz.getName(), e);
            return null;
        }
    }

    private String buildJavadocParams(Method method) {
        var sb = new StringBuilder();
        for (int i = 0; i < method.getParameterTypes().length; i++) {
            if (i > 0) sb.append(",");
            sb.append(method.getParameterTypes()[i].getSimpleName());
        }
        return sb.toString();
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
        resetMilestone();

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
            logger.fine("Generated YAML:\n" + yaml);
            effectiveEmitter.accept(new WorkflowEvent("info", "Workflow started: " + workflowName, null, null));

            // Reuse the default interpreter actor
            Interpreter interpreter = currentInterpreter;
            interpreter.reset();

            interpreter.setBreakpointListener((transition, state) ->
                    effectiveEmitter.accept(new WorkflowEvent("paused",
                            "Breakpoint at state: " + state, state, null)));

            interpreter.setActionFailureListener((transition, state, result) -> {
                String failMsg = "Action failed at state '" + state + "' transition "
                        + transition.getStates() + ": " + result.getResult();
                logger.finest(failMsg);
                effectiveEmitter.accept(new WorkflowEvent("finest", failMsg, state, null));
            });

            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            emitActorTree(effectiveEmitter);

            int iteration = 0;
            while (iteration < maxIterations && !stopRequested) {
                ActionResult result = interpreter.execCode();
                String currentState = interpreter.getCurrentState();
                emitActorTree(effectiveEmitter);

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

                effectiveEmitter.accept(new WorkflowEvent("fine", result.getResult(), currentState, null));
                iteration++;

                if ("end".equals(interpreter.getCurrentState())) {
                    effectiveEmitter.accept(new WorkflowEvent("completed", "Workflow completed", "end", null));
                    break;
                }
            }

            if (iteration >= maxIterations) {
                effectiveEmitter.accept(new WorkflowEvent("warning", "Max iterations reached (" + maxIterations + ")", null, null));
            }
            if (stopRequested && !interpreter.isStopRequested()) {
                effectiveEmitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", null, null));
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Workflow execution failed", e);
            effectiveEmitter.accept(new WorkflowEvent("error", "Execution failed: " + e.getMessage(), null, null));
        } finally {
            // Do not null out currentInterpreter - it is the default interpreter actor, reused across runs
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
        resetMilestone();

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
            logger.fine("Running YAML directly:\n" + yaml);
            effectiveEmitter.accept(new WorkflowEvent("info", "Workflow started (YAML)", null, null));

            // Reuse the default interpreter actor
            Interpreter interpreter = currentInterpreter;
            interpreter.reset();

            interpreter.setBreakpointListener((transition, state) ->
                    effectiveEmitter.accept(new WorkflowEvent("paused",
                            "Breakpoint at state: " + state, state, null)));

            interpreter.setActionFailureListener((transition, state, result) -> {
                String failMsg = "Action failed at state '" + state + "' transition "
                        + transition.getStates() + ": " + result.getResult();
                logger.finest(failMsg);
                effectiveEmitter.accept(new WorkflowEvent("finest", failMsg, state, null));
            });

            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            emitActorTree(effectiveEmitter);

            // Debug: log initial state and loaded transitions to both server log and SSE
            String initMsg = "Initial state: '" + interpreter.getCurrentState() + "'";
            logger.info(initMsg);
            effectiveEmitter.accept(new WorkflowEvent("info", initMsg, null, null));
            if (interpreter.hasCodeLoaded()) {
                var debugTransitions = interpreter.getCode().getTransitions();
                effectiveEmitter.accept(new WorkflowEvent("fine", "Transition count: " + debugTransitions.size(), null, null));
                for (int t = 0; t < debugTransitions.size(); t++) {
                    var tr = debugTransitions.get(t);
                    String trMsg = "  [" + t + "] states=" + tr.getStates() + " label=" + tr.getLabel();
                    logger.fine(trMsg);
                    effectiveEmitter.accept(new WorkflowEvent("fine", trMsg, null, null));
                }
            } else {
                effectiveEmitter.accept(new WorkflowEvent("error", "No code loaded!", null, null));
            }

            int iteration = 0;
            while (iteration < maxIterations && !stopRequested) {
                ActionResult result = interpreter.execCode();
                String currentState = interpreter.getCurrentState();
                emitActorTree(effectiveEmitter);
                // Debug: log each step result
                String stepDebug = "execCode result: success=" + (result != null && result.isSuccess())
                        + " state=" + currentState
                        + " msg=" + (result != null ? result.getResult() : "null");
                logger.fine(stepDebug);
                effectiveEmitter.accept(new WorkflowEvent("fine", stepDebug, currentState, null));

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

                effectiveEmitter.accept(new WorkflowEvent("fine", result.getResult(), currentState, null));
                iteration++;

                if ("end".equals(interpreter.getCurrentState())) {
                    effectiveEmitter.accept(new WorkflowEvent("completed", "Workflow completed", "end", null));
                    break;
                }
            }

            if (iteration >= maxIterations) {
                effectiveEmitter.accept(new WorkflowEvent("warning", "Max iterations reached (" + maxIterations + ")", null, null));
            }
            if (stopRequested) {
                effectiveEmitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", null, null));
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Workflow execution failed", e);
            effectiveEmitter.accept(new WorkflowEvent("error", "Execution failed: " + e.getMessage(), null, null));
        } finally {
            // Do not null out currentInterpreter - it is the default interpreter actor, reused across runs
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
            } else if (actor instanceof MilestoneActor ms) {
                ms.setOutputListener(listener);
            }
            // For dynamically loaded actors, try to call setOutputListener via reflection
            trySetOutputListenerReflective(actor, listener);
        }
    }

    private void trySetOutputListenerReflective(IIActorRef<?> actor, Consumer<String> listener) {
        try {
            var method = actor.getClass().getMethod("setOutputListener", Consumer.class);
            // Skip built-in actors already handled above
            if (actor instanceof ShellActor || actor instanceof LogActor) return;
            method.invoke(actor, listener);
        } catch (NoSuchMethodException e) {
            // Actor doesn't support output listener — that's fine
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to set output listener on " + actor.getName(), e);
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
            appendYamlArguments(sb, args, "        ");
        }
    }

    /**
     * Appends arguments to YAML using block scalar (|) for multiline values
     * and double-quoted strings for single-line values.
     */
    private static void appendYamlArguments(StringBuilder sb, String args, String indent) {
        if (args.startsWith("[") || args.startsWith("{")) {
            sb.append(indent).append("arguments: ").append(args).append("\n");
        } else if (args.contains("\n")) {
            // Multiline: use YAML block scalar to preserve newlines
            sb.append(indent).append("arguments: |\n");
            for (String line : args.split("\n", -1)) {
                if (line.isEmpty()) {
                    sb.append("\n");
                } else {
                    sb.append(indent).append("  ").append(line).append("\n");
                }
            }
        } else {
            sb.append(indent).append("arguments: \"").append(escapeYamlString(args)).append("\"\n");
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
            String from;
            String to;
            if (step.containsKey("states")) {
                List<String> states = ((List<?>) step.get("states")).stream()
                        .map(String::valueOf).toList();
                from = states.size() > 0 ? states.get(0) : "";
                to = states.size() > 1 ? states.get(1) : "";
            } else {
                from = String.valueOf(step.getOrDefault("from", ""));
                to = String.valueOf(step.getOrDefault("to", ""));
            }
            String label = step.containsKey("label") ? String.valueOf(step.get("label")) : null;
            String note = step.containsKey("note") ? String.valueOf(step.get("note")) : null;

            List<ActionDto> actionDtos = new ArrayList<>();
            List<Map<String, Object>> actions = (List<Map<String, Object>>) step.get("actions");
            if (actions != null) {
                for (Map<String, Object> action : actions) {
                    String actor = String.valueOf(action.getOrDefault("actor", ""));
                    String method = String.valueOf(action.getOrDefault("method", ""));
                    String args = null;
                    if (action.containsKey("arguments")) {
                        Object rawArgs = action.get("arguments");
                        if (rawArgs instanceof List) {
                            // Convert List to JSON array string
                            org.json.JSONArray jsonArr = new org.json.JSONArray((List<?>) rawArgs);
                            args = jsonArr.toString();
                        } else {
                            args = String.valueOf(rawArgs);
                        }
                    }
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
                    appendYamlArguments(sb, action.arguments(), "      ");
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

    @RegisterForReflection
    public record ParsedWorkflow(String name, String description, List<StepDto> steps) {}
    @RegisterForReflection
    public record StepDto(String from, String to, String label, String note, Long delay, Boolean breakpoint, List<ActionDto> actions) {}
    @RegisterForReflection
    public record ActionDto(String actor, String method, String arguments) {}
}
