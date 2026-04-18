package com.scivicslab.workfloweditor.service;

import com.scivicslab.workfloweditor.rest.WorkflowResource.MatrixRow;
import com.scivicslab.workfloweditor.service.WorkflowRunner.ParamMeta;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side holder for multiple workflow definitions (tabs).
 * Enables external API clients to inspect and manipulate workflows.
 */
@ApplicationScoped
public class WorkflowState {

    private final Map<String, TabData> tabs = new LinkedHashMap<>();
    private volatile String activeTab = null;

    public static class TabData {
        public final String name;
        public final CopyOnWriteArrayList<MatrixRow> rows = new CopyOnWriteArrayList<>();
        public volatile int maxIterations = 100;
        public volatile String description = null;
        public volatile Map<String, ParamMeta> params = new LinkedHashMap<>();

        public TabData(String name) {
            this.name = name;
        }
    }

    // --- Tab management ---

    public synchronized List<String> listTabs() {
        return new ArrayList<>(tabs.keySet());
    }

    public synchronized String getActiveTab() {
        return activeTab;
    }

    public synchronized TabData getTab(String name) {
        return tabs.get(name);
    }

    public synchronized String createTab(String name) {
        if (name == null || name.isBlank()) name = "workflow";
        // Ensure unique name
        String baseName = name;
        int counter = 1;
        while (tabs.containsKey(name)) {
            name = baseName + "-" + counter++;
        }
        tabs.put(name, new TabData(name));
        if (activeTab == null) {
            activeTab = name;
        }
        return name;
    }

    public synchronized boolean deleteTab(String name) {
        if (!tabs.containsKey(name)) return false;
        tabs.remove(name);
        if (name.equals(activeTab)) {
            activeTab = tabs.isEmpty() ? null : tabs.keySet().iterator().next();
        }
        return true;
    }

    public synchronized boolean activateTab(String name) {
        if (!tabs.containsKey(name)) return false;
        activeTab = name;
        return true;
    }

    public synchronized String renameTab(String oldName, String newName) {
        if (!tabs.containsKey(oldName)) return null;
        if (newName == null || newName.isBlank()) return null;
        if (tabs.containsKey(newName) && !oldName.equals(newName)) {
            // Ensure unique
            String base = newName;
            int counter = 1;
            while (tabs.containsKey(newName)) {
                newName = base + "-" + counter++;
            }
        }
        // Rebuild map to preserve order
        Map<String, TabData> newTabs = new LinkedHashMap<>();
        for (var entry : tabs.entrySet()) {
            if (entry.getKey().equals(oldName)) {
                var data = entry.getValue();
                var renamed = new TabData(newName);
                renamed.rows.addAll(data.rows);
                renamed.maxIterations = data.maxIterations;
                newTabs.put(newName, renamed);
            } else {
                newTabs.put(entry.getKey(), entry.getValue());
            }
        }
        tabs.clear();
        tabs.putAll(newTabs);
        if (oldName.equals(activeTab)) {
            activeTab = newName;
        }
        return newName;
    }

    // --- Active tab convenience (delegates to current active tab) ---

    private TabData active() {
        if (activeTab == null || !tabs.containsKey(activeTab)) {
            // Auto-create default tab
            createTab("workflow");
        }
        return tabs.get(activeTab);
    }

    public String getName() {
        return active().name;
    }

    public int getMaxIterations() {
        return active().maxIterations;
    }

    public String getDescription() {
        return active().description;
    }

    public void setDescription(String description) {
        active().description = description;
    }

    public Map<String, ParamMeta> getParams() {
        return active().params != null ? active().params : Map.of();
    }

    public void setParams(Map<String, ParamMeta> params) {
        active().params = params != null ? params : new LinkedHashMap<>();
    }

    public List<MatrixRow> getRows() {
        return Collections.unmodifiableList(new ArrayList<>(active().rows));
    }

    public int size() {
        return active().rows.size();
    }

    public synchronized void replaceAll(String name, List<MatrixRow> newRows, int maxIterations) {
        String tabName = name != null ? name : "workflow";
        // If a tab with this name exists, update it and activate it
        if (tabs.containsKey(tabName)) {
            var tab = tabs.get(tabName);
            tab.rows.clear();
            if (newRows != null) tab.rows.addAll(newRows);
            tab.maxIterations = maxIterations > 0 ? maxIterations : 100;
            activeTab = tabName;
        } else {
            // Create new tab
            var tab = new TabData(tabName);
            if (newRows != null) tab.rows.addAll(newRows);
            tab.maxIterations = maxIterations > 0 ? maxIterations : 100;
            tabs.put(tabName, tab);
            activeTab = tabName;
        }
    }

    public synchronized int addStep(MatrixRow row, Integer index) {
        var rows = active().rows;
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
        var rows = active().rows;
        if (index < 0 || index >= rows.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + rows.size() + ")");
        }
        rows.set(index, row);
    }

    public synchronized MatrixRow deleteStep(int index) {
        var rows = active().rows;
        if (index < 0 || index >= rows.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + rows.size() + ")");
        }
        return rows.remove(index);
    }

    // --- Transition / sub-action helpers ---

    public synchronized List<Map<String, Object>> getTransitions() {
        var rows = active().rows;
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

    private int transitionStartIndex(int tIndex) {
        var rows = active().rows;
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

    private int actionFlatIndex(int tIndex, int aIndex) {
        var rows = active().rows;
        int start = transitionStartIndex(tIndex);
        int actionCount = 0;
        for (int i = start; i < rows.size(); i++) {
            if (i > start) {
                var row = rows.get(i);
                if (row.from() != null && !row.from().isEmpty()
                        && row.to() != null && !row.to().isEmpty()) {
                    break;
                }
            }
            if (actionCount == aIndex) return i;
            actionCount++;
        }
        throw new IndexOutOfBoundsException(
                "Action " + aIndex + " not found in transition " + tIndex + " (has " + actionCount + " actions)");
    }

    private int transitionEndIndex(int tIndex) {
        var rows = active().rows;
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

    public synchronized int addAction(int tIndex, MatrixRow action, Integer aIndex) {
        var rows = active().rows;
        if (aIndex != null) {
            int insertAt;
            if (aIndex <= 0) {
                insertAt = transitionStartIndex(tIndex) + 1;
            } else {
                insertAt = actionFlatIndex(tIndex, aIndex);
            }
            var subRow = new MatrixRow("", "", action.actor(), action.method(), action.arguments());
            rows.add(insertAt, subRow);
            return aIndex;
        }
        int insertAt = transitionEndIndex(tIndex);
        var subRow = new MatrixRow("", "", action.actor(), action.method(), action.arguments());
        rows.add(insertAt, subRow);
        return insertAt - transitionStartIndex(tIndex);
    }

    public synchronized void updateAction(int tIndex, int aIndex, MatrixRow action) {
        var rows = active().rows;
        int flatIdx = actionFlatIndex(tIndex, aIndex);
        var existing = rows.get(flatIdx);
        var updated = new MatrixRow(existing.from(), existing.to(), action.actor(), action.method(), action.arguments());
        rows.set(flatIdx, updated);
    }

    public synchronized MatrixRow deleteAction(int tIndex, int aIndex) {
        var rows = active().rows;
        int flatIdx = actionFlatIndex(tIndex, aIndex);
        if (aIndex == 0) {
            int end = transitionEndIndex(tIndex);
            List<MatrixRow> removed = new ArrayList<>();
            for (int i = end - 1; i >= flatIdx; i--) {
                removed.add(rows.remove(i));
            }
            return removed.get(removed.size() - 1);
        }
        return rows.remove(flatIdx);
    }
}
