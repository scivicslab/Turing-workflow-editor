package com.scivicslab.workfloweditor.service;

import com.scivicslab.workfloweditor.rest.WorkflowResource.WorkflowEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * POJO actor that manages a FIFO queue of workflow execution requests.
 *
 * Serializes execution: only one workflow runs at a time. When a workflow
 * completes, the next item in the queue is dispatched automatically.
 *
 * All public methods are called via ActorRef.tell()/ask() and execute on
 * the actor's dedicated virtual thread, so no external synchronization is needed.
 */
public class WorkflowQueueActor {

    public record QueueItem(String id, String name, String yaml, int maxIterations, Level logLevel) {}

    private final ArrayDeque<QueueItem> queue = new ArrayDeque<>();
    private boolean busy = false;

    private final WorkflowRunner runner;
    private final Consumer<WorkflowEvent> emitter;

    // Injected after actor creation via setExecutionContext()
    private ActorRef<WorkflowQueueActor> self;
    private ExecutorService pool;

    public WorkflowQueueActor(WorkflowRunner runner, Consumer<WorkflowEvent> emitter) {
        this.runner = runner;
        this.emitter = emitter;
    }

    /** Called once immediately after the ActorRef is created. */
    public void setExecutionContext(ActorRef<WorkflowQueueActor> self, ExecutorService pool) {
        this.self = self;
        this.pool = pool;
    }

    /**
     * Adds a workflow to the queue. If the runner is idle, dispatches immediately.
     * Returns the queue-item id.
     */
    public String enqueue(String name, String yaml, int maxIterations, Level logLevel) {
        String id = UUID.randomUUID().toString();
        queue.addLast(new QueueItem(id, name, yaml, maxIterations, logLevel));
        if (!busy) {
            dispatchNext();
        } else {
            emitQueueChanged();
        }
        return id;
    }

    /**
     * Called by the pool thread (via self.tell) when a workflow finishes.
     * Dispatches the next queued item if available.
     */
    public void onWorkflowComplete() {
        busy = false;
        if (!queue.isEmpty()) {
            dispatchNext();
        } else {
            emitQueueChanged();
        }
    }

    /** Stops the currently running workflow. Does not clear the queue. */
    public void cancelCurrent() {
        runner.stop();
    }

    /** Removes a pending item from the queue by id. No-op if not found. */
    public void removeFromQueue(String id) {
        queue.removeIf(item -> item.id().equals(id));
        emitQueueChanged();
    }

    /** Returns a snapshot of pending items (does not include the running item). */
    public List<Map<String, String>> getQueueSnapshot() {
        var result = new ArrayList<Map<String, String>>();
        for (var item : queue) {
            var m = new LinkedHashMap<String, String>();
            m.put("id", item.id());
            m.put("name", item.name());
            result.add(m);
        }
        return result;
    }

    public boolean isBusy() {
        return busy;
    }

    // ---- private -------------------------------------------------------

    private void dispatchNext() {
        if (queue.isEmpty()) return;
        QueueItem item = queue.pollFirst();
        busy = true;
        emitQueueChanged();
        pool.execute(() -> {
            try {
                runner.runYaml(item.yaml(), item.maxIterations(), item.logLevel(), emitter);
            } finally {
                self.tell(q -> q.onWorkflowComplete());
            }
        });
    }

    private void emitQueueChanged() {
        var data = new LinkedHashMap<String, Object>();
        data.put("items", getQueueSnapshot());
        data.put("busy", busy);
        emitter.accept(new WorkflowEvent("queue-changed", null, null, null, null, data));
    }
}
