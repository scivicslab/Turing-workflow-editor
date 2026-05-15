package com.scivicslab.workfloweditor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CatalogScanner")
class CatalogScannerTest {

    @TempDir Path tempDir;

    // --- parseEntry ---

    @Test
    @DisplayName("parseEntry_withNameAndDescription_extractsBoth")
    void parseEntry_withNameAndDescription_extractsBoth() throws IOException {
        Path yaml = writeYaml(tempDir, "wf.yaml",
                "name: my-workflow\ndescription: Does something useful\nsteps:\n- states: [\"0\",\"1\"]\n");
        var entry = CatalogScanner.parseEntry(yaml, "my-dir");
        assertEquals("my-workflow", entry.get("name"));
        assertEquals("Does something useful", entry.get("description"));
    }

    @Test
    @DisplayName("parseEntry_withoutName_usesFilenameWithoutExtension")
    void parseEntry_withoutName_usesFilenameWithoutExtension() throws IOException {
        Path yaml = writeYaml(tempDir, "my-flow.yaml", "steps:\n- states: [\"0\",\"1\"]\n");
        var entry = CatalogScanner.parseEntry(yaml, "some-dir");
        assertEquals("my-flow", entry.get("name"));
    }

    @Test
    @DisplayName("parseEntry_withoutDescription_returnsEmptyString")
    void parseEntry_withoutDescription_returnsEmptyString() throws IOException {
        Path yaml = writeYaml(tempDir, "wf.yaml", "name: foo\nsteps:\n- states: [\"0\",\"1\"]\n");
        var entry = CatalogScanner.parseEntry(yaml, "some-dir");
        assertEquals("", entry.get("description"));
    }

    @Test
    @DisplayName("parseEntry_quotedValues_stripsQuotes")
    void parseEntry_quotedValues_stripsQuotes() throws IOException {
        Path yaml = writeYaml(tempDir, "wf.yaml",
                "name: 'single-quoted'\ndescription: \"double-quoted\"\nsteps:\n- states: [\"0\",\"1\"]\n");
        var entry = CatalogScanner.parseEntry(yaml, "some-dir");
        assertEquals("single-quoted", entry.get("name"));
        assertEquals("double-quoted", entry.get("description"));
    }

    @Test
    @DisplayName("parseEntry_noStepsOrStates_returnsNull")
    void parseEntry_noStepsOrStates_returnsNull() throws IOException {
        Path yaml = writeYaml(tempDir, "config.yaml", "name: not-a-workflow\nkey: value\n");
        assertNull(CatalogScanner.parseEntry(yaml, "some-dir"));
    }

    @Test
    @DisplayName("parseEntry_sourceNameIsPreserved")
    void parseEntry_sourceNameIsPreserved() throws IOException {
        Path yaml = writeYaml(tempDir, "wf.yaml", "steps:\n- states: [\"0\",\"1\"]\n");
        var entry = CatalogScanner.parseEntry(yaml, "my-dir");
        assertEquals("my-dir", entry.get("source"));
    }

    @Test
    @DisplayName("parseEntry_absolutePathIsPreserved")
    void parseEntry_absolutePathIsPreserved() throws IOException {
        Path yaml = writeYaml(tempDir, "wf.yaml", "steps:\n- states: [\"0\",\"1\"]\n");
        var entry = CatalogScanner.parseEntry(yaml, "some-dir");
        assertEquals(yaml.toAbsolutePath().toString(), entry.get("path"));
    }

    // --- scan ---

    @Test
    @DisplayName("scan_singleDir_returnsEntries")
    void scan_singleDir_returnsEntries() throws IOException {
        writeYaml(tempDir, "wf.yaml", "name: wf\nsteps:\n- states: [\"0\",\"1\"]\n");

        List<Map<String, String>> entries = CatalogScanner.scan(List.of(tempDir));
        assertEquals(1, entries.size());
        assertEquals("wf", entries.get(0).get("name"));
        assertEquals(tempDir.toString(), entries.get(0).get("source"));
    }

    @Test
    @DisplayName("scan_multipleDirs_aggregatesEntries")
    void scan_multipleDirs_aggregatesEntries() throws IOException {
        Path dir1 = Files.createDirectory(tempDir.resolve("dir1"));
        Path dir2 = Files.createDirectory(tempDir.resolve("dir2"));
        writeYaml(dir1, "wf1.yaml", "name: wf1\nsteps:\n- states: [\"0\",\"1\"]\n");
        writeYaml(dir2, "wf2.yaml", "name: wf2\nsteps:\n- states: [\"0\",\"1\"]\n");

        List<Map<String, String>> entries = CatalogScanner.scan(List.of(dir1, dir2));
        assertEquals(2, entries.size());
        var names = entries.stream().map(e -> e.get("name")).toList();
        assertTrue(names.contains("wf1"));
        assertTrue(names.contains("wf2"));
    }

    @Test
    @DisplayName("scan_skipsNonWorkflowYamls")
    void scan_skipsNonWorkflowYamls() throws IOException {
        writeYaml(tempDir, "wf.yaml", "name: real\nsteps:\n- states: [\"0\",\"1\"]\n");
        writeYaml(tempDir, "config.yaml", "name: not-a-workflow\nkey: value\n");

        List<Map<String, String>> entries = CatalogScanner.scan(List.of(tempDir));
        assertEquals(1, entries.size());
        assertEquals("real", entries.get(0).get("name"));
    }

    @Test
    @DisplayName("scan_emptyDir_returnsEmptyList")
    void scan_emptyDir_returnsEmptyList() {
        assertTrue(CatalogScanner.scan(List.of(tempDir)).isEmpty());
    }

    @Test
    @DisplayName("scan_nonExistentDir_returnsEmptyList")
    void scan_nonExistentDir_returnsEmptyList() {
        assertTrue(CatalogScanner.scan(List.of(tempDir.resolve("nonexistent"))).isEmpty());
    }

    @Test
    @DisplayName("scan_emptyDirList_returnsEmptyList")
    void scan_emptyDirList_returnsEmptyList() {
        assertTrue(CatalogScanner.scan(List.of()).isEmpty());
    }

    // --- helper ---

    private Path writeYaml(Path dir, String filename, String content) throws IOException {
        Path file = dir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }
}
