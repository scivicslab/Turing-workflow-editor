package com.scivicslab.workfloweditor.service;

import com.scivicslab.workfloweditor.service.WorkflowRunner.ActionDto;
import com.scivicslab.workfloweditor.service.WorkflowRunner.ParamMeta;
import com.scivicslab.workfloweditor.service.WorkflowRunner.StepDto;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side holder for multiple workflow definitions (tabs).
 * Enables external API clients to inspect and manipulate workflows.
 */
@ApplicationScoped
public class WorkflowState {

    private final Map<String, TabData> tabs = new LinkedHashMap<>();
    private volatile String activeTab = null;
    private final List<String> catalogDirs = new ArrayList<>(
            List.of(System.getProperty("user.dir") + "/workflow"));

    public static class TabData {
        public final String name;
        public final List<StepDto> steps = new ArrayList<>();
        public volatile int maxIterations = 100;
        public volatile String description = null;
        public volatile Map<String, ParamMeta> params = new LinkedHashMap<>();
        public volatile String filePath = null;

        public TabData(String name) {
            this.name = name;
        }
    }

    // --- Catalog directories ---

    public synchronized List<String> getCatalogDirs() {
        return new ArrayList<>(catalogDirs);
    }

    public synchronized void setCatalogDirs(List<String> dirs) {
        catalogDirs.clear();
        if (dirs != null) catalogDirs.addAll(dirs);
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
                renamed.steps.addAll(data.steps);
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

    public String getFilePath() {
        return active().filePath;
    }

    public void setFilePath(String filePath) {
        active().filePath = filePath;
    }

    public List<StepDto> getSteps() {
        return Collections.unmodifiableList(new ArrayList<>(active().steps));
    }

    public int size() {
        return active().steps.size();
    }

    public synchronized void replaceAll(String name, List<StepDto> newSteps, int maxIterations) {
        String tabName = name != null ? name : "workflow";
        // If a tab with this name exists, update it and activate it
        if (tabs.containsKey(tabName)) {
            var tab = tabs.get(tabName);
            tab.steps.clear();
            if (newSteps != null) tab.steps.addAll(newSteps);
            tab.maxIterations = maxIterations > 0 ? maxIterations : 100;
            activeTab = tabName;
        } else {
            // Create new tab
            var tab = new TabData(tabName);
            if (newSteps != null) tab.steps.addAll(newSteps);
            tab.maxIterations = maxIterations > 0 ? maxIterations : 100;
            tabs.put(tabName, tab);
            activeTab = tabName;
        }
    }

    public synchronized int addStep(StepDto step, Integer index) {
        var steps = active().steps;
        if (index != null) {
            if (index < 0 || index > steps.size()) {
                throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + steps.size() + "]");
            }
            steps.add(index, step);
            return index;
        }
        steps.add(step);
        return steps.size() - 1;
    }

    public synchronized void updateStep(int index, StepDto step) {
        var steps = active().steps;
        if (index < 0 || index >= steps.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + steps.size() + ")");
        }
        steps.set(index, step);
    }

    public synchronized StepDto deleteStep(int index) {
        var steps = active().steps;
        if (index < 0 || index >= steps.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + steps.size() + ")");
        }
        return steps.remove(index);
    }

    // --- Transition / sub-action helpers ---

    public synchronized List<Map<String, Object>> getTransitions() {
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (var step : active().steps) {
            var transition = new LinkedHashMap<String, Object>();
            transition.put("from", step.from());
            transition.put("to", step.to());
            List<Map<String, String>> actionList = new ArrayList<>();
            if (step.actions() != null) {
                for (var action : step.actions()) {
                    var actionMap = new LinkedHashMap<String, String>();
                    actionMap.put("actor", action.actor());
                    actionMap.put("method", action.method());
                    actionMap.put("arguments", action.arguments());
                    actionList.add(actionMap);
                }
            }
            transition.put("actions", actionList);
            transitions.add(transition);
        }
        return transitions;
    }

    public synchronized int addAction(int stepIndex, ActionDto action, Integer aIndex) {
        var steps = active().steps;
        if (stepIndex < 0 || stepIndex >= steps.size()) {
            throw new IndexOutOfBoundsException("Step index " + stepIndex + " out of range [0, " + steps.size() + ")");
        }
        var step = steps.get(stepIndex);
        var newActions = new ArrayList<>(step.actions() != null ? step.actions() : List.of());
        int insertAt;
        if (aIndex != null) {
            if (aIndex < 0 || aIndex > newActions.size()) {
                throw new IndexOutOfBoundsException("Action index " + aIndex + " out of range [0, " + newActions.size() + "]");
            }
            newActions.add(aIndex, action);
            insertAt = aIndex;
        } else {
            newActions.add(action);
            insertAt = newActions.size() - 1;
        }
        steps.set(stepIndex, new StepDto(step.from(), step.to(), step.label(), step.note(),
                step.delay(), step.breakpoint(), newActions));
        return insertAt;
    }

    public synchronized void updateAction(int stepIndex, int aIndex, ActionDto action) {
        var steps = active().steps;
        if (stepIndex < 0 || stepIndex >= steps.size()) {
            throw new IndexOutOfBoundsException("Step index " + stepIndex + " out of range [0, " + steps.size() + ")");
        }
        var step = steps.get(stepIndex);
        var newActions = new ArrayList<>(step.actions() != null ? step.actions() : List.of());
        if (aIndex < 0 || aIndex >= newActions.size()) {
            throw new IndexOutOfBoundsException("Action index " + aIndex + " out of range [0, " + newActions.size() + ")");
        }
        newActions.set(aIndex, action);
        steps.set(stepIndex, new StepDto(step.from(), step.to(), step.label(), step.note(),
                step.delay(), step.breakpoint(), newActions));
    }

    public synchronized ActionDto deleteAction(int stepIndex, int aIndex) {
        var steps = active().steps;
        if (stepIndex < 0 || stepIndex >= steps.size()) {
            throw new IndexOutOfBoundsException("Step index " + stepIndex + " out of range [0, " + steps.size() + ")");
        }
        var step = steps.get(stepIndex);
        var newActions = new ArrayList<>(step.actions() != null ? step.actions() : List.of());
        if (aIndex < 0 || aIndex >= newActions.size()) {
            throw new IndexOutOfBoundsException("Action index " + aIndex + " out of range [0, " + newActions.size() + ")");
        }
        ActionDto removed = newActions.remove(aIndex);
        steps.set(stepIndex, new StepDto(step.from(), step.to(), step.label(), step.note(),
                step.delay(), step.breakpoint(), newActions));
        return removed;
    }
}
