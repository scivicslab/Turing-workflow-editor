package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E tests for (Y_exp→Y_yaml).02: catalog search and import.
 * Places a known workflow YAML in a temp scan directory, registers that directory
 * via PUT /api/catalog/dirs, and restores the original dirs in tearDown.
 */
public class S78_CatalogImportE2E {

    private static final String WF_NAME = "e2e-catalog-workflow";
    private static final String WF_DESC = "unique-e2e-catalog-description";

    private final Page page;
    private final String url;
    private Path scanDir;
    private Path wfFile;
    private final HttpClient http = HttpClient.newHttpClient();

    public S78_CatalogImportE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S78 CatalogImport: start");
        setUp();
        try {
            page.navigate(url);
            page.waitForSelector("#stepsContainer .step-group");

            catalog_opensModal();
            catalog_showsWorkflowEntry();
            catalog_search_filtersEntries();
            catalog_search_noMatch_showsEmptyMessage();
            catalog_import_loadsWorkflowIntoEditor();
        } finally {
            tearDown();
        }
        System.out.println("S78 CatalogImport: PASSED");
    }

    // ---- setup / teardown -----------------------------------------------

    private void setUp() {
        try {
            scanDir = Files.createTempDirectory("e2e-catalog-scan-");
            wfFile = scanDir.resolve(WF_NAME + ".yaml");
            Files.writeString(wfFile,
                    "name: " + WF_NAME + "\n" +
                    "description: " + WF_DESC + "\n" +
                    "steps:\n" +
                    "- states: [\"catalog-from\", \"catalog-to\"]\n" +
                    "  actions:\n" +
                    "  - actor: out\n" +
                    "    method: print\n" +
                    "    arguments: catalog-test\n");
            setCatalogDirs("[\"" + scanDir.toAbsolutePath() + "\"]");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("S78: setUp failed", e);
        }
    }

    private void tearDown() {
        try {
            // Restore default catalog dirs
            setCatalogDirs("[]");
        } catch (Exception e) {
            System.err.println("S78: tearDown dirs warning: " + e.getMessage());
        }
        try {
            if (wfFile != null) Files.deleteIfExists(wfFile);
            if (scanDir != null) Files.deleteIfExists(scanDir);
        } catch (IOException e) {
            System.err.println("S78: tearDown files warning: " + e.getMessage());
        }
        try {
            page.evaluate("() => localStorage.removeItem('workflow-editor-state')");
        } catch (Exception e) {
            System.err.println("S78: localStorage cleanup warning: " + e.getMessage());
        }
    }

    private void setCatalogDirs(String jsonArray) throws InterruptedException {
        try {
            http.send(HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/catalog/dirs"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonArray))
                    .build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            System.err.println("S78: setCatalogDirs warning: " + e.getMessage());
        }
    }

    // ---- scenarios -------------------------------------------------------

    private void catalog_opensModal() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        openCatalog();
        assertTrue("catalog_opensModal: overlay visible",
                page.isVisible("#catalogOverlay"));

        closeCatalog();
    }

    private void catalog_showsWorkflowEntry() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        openCatalog();
        page.waitForFunction(
                "() => document.querySelector('.catalog-entry-name') !== null");

        String names = page.locator(".catalog-entry-name").allTextContents().toString();
        assertTrue("catalog_showsWorkflowEntry: entry name appears", names.contains(WF_NAME));

        closeCatalog();
        System.out.println("  catalog_showsWorkflowEntry: PASSED");
    }

    private void catalog_search_filtersEntries() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        openCatalog();
        page.waitForFunction("() => document.querySelector('.catalog-entry-name') !== null");

        // Search by the unique description keyword
        page.fill("#catalogSearch", WF_DESC);
        page.waitForFunction(
                "() => document.querySelectorAll('.catalog-entry').length === 1");

        String names = page.locator(".catalog-entry-name").allTextContents().toString();
        assertTrue("catalog_search_filtersEntries: only matching entry remains",
                names.contains(WF_NAME));

        closeCatalog();
        System.out.println("  catalog_search_filtersEntries: PASSED");
    }

    private void catalog_search_noMatch_showsEmptyMessage() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        openCatalog();
        page.waitForFunction("() => document.querySelector('.catalog-entry-name') !== null");

        page.fill("#catalogSearch", "xyzzy-no-match-keyword-99999");
        page.waitForSelector(".catalog-empty");

        assertTrue("catalog_search_noMatch_showsEmptyMessage: empty message visible",
                page.isVisible(".catalog-empty"));

        closeCatalog();
        System.out.println("  catalog_search_noMatch_showsEmptyMessage: PASSED");
    }

    private void catalog_import_loadsWorkflowIntoEditor() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        openCatalog();
        page.waitForFunction("() => document.querySelector('.catalog-entry-name') !== null");

        // Filter to our entry, then click Import
        page.fill("#catalogSearch", WF_NAME);
        page.waitForFunction(
                "() => document.querySelectorAll('.catalog-entry').length === 1");
        page.locator(".catalog-import-btn").first().click();

        // Modal closes and step table reflects the imported workflow
        page.waitForFunction("() => document.getElementById('catalogOverlay').style.display === 'none'");
        page.waitForFunction(
                "() => document.querySelector('.step-from') && " +
                "document.querySelector('.step-from').value === 'catalog-from'");

        Locator firstGroup = page.locator(".step-group").first();
        assertEqual("catalog_import: from field", "catalog-from",
                firstGroup.locator(".step-from").inputValue());
        assertEqual("catalog_import: to field", "catalog-to",
                firstGroup.locator(".step-to").inputValue());

        System.out.println("  catalog_import_loadsWorkflowIntoEditor: PASSED");
    }

    // ---- helpers --------------------------------------------------------

    private void openCatalog() {
        page.click("#fileMenuBtn");
        page.waitForFunction("() => document.getElementById('fileMenu').style.display !== 'none'");
        page.click("#openCatalogBtn");
        page.waitForFunction("() => document.getElementById('catalogOverlay').style.display !== 'none'");
    }

    private void closeCatalog() {
        page.click("#catalogCloseBtn");
        page.waitForFunction("() => document.getElementById('catalogOverlay').style.display === 'none'");
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
