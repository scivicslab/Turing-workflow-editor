package com.scivicslab.workfloweditor.service;

import com.scivicslab.workfloweditor.rest.WorkflowResource.MatrixRow;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side holder for the current workflow definition.
 * Enables external API clients to inspect and manipulate the workflow.
 */
@ApplicationScoped
public class WorkflowState {

    private volatile String name = "workflow";
    private volatile int maxIterations = 100;
    private final CopyOnWriteArrayList<MatrixRow> rows = new CopyOnWriteArrayList<>();

    public String getName() {
        return name;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public List<MatrixRow> getRows() {
        return Collections.unmodifiableList(new ArrayList<>(rows));
    }

    public int size() {
        return rows.size();
    }

    public synchronized void replaceAll(String name, List<MatrixRow> newRows, int maxIterations) {
        this.name = name != null ? name : "workflow";
        this.maxIterations = maxIterations > 0 ? maxIterations : 100;
        this.rows.clear();
        if (newRows != null) {
            this.rows.addAll(newRows);
        }
    }

    public synchronized int addStep(MatrixRow row, Integer index) {
        if (index != null) {
            if (index < 0 || index > rows.size()) {
                throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + rows.size() + "]");
            }
            rows.add(index, row);
            return index;
        }
        rows.add(row);
        return rows.size() - 1;
    }

    public synchronized void updateStep(int index, MatrixRow row) {
        if (index < 0 || index >= rows.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + rows.size() + ")");
        }
        rows.set(index, row);
    }

    public synchronized MatrixRow deleteStep(int index) {
        if (index < 0 || index >= rows.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + rows.size() + ")");
        }
        return rows.remove(index);
    }

    // --- Transition / sub-action helpers ---

    /**
     * Returns structured view: list of transitions, each with from, to, and list of actions.
     */
    public synchronized List<Map<String, Object>> getTransitions() {
        List<Map<String, Object>> transitions = new ArrayList<>();
        Map<String, Object> current = null;
        List<Map<String, String>> currentActions = null;

        for (var row : rows) {
            boolean isTransition = row.from() != null && !row.from().isEmpty()
                    && row.to() != null && !row.to().isEmpty();

            if (isTransition) {
                current = new LinkedHashMap<>();
                current.put("from", row.from());
                current.put("to", row.to());
                currentActions = new ArrayList<>();
                current.put("actions", currentActions);
                transitions.add(current);
            }

            if (currentActions != null) {
                var action = new LinkedHashMap<String, String>();
                action.put("actor", row.actor());
                action.put("method", row.method());
                action.put("arguments", row.arguments());
                currentActions.add(action);
            }
        }
        return transitions;
    }

    /**
     * Returns the flat row index of the first row in transition tIndex.
     * Throws IndexOutOfBoundsException if tIndex is invalid.
     */
    private int transitionStartIndex(int tIndex) {
        int t = -1;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.from() != null && !row.from().isEmpty()
                    && row.to() != null && !row.to().isEmpty()) {
                t++;
                if (t == tIndex) return i;
            }
        }
        throw new IndexOutOfBoundsException("Transition " + tIndex + " not found");
    }

    /**
     * Returns the flat row index corresponding to transition tIndex, action aIndex.
     */
    private int actionFlatIndex(int tIndex, int aIndex) {
        int start = transitionStartIndex(tIndex);
        // Count actions within this transition (start row + subsequent sub-action rows)
        int actionCount = 0;
        for (int i = start; i < rows.size(); i++) {
            if (i > start) {
                var row = rows.get(i);
                if (row.from() != null && !row.from().isEmpty()
                        && row.to() != null && !row.to().isEmpty()) {
                    break; // next transition
                }
            }
            if (actionCount == aIndex) return i;
            actionCount++;
        }
        throw new IndexOutOfBoundsException(
                "Action " + aIndex + " not found in transition " + tIndex + " (has " + actionCount + " actions)");
    }

    /**
     * Returns the flat index just after the last action of transition tIndex (insert point for appending).
     */
    private int transitionEndIndex(int tIndex) {
        int start = transitionStartIndex(tIndex);
        int end = start + 1;
        for (int i = start + 1; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.from() != null && !row.from().isEmpty()
                    && row.to() != null && !row.to().isEmpty()) {
                break;
            }
            end = i + 1;
        }
        return end;
    }

    /**
     * Adds a sub-action to transition tIndex. If aIndex is null, appends at end.
     */
    public synchronized int addAction(int tIndex, MatrixRow action, Integer aIndex) {
        if (aIndex != null) {
            int flatIdx = (aIndex == 0)
                    ? transitionStartIndex(tIndex) + 1  // insert after transition row itself? No...
                    : actionFlatIndex(tIndex, aIndex - 1) + 1;
            // For aIndex=0 we want to insert right after the transition's first action (index 0 is the transition row)
            // Actually: action index 0 = the transition row itself. Sub-actions start at index 1.
            // But semantically the user wants to insert at a position within the actions list.
            // Let's use: aIndex is within the actions list (0 = first action = transition row).
            // Inserting a sub-action at aIndex means inserting *before* that position.
            // But sub-actions always have empty from/to, so inserting at 0 would put it before the transition row — that's wrong.
            // Better: only allow appending sub-actions (aIndex >= 1), or inserting after the transition row.
            // Simplify: just compute flat index for insert.
            int insertAt;
            if (aIndex <= 0) {
                // Insert right after the transition row (before first sub-action)
                insertAt = transitionStartIndex(tIndex) + 1;
            } else {
                insertAt = actionFlatIndex(tIndex, aIndex);
            }
            var subRow = new MatrixRow("", "", action.actor(), action.method(), action.arguments());
            rows.add(insertAt, subRow);
            return aIndex;
        }
        // Append at end of transition
        int insertAt = transitionEndIndex(tIndex);
        var subRow = new MatrixRow("", "", action.actor(), action.method(), action.arguments());
        rows.add(insertAt, subRow);
        // Return the action index within this transition
        return insertAt - transitionStartIndex(tIndex);
    }

    /**
     * Updates action aIndex within transition tIndex.
     */
    public synchronized void updateAction(int tIndex, int aIndex, MatrixRow action) {
        int flatIdx = actionFlatIndex(tIndex, aIndex);
        var existing = rows.get(flatIdx);
        // Preserve from/to of the original row (transition row keeps its from/to, sub-actions keep empty)
        var updated = new MatrixRow(existing.from(), existing.to(), action.actor(), action.method(), action.arguments());
        rows.set(flatIdx, updated);
    }

    /**
     * Deletes action aIndex within transition tIndex.
     * If aIndex == 0 (the transition row itself), removes the entire transition and its sub-actions.
     */
    public synchronized MatrixRow deleteAction(int tIndex, int aIndex) {
        int flatIdx = actionFlatIndex(tIndex, aIndex);
        if (aIndex == 0) {
            // Deleting the transition row: remove it and all its sub-actions
            int end = transitionEndIndex(tIndex);
            List<MatrixRow> removed = new ArrayList<>();
            for (int i = end - 1; i >= flatIdx; i--) {
                removed.add(rows.remove(i));
            }
            return removed.get(removed.size() - 1); // return the transition row
        }
        return rows.remove(flatIdx);
    }
}
