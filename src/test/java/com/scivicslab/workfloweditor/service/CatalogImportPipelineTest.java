package com.scivicslab.workfloweditor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end pipeline test: scan → index → search → read file → parse YAML.
 * Covers the full path that the Import button exercises (minus HTTP layer).
 */
@DisplayName("CatalogImportPipeline")
class CatalogImportPipelineTest {

    @TempDir Path tempDir;

    private static final String WORKFLOW_YAML = """
            name: test-workflow
            description: A test workflow for pipeline verification
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: log
                    method: info
                    arguments: started
              - states: ["1", "end"]
                actions:
                  - actor: log
                    method: info
                    arguments: finished
            """;

    // --- scan → index → search: path survives the full round-trip ---

    @Test
    @DisplayName("pipeline_pathSurvivesRoundTrip")
    void pipeline_pathSurvivesRoundTrip() throws Exception {
        Path yamlFile = Files.writeString(tempDir.resolve("test-workflow.yaml"), WORKFLOW_YAML);

        // Step 1: scan
        var entries = CatalogScanner.scan(List.of(tempDir));
        assertEquals(1, entries.size());
        String scannedPath = entries.get(0).get("path");
        assertEquals(yamlFile.toAbsolutePath().toString(), scannedPath);

        // Step 2: index
        var indexService = new CatalogIndexService();
        indexService.rebuild(List.of(
                new CatalogIndexService.Entry(
                        entries.get(0).get("file"),
                        entries.get(0).get("path"),
                        entries.get(0).get("name"),
                        entries.get(0).get("description"))));

        // Step 3: search (no query → all results)
        var result = indexService.search(null, 0, 10);
        assertEquals(1, result.total());
        var found = result.results().get(0);
        assertEquals(scannedPath, found.path(),
                "path must survive scan → index → search round-trip");
    }

    // --- file read → YAML parse: content is correctly retrieved and parsed ---

    @Test
    @DisplayName("pipeline_fileReadAndParse_producesCorrectWorkflow")
    void pipeline_fileReadAndParse_producesCorrectWorkflow() throws Exception {
        Path yamlFile = Files.writeString(tempDir.resolve("test-workflow.yaml"), WORKFLOW_YAML);

        var entries = CatalogScanner.scan(List.of(tempDir));
        String path = entries.get(0).get("path");

        // Simulate GET /api/catalog/file?path=...
        String content = Files.readString(Path.of(path));
        assertFalse(content.isBlank(), "file content must not be empty");

        // Simulate POST /api/yaml/import
        var parsed = WorkflowRunner.fromYaml(content);
        assertEquals("test-workflow", parsed.name());
        assertNotNull(parsed.description());
        assertTrue(parsed.description().contains("pipeline verification"));
        assertEquals(2, parsed.steps().size());
    }

    // --- full pipeline in one pass: scan → index → search → file → parse ---

    @Test
    @DisplayName("pipeline_fullImportFlow_loadsWorkflowCorrectly")
    void pipeline_fullImportFlow_loadsWorkflowCorrectly() throws Exception {
        Files.writeString(tempDir.resolve("test-workflow.yaml"), WORKFLOW_YAML);

        // 1. scan
        var scanned = CatalogScanner.scan(List.of(tempDir));
        assertEquals(1, scanned.size());

        // 2. index
        var svc = new CatalogIndexService();
        svc.rebuild(scanned.stream()
                .map(e -> new CatalogIndexService.Entry(
                        e.get("file"), e.get("path"), e.get("name"), e.get("description")))
                .toList());

        // 3. search
        var sr = svc.search(null, 0, 10);
        assertEquals(1, sr.total());
        String retrievedPath = sr.results().get(0).path();
        assertNotNull(retrievedPath, "path from index must not be null");
        assertFalse(retrievedPath.isBlank(), "path from index must not be blank");

        // 4. read file
        String yaml = Files.readString(Path.of(retrievedPath));
        assertFalse(yaml.isBlank());

        // 5. parse
        var wf = WorkflowRunner.fromYaml(yaml);
        assertEquals("test-workflow", wf.name());
        assertEquals(2, wf.steps().size());
    }

    // --- recursive scan: workflows in subdirectories are found ---

    @Test
    @DisplayName("pipeline_recursiveScan_findsNestedWorkflows")
    void pipeline_recursiveScan_findsNestedWorkflows() throws Exception {
        Path sub = Files.createDirectory(tempDir.resolve("subdir"));
        Files.writeString(tempDir.resolve("top.yaml"), WORKFLOW_YAML.replace("test-workflow", "top"));
        Files.writeString(sub.resolve("nested.yaml"), WORKFLOW_YAML.replace("test-workflow", "nested"));

        var entries = CatalogScanner.scan(List.of(tempDir));
        assertEquals(2, entries.size());

        var names = entries.stream().map(e -> e.get("name")).toList();
        assertTrue(names.contains("top"));
        assertTrue(names.contains("nested"));

        // All paths must point to readable files
        for (var e : entries) {
            assertTrue(Files.exists(Path.of(e.get("path"))),
                    "path must point to an existing file: " + e.get("path"));
        }
    }

    // --- path null guard: entry without path produces a clear error, not NPE ---

    @Test
    @DisplayName("pipeline_entryWithNullPath_searchReturnsNull")
    void pipeline_entryWithNullPath_searchReturnsNull() throws Exception {
        var svc = new CatalogIndexService();
        svc.rebuild(List.of(new CatalogIndexService.Entry("wf.yaml", null, "wf", "desc")));

        var result = svc.search(null, 0, 10);
        assertEquals(1, result.total());
        // null path round-trips as null (or empty) — consumer must guard against this
        var path = result.results().get(0).path();
        assertTrue(path == null || path.isBlank(),
                "null path should come back as null or blank, not a garbage value");
    }
}
