package com.scivicslab.workfloweditor.service;

import com.scivicslab.workfloweditor.service.WorkflowRunner.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the S1→S2 YAML I/O contract:
 * - fromYaml() correctly parses all supported YAML structures
 * - toYamlStructured() output is re-importable (round-trip identity)
 *
 * Pure-Java unit tests. No Quarkus, no HTTP.
 */
@DisplayName("S1→S2 YAML I/O: fromYaml parsing and export round-trip")
class YamlImportTest {

    // ---- helpers -------------------------------------------------------

    private static final String MINIMAL_YAML = """
            name: test-wf
            steps:
            - states: ["0", "1"]
              actions:
              - actor: out
                method: print
                arguments: hello
            """;

    // ---- fromYaml parsing ----------------------------------------------

    @Nested
    @DisplayName("fromYaml: basic fields")
    class BasicParsing {

        @Test
        @DisplayName("name is parsed")
        void name() {
            var result = WorkflowRunner.fromYaml(MINIMAL_YAML);
            assertEquals("test-wf", result.name());
        }

        @Test
        @DisplayName("missing description returns null")
        void missingDescription() {
            var result = WorkflowRunner.fromYaml(MINIMAL_YAML);
            assertNull(result.description());
        }

        @Test
        @DisplayName("description is parsed when present")
        void description() {
            String yaml = """
                    name: wf
                    description: My workflow description
                    steps:
                    - states: ["0", "end"]
                      actions:
                      - actor: out
                        method: print
                        arguments: done
                    """;
            var result = WorkflowRunner.fromYaml(yaml);
            assertEquals("My workflow description", result.description());
        }

        @Test
        @DisplayName("states: [from, to] format is parsed")
        void statesFormat() {
            var result = WorkflowRunner.fromYaml(MINIMAL_YAML);
            assertEquals("0", result.steps().get(0).from());
            assertEquals("1", result.steps().get(0).to());
        }

        @Test
        @DisplayName("from: / to: format is also accepted")
        void fromToFormat() {
            String yaml = """
                    name: wf
                    steps:
                    - from: "start"
                      to: "done"
                      actions:
                      - actor: out
                        method: print
                        arguments: ok
                    """;
            var result = WorkflowRunner.fromYaml(yaml);
            assertEquals("start", result.steps().get(0).from());
            assertEquals("done", result.steps().get(0).to());
        }

        @Test
        @DisplayName("empty steps list is accepted")
        void emptySteps() {
            String yaml = "name: empty\nsteps: []\n";
            var result = WorkflowRunner.fromYaml(yaml);
            assertEquals("empty", result.name());
            assertTrue(result.steps().isEmpty());
        }

        @Test
        @DisplayName("missing steps section is accepted")
        void missingSteps() {
            String yaml = "name: no-steps\n";
            var result = WorkflowRunner.fromYaml(yaml);
            assertTrue(result.steps().isEmpty());
        }
    }

    @Nested
    @DisplayName("fromYaml: params section")
    class ParamsParsing {

        @Test
        @DisplayName("params section is parsed")
        void paramsPresent() {
            String yaml = """
                    name: parameterized
                    params:
                      task:
                        description: Task to execute
                        default: ""
                      model:
                        description: LLM model name
                        default: claude-opus-4-5
                    steps:
                    - states: ["0", "end"]
                      actions:
                      - actor: out
                        method: print
                        arguments: done
                    """;
            var result = WorkflowRunner.fromYaml(yaml);
            assertNotNull(result.params());
            assertEquals(2, result.params().size());
            assertEquals("Task to execute", result.params().get("task").description());
            assertEquals("claude-opus-4-5", result.params().get("model").defaultValue());
        }

        @Test
        @DisplayName("missing params section returns empty map, not null")
        void missingParamsNotNull() {
            var result = WorkflowRunner.fromYaml(MINIMAL_YAML);
            assertNotNull(result.params());
        }
    }

    @Nested
    @DisplayName("fromYaml: arguments encoding")
    class ArgumentsParsing {

        @Test
        @DisplayName("JSON object arguments are preserved (not corrupted to {key=value})")
        void jsonObjectArguments() {
            String yaml = """
                    name: wf
                    steps:
                    - states: ["0", "end"]
                      actions:
                      - actor: llm
                        method: callAgent
                        arguments: {"agent": "quarkus-chat-ui", "prompt": "hello"}
                    """;
            var result = WorkflowRunner.fromYaml(yaml);
            String args = result.steps().get(0).actions().get(0).arguments();
            assertTrue(args.contains("\"agent\""), "key must be quoted, got: " + args);
            assertFalse(args.contains("agent="), "must not use = syntax, got: " + args);
        }

        @Test
        @DisplayName("JSON array arguments are preserved")
        void jsonArrayArguments() {
            String yaml = """
                    name: wf
                    steps:
                    - states: ["0", "end"]
                      actions:
                      - actor: loader
                        method: createChild
                        arguments: ["ROOT", "llm", "com.example.LlmActor"]
                    """;
            var result = WorkflowRunner.fromYaml(yaml);
            String args = result.steps().get(0).actions().get(0).arguments();
            assertTrue(args.contains("ROOT"), "first element must be present, got: " + args);
            assertTrue(args.contains("LlmActor"), "last element must be present, got: " + args);
        }

        @Test
        @DisplayName("null arguments are preserved as null")
        void nullArguments() {
            String yaml = """
                    name: wf
                    steps:
                    - states: ["0", "end"]
                      actions:
                      - actor: promptBuilder
                        method: clear
                    """;
            var result = WorkflowRunner.fromYaml(yaml);
            assertNull(result.steps().get(0).actions().get(0).arguments());
        }
    }

    // ---- export → import round-trip ------------------------------------

    @Nested
    @DisplayName("Export → Import round-trip")
    class RoundTrip {

        @Test
        @DisplayName("toYamlStructured output can be re-imported without loss")
        void fullRoundTrip() {
            var actions = List.of(new ActionDto("loader", "loadJar", "com.example:plugin:1.0.0"));
            var steps = List.of(new StepDto("0", "1", null, "Load plugin", null, null, actions));

            String exported = WorkflowRunner.toYamlStructured("my-wf", "My description", steps);
            var reimported = WorkflowRunner.fromYaml(exported);

            assertEquals("my-wf", reimported.name());
            assertEquals("My description", reimported.description());
            assertEquals(1, reimported.steps().size());
            assertEquals("0", reimported.steps().get(0).from());
            assertEquals("Load plugin", reimported.steps().get(0).note());
            assertEquals("com.example:plugin:1.0.0", reimported.steps().get(0).actions().get(0).arguments());
        }

        @Test
        @DisplayName("params survive round-trip")
        void paramsRoundTrip() {
            var steps = List.of(new StepDto("0", "end", null, null, null, null,
                    List.of(new ActionDto("out", "print", "${task}"))));
            var params = new java.util.LinkedHashMap<String, WorkflowRunner.ParamMeta>();
            params.put("task", new WorkflowRunner.ParamMeta("Task to execute", "default-task"));

            String exported = WorkflowRunner.toYamlStructured("wf", null, steps, params);
            var reimported = WorkflowRunner.fromYaml(exported);

            assertNotNull(reimported.params());
            assertTrue(reimported.params().containsKey("task"));
            assertEquals("Task to execute", reimported.params().get("task").description());
            assertEquals("default-task", reimported.params().get("task").defaultValue());
        }
    }
}
