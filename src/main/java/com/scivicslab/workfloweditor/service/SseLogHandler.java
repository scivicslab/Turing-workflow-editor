package com.scivicslab.workfloweditor.service;

import com.scivicslab.workfloweditor.rest.WorkflowResource.WorkflowEvent;

import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Bridges java.util.logging records to the SSE WorkflowEvent stream.
 *
 * Installed on the "workflow" logger (Interpreter traces) and the
 * "com.scivicslab" logger (POJO-actor / Turing-workflow library classes)
 * for the duration of a single runYaml() call, then removed in the finally block.
 *
 * A ThreadLocal re-entrancy guard prevents infinite loops: WorkflowResource
 * logs at INFO inside emitSse(), which would otherwise cycle back through
 * this handler indefinitely.
 */
public class SseLogHandler extends Handler {

    private static final ThreadLocal<Boolean> publishing = ThreadLocal.withInitial(() -> false);

    private final Consumer<WorkflowEvent> emitter;

    public SseLogHandler(Consumer<WorkflowEvent> emitter) {
        this.emitter = emitter;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getMessage() == null) return;
        if (publishing.get()) return;
        publishing.set(true);
        try {
            String type = levelToType(record.getLevel());
            emitter.accept(new WorkflowEvent(type, record.getMessage(), null, null));
        } finally {
            publishing.set(false);
        }
    }

    @Override public void flush() {}
    @Override public void close() {}

    private static String levelToType(Level level) {
        if (level == null)              return "fine";
        int v = level.intValue();
        if (v >= Level.SEVERE.intValue())  return "error";
        if (v >= Level.WARNING.intValue()) return "warning";
        if (v >= Level.INFO.intValue())    return "info";
        if (v >= Level.FINE.intValue())    return "fine";
        if (v >= Level.FINER.intValue())   return "finer";
        return "finest";
    }
}
