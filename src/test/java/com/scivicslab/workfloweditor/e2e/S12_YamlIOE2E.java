package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E tests for S1→S2: YAML I/O.
 * Verifies Export (download), Import (file upload), and New workflow creation.
 */
public class S12_YamlIOE2E {

    private final Page page;
    private final String url;

    public S12_YamlIOE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S12 YamlIO: start");
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        export_downloadedFileContainsExpectedYaml();
        importYaml_stepTableReflectsYamlContent();
        newWorkflow_tableResetToTemplate();

        System.out.println("S12 YamlIO: PASSED");
    }

    // ---- scenarios -----------------------------------------------------

    private void export_downloadedFileContainsExpectedYaml() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        // Enter known content into the first step
        Locator firstGroup = page.locator(".step-group").first();
        firstGroup.locator(".step-from").fill("alpha");
        firstGroup.locator(".step-to").fill("beta");
        firstGroup.locator(".step-note-input").fill("export-test-note");

        // Open File menu, then click Export (Save As)
        openFileMenu();
        Download download = page.waitForDownload(() -> page.click("#exportYamlBtn"));

        String filename = download.suggestedFilename();
        assertTrue("export: filename ends with .yaml", filename.endsWith(".yaml"));

        String content;
        try {
            content = Files.readString(download.path());
        } catch (Exception e) {
            throw new RuntimeException("export: could not read downloaded file", e);
        }

        assertTrue("export: YAML contains 'name:'", content.contains("name:"));
        assertTrue("export: YAML contains 'steps:'", content.contains("steps:"));
        assertTrue("export: YAML contains from value 'alpha'", content.contains("alpha"));
        assertTrue("export: YAML contains to value 'beta'", content.contains("beta"));
        assertTrue("export: YAML contains note", content.contains("export-test-note"));

        System.out.println("  export_downloadedFileContainsExpectedYaml: PASSED");
    }

    private void importYaml_stepTableReflectsYamlContent() {
        // Write a known YAML to a temp file
        String yaml = """
                name: imported-wf
                steps:
                - states: ["imported-from", "imported-to"]
                  note: imported note
                  actions:
                  - actor: out
                    method: print
                    arguments: imported-value
                """;
        Path tmpFile;
        try {
            tmpFile = Files.createTempFile("e2e-import-", ".yaml");
            Files.writeString(tmpFile, yaml);
        } catch (Exception e) {
            throw new RuntimeException("importYaml: could not create temp file", e);
        }

        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        // Use the direct header Import YAML button (always visible, no menu needed)
        FileChooser fileChooser = page.waitForFileChooser(
                () -> page.click("#importYamlHeaderBtn"));
        fileChooser.setFiles(tmpFile);

        // Wait for the step table to reload
        page.waitForFunction(
                "() => document.querySelector('.step-from') && " +
                "document.querySelector('.step-from').value === 'imported-from'");

        Locator firstGroup = page.locator(".step-group").first();
        assertEqual("importYaml: from field", "imported-from",
                firstGroup.locator(".step-from").inputValue());
        assertEqual("importYaml: to field", "imported-to",
                firstGroup.locator(".step-to").inputValue());
        assertEqual("importYaml: note field", "imported note",
                firstGroup.locator(".step-note-input").inputValue());

        System.out.println("  importYaml_stepTableReflectsYamlContent: PASSED");
    }

    private void newWorkflow_tableResetToTemplate() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        // Handle the prompt() dialog (one-shot handler to avoid accumulation across scenarios)
        acceptNextDialog("my-new-workflow");

        // Open File menu, then click New
        openFileMenu();
        page.click("#newWorkflowBtn");

        page.waitForFunction(
                "() => document.querySelector('.step-from') && " +
                "document.querySelector('.step-from').value === '0'");

        Locator firstGroup = page.locator(".step-group").first();
        assertEqual("newWorkflow: from is '0'", "0",
                firstGroup.locator(".step-from").inputValue());
        assertEqual("newWorkflow: to is '1'", "1",
                firstGroup.locator(".step-to").inputValue());

        System.out.println("  newWorkflow_tableResetToTemplate: PASSED");
    }

    // ---- helpers -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void acceptNextDialog(String answer) {
        java.util.function.Consumer<Dialog>[] ref = new java.util.function.Consumer[1];
        ref[0] = dialog -> {
            page.offDialog(ref[0]);
            dialog.accept(answer);
        };
        page.onDialog(ref[0]);
    }

    private void openFileMenu() {
        page.click("#fileMenuBtn");
        page.waitForFunction("() => document.getElementById('fileMenu').style.display !== 'none'");
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
