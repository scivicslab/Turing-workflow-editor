package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E tests for S3→S4: Run button and Parameter Panel.
 * Verifies that the run panel opens, parameter inputs appear for workflow
 * variables, the panel can be closed, and workflow execution can be started
 * and stopped.
 */
public class S34_RunParameterDialogE2E {

    private final Page page;
    private final String url;

    public S34_RunParameterDialogE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S34 RunParameterDialog: start");

        runButton_opensRunPanel();
        yamlWithVariables_showsRequiredParamInputs();
        closePanel_panelBecomesHidden();
        startExecution_stopBtnBecomesEnabled();
        stopExecution_stoppedEventInLog();

        System.out.println("S34 RunParameterDialog: PASSED");
    }

    // ---- scenarios -----------------------------------------------------

    private void runButton_opensRunPanel() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        page.click("#runBtn");

        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");
        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");

        assertTrue("runButton: side panel is visible",
                !"none".equals(page.locator("#sidePanel").getAttribute("style").replace(" ", "")));

        System.out.println("  runButton_opensRunPanel: PASSED");
    }

    private void yamlWithVariables_showsRequiredParamInputs() {
        String yaml = """
                name: param-test-wf
                steps:
                - states: ["0", "1"]
                  note: test step
                  actions:
                  - actor: out
                    method: print
                    arguments: ${task}
                """;
        Path tmpFile = writeTempYaml(yaml, "e2e-param-");

        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        FileChooser fileChooser = page.waitForFileChooser(
                () -> page.click("#importYamlHeaderBtn"));
        fileChooser.setFiles(tmpFile);

        page.waitForFunction(
                "() => document.querySelector('.step-from') && " +
                "document.querySelector('.step-from').value === '0'");

        page.click("#runBtn");
        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");
        page.waitForFunction(
                "() => document.querySelector('[data-param-key=\"task\"]') !== null");

        Locator taskInput = page.locator("[data-param-key='task']");
        assertTrue("yamlWithVariables: task input field is present",
                taskInput.count() > 0);

        System.out.println("  yamlWithVariables_showsRequiredParamInputs: PASSED");
    }

    private void closePanel_panelBecomesHidden() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        page.click("#runBtn");
        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");

        page.click("#sidePanelClose");

        page.waitForFunction("() => document.getElementById('sidePanel').style.display === 'none'");

        String display = page.locator("#sidePanel").evaluate("el => el.style.display").toString();
        assertEqual("closePanel: side panel is hidden", "none", display);

        System.out.println("  closePanel_panelBecomesHidden: PASSED");
    }

    private void startExecution_stopBtnBecomesEnabled() {
        String yaml = """
                name: start-test-wf
                steps:
                - states: ["0", "1"]
                  note: quick step
                  actions:
                  - actor: out
                    method: print
                    arguments: hello
                """;
        Path tmpFile = writeTempYaml(yaml, "e2e-start-");
        importYaml(tmpFile);

        page.click("#runBtn");
        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");

        page.click("#paramExecute");

        page.waitForFunction(
                "() => !document.getElementById('stopBtn').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        assertTrue("startExecution: stop button is enabled",
                !Boolean.parseBoolean(page.locator("#stopBtn").getAttribute("disabled")));

        System.out.println("  startExecution_stopBtnBecomesEnabled: PASSED");
    }

    private void stopExecution_stoppedEventInLog() {
        // Use shell sleep to guarantee the workflow is still running when Stop is clicked
        String yaml = """
                name: stop-test-wf
                steps:
                - states: ["0", "1"]
                  note: long running step
                  actions:
                  - actor: shell
                    method: exec
                    arguments: sleep 30
                """;
        Path tmpFile = writeTempYaml(yaml, "e2e-stop-");
        importYaml(tmpFile);

        // Set log level so stopped events are visible (they always pass, but select FINE to see all)
        page.selectOption("#logLevelSelect", "FINE");

        page.click("#runBtn");
        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");
        page.click("#paramExecute");

        // Wait for Stop to be clickable
        page.waitForFunction(
                "() => !document.getElementById('stopBtn').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        page.click("#stopBtn");

        // Workflow is stopped when stopBtn becomes disabled again
        page.waitForFunction(
                "() => document.getElementById('stopBtn').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(30000));

        assertTrue("stopExecution: stopBtn disabled after stop",
                Boolean.TRUE.equals(page.locator("#stopBtn").evaluate("el => el.disabled")));

        // Either stopped or completed event appears in the log (always visible regardless of level)
        int terminatedCount = page.locator("#logOutput .log-entry.stopped, #logOutput .log-entry.completed")
                .count();
        assertTrue("stopExecution: terminated event in log", terminatedCount > 0);

        System.out.println("  stopExecution_stoppedEventInLog: PASSED");
    }

    // ---- helpers -------------------------------------------------------

    private void importYaml(Path file) {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");
        // Use the direct header button (always visible, no menu needed)
        page.waitForResponse("**/api/yaml/import", () -> {
            FileChooser fc = page.waitForFileChooser(() -> page.click("#importYamlHeaderBtn"));
            fc.setFiles(file);
        });
        page.waitForSelector("#stepsContainer .step-group");
    }

    private static Path writeTempYaml(String yaml, String prefix) {
        try {
            Path tmp = Files.createTempFile(prefix, ".yaml");
            Files.writeString(tmp, yaml);
            return tmp;
        } catch (Exception e) {
            throw new RuntimeException("could not create temp YAML file", e);
        }
    }

    private static void assertEqual(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertTrue(String label, boolean condition) {
        if (!condition) {
            throw new AssertionError(label + ": expected true");
        }
    }
}
