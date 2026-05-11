package com.scivicslab.workfloweditor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the S3→S4 RunParameterDialog contract:
 * WorkflowRunner.applyParameters() correctly substitutes ${key} placeholders
 * in YAML text without breaking YAML string escaping.
 *
 * The variable-scanning step (extracting ${vars} from YAML for the dialog UI)
 * is JavaScript-side logic and is not covered here.
 *
 * Pure-Java unit tests. No Quarkus, no HTTP.
 */
@DisplayName("S3→S4 Run parameter dialog: applyParameters substitution")
class RunParameterDialogTest {

    // ---- basic substitution --------------------------------------------

    @Nested
    @DisplayName("Basic substitution")
    class BasicSubstitution {

        @Test
        @DisplayName("placeholder in double-quoted string is replaced")
        void doubleQuoted() {
            String yaml = "arguments: \"${task}\"";
            String result = WorkflowRunner.applyParameters(yaml, Map.of("task", "Register repo"));
            assertTrue(result.contains("Register repo"), "value must be substituted, got: " + result);
            assertFalse(result.contains("${task}"), "placeholder must be gone, got: " + result);
        }

        @Test
        @DisplayName("placeholder in single-quoted string is replaced")
        void singleQuoted() {
            String yaml = "arguments: '${task}'";
            String result = WorkflowRunner.applyParameters(yaml, Map.of("task", "Register repo"));
            assertTrue(result.contains("Register repo"));
            assertFalse(result.contains("${task}"));
        }

        @Test
        @DisplayName("placeholder embedded in longer string is replaced")
        void embeddedInString() {
            String yaml = "arguments: \"Working on ${repo} in ${dir}\"";
            String result = WorkflowRunner.applyParameters(yaml,
                    Map.of("repo", "oogasawa/k8s-tree", "dir", "/home/devteam/works/k8s-tree"));
            assertTrue(result.contains("oogasawa/k8s-tree"));
            assertTrue(result.contains("/home/devteam/works/k8s-tree"));
        }

        @Test
        @DisplayName("multiple distinct placeholders are all replaced")
        void multipleParams() {
            String yaml = """
                    - actor: llm
                      method: callAgent
                      arguments: {"repo": "${repo}", "task": "${task}"}
                    """;
            String result = WorkflowRunner.applyParameters(yaml,
                    Map.of("repo", "oogasawa/k8s-tree", "task", "check pods"));
            assertTrue(result.contains("oogasawa/k8s-tree"));
            assertTrue(result.contains("check pods"));
            assertFalse(result.contains("${repo}"));
            assertFalse(result.contains("${task}"));
        }

        @Test
        @DisplayName("unknown placeholder is left as-is")
        void unknownPlaceholder() {
            String yaml = "arguments: \"${task}\"";
            String result = WorkflowRunner.applyParameters(yaml, Map.of("other", "value"));
            assertTrue(result.contains("${task}"), "unreferenced placeholder must remain, got: " + result);
        }

        @Test
        @DisplayName("null parameters returns yaml unchanged")
        void nullParams() {
            String yaml = "arguments: \"${task}\"";
            assertEquals(yaml, WorkflowRunner.applyParameters(yaml, null));
        }

        @Test
        @DisplayName("empty parameters map returns yaml unchanged")
        void emptyParams() {
            String yaml = "arguments: \"${task}\"";
            assertEquals(yaml, WorkflowRunner.applyParameters(yaml, Map.of()));
        }
    }

    // ---- YAML escaping -------------------------------------------------

    @Nested
    @DisplayName("YAML escaping in double-quoted strings")
    class YamlEscaping {

        @Test
        @DisplayName("double quote in value is escaped as \\\"")
        void doubleQuoteInValue() {
            String yaml = "arguments: \"${task}\"";
            String result = WorkflowRunner.applyParameters(yaml, Map.of("task", "say \"hello\""));
            // Value contains a literal quote — must be escaped so YAML remains valid
            assertFalse(result.contains("\"say \"hello\"\""),
                    "unescaped double quote would break YAML, got: " + result);
            assertTrue(result.contains("say"), "value content must be present, got: " + result);
        }

        @Test
        @DisplayName("backslash in value is escaped as \\\\")
        void backslashInValue() {
            String yaml = "arguments: \"${dir}\"";
            String result = WorkflowRunner.applyParameters(yaml, Map.of("dir", "C:\\Users\\devteam"));
            assertTrue(result.contains("C:\\\\Users\\\\devteam"), "backslash must be escaped, got: " + result);
        }

        @Test
        @DisplayName("newline in value is escaped as \\n")
        void newlineInValue() {
            String yaml = "arguments: \"${text}\"";
            String result = WorkflowRunner.applyParameters(yaml, Map.of("text", "line1\nline2"));
            assertTrue(result.contains("\\n"), "newline must be escaped as \\n, got: " + result);
            assertFalse(result.contains("\n\n"),
                    "literal newline inside double-quoted value would break YAML, got: " + result);
        }

        @Test
        @DisplayName("single quote in value is escaped as '' in single-quoted string")
        void singleQuoteInSingleQuotedString() {
            String yaml = "arguments: '${task}'";
            String result = WorkflowRunner.applyParameters(yaml, Map.of("task", "it's done"));
            assertTrue(result.contains("it''s done"), "single quote must be doubled, got: " + result);
        }
    }

    // ---- merge semantics (env file + dialog) ---------------------------

    @Nested
    @DisplayName("Parameter merge: dialog value overrides env file value")
    class MergeSemantics {

        @Test
        @DisplayName("last-write wins when both env and dialog define the same key")
        void dialogOverridesEnvFile() {
            // Caller merges env-file params first, then dialog params on top.
            // applyParameters receives the already-merged map.
            // If dialog defines task="new task" and env file had task="old task",
            // only the final merged map value reaches applyParameters.
            Map<String, String> merged = Map.of(
                    "repo", "oogasawa/k8s-tree",  // from env file
                    "task", "new task"             // dialog overrides env file's "old task"
            );
            String yaml = "arguments: \"${task} in ${repo}\"";
            String result = WorkflowRunner.applyParameters(yaml, merged);
            assertTrue(result.contains("new task"), "dialog value must win, got: " + result);
            assertFalse(result.contains("old task"), "env file value must be gone, got: " + result);
        }
    }
}
