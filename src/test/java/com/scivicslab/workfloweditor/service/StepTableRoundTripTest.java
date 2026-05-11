package com.scivicslab.workfloweditor.service;

import com.scivicslab.workfloweditor.service.WorkflowRunner.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the S0→S1 step table editor contract:
 * data entered in the step table (StepDto list) survives a
 * toYamlStructured() → fromYaml() round-trip without loss or corruption.
 *
 * These are pure-Java unit tests. No Quarkus, no HTTP, no external services.
 */
@DisplayName("S0→S1 Step table editor: toYamlStructured / fromYaml round-trip")
class StepTableRoundTripTest {

    // ---- helpers -------------------------------------------------------

    private static ParsedWorkflow roundTrip(String name, List<StepDto> steps) {
        String yaml = WorkflowRunner.toYamlStructured(name, null, steps);
        return WorkflowRunner.fromYaml(yaml);
    }

    private static StepDto step(String from, String to, String note, String actor,
                                String method, String arguments) {
        return new StepDto(from, to, null, note, null, null,
                List.of(new ActionDto(actor, method, arguments)));
    }

    // ---- tests ---------------------------------------------------------

    @Nested
    @DisplayName("Basic fields")
    class BasicFields {

        @Test
        @DisplayName("from / to survive round-trip")
        void fromTo() {
            var steps = List.of(step("0", "1", null, "loader", "loadJar", "com.example:lib:1.0"));
            var result = roundTrip("wf", steps);
            assertEquals("0", result.steps().get(0).from());
            assertEquals("1", result.steps().get(0).to());
        }

        @Test
        @DisplayName("note survives round-trip")
        void note() {
            var steps = List.of(step("0", "1", "Load the plugin JAR", "loader", "loadJar", "x:y:1.0"));
            var result = roundTrip("wf", steps);
            assertEquals("Load the plugin JAR", result.steps().get(0).note());
        }

        @Test
        @DisplayName("workflow name survives round-trip")
        void workflowName() {
            var steps = List.of(step("0", "end", null, "out", "print", "done"));
            var result = roundTrip("my-workflow", steps);
            assertEquals("my-workflow", result.name());
        }

        @Test
        @DisplayName("multiple steps survive round-trip in order")
        void multipleSteps() {
            var steps = List.of(
                    step("0", "1", "first", "loader", "loadJar", "a:b:1.0"),
                    step("1", "2", "second", "llm", "setUrl", "http://localhost:28001/mcp/_all"),
                    step("2", "end", "third", "out", "print", "done")
            );
            var result = roundTrip("wf", steps);
            assertEquals(3, result.steps().size());
            assertEquals("0", result.steps().get(0).from());
            assertEquals("1", result.steps().get(1).from());
            assertEquals("2", result.steps().get(2).from());
        }
    }

    @Nested
    @DisplayName("Arguments encoding")
    class ArgumentsEncoding {

        @Test
        @DisplayName("plain string argument survives round-trip")
        void plainString() {
            var steps = List.of(step("0", "1", null, "loader", "loadJar",
                    "com.scivicslab.turingworkflow.plugins:plugin-llm:1.0.0"));
            var result = roundTrip("wf", steps);
            assertEquals("com.scivicslab.turingworkflow.plugins:plugin-llm:1.0.0",
                    result.steps().get(0).actions().get(0).arguments());
        }

        @Test
        @DisplayName("JSON array argument survives round-trip")
        void jsonArray() {
            var steps = List.of(step("0", "1", null, "loader", "createChild",
                    "[\"ROOT\", \"llm\", \"com.example.LlmActor\"]"));
            var result = roundTrip("wf", steps);
            assertEquals("[\"ROOT\",\"llm\",\"com.example.LlmActor\"]",
                    result.steps().get(0).actions().get(0).arguments());
        }

        @Test
        @DisplayName("JSON object argument survives round-trip (regression: was corrupted to {key=value})")
        void jsonObject() {
            var steps = List.of(step("7", "end", null, "llm", "callAgent",
                    "{\"agent\": \"quarkus-chat-ui-28003\", \"prompt\": \"hello\", \"caller\": \"test\"}"));
            var result = roundTrip("wf", steps);
            String args = result.steps().get(0).actions().get(0).arguments();
            assertTrue(args.contains("\"agent\""), "key must be quoted, got: " + args);
            assertTrue(args.contains("quarkus-chat-ui-28003"), "value must be preserved, got: " + args);
            assertFalse(args.contains("agent="), "must not use = assignment syntax, got: " + args);
        }

        @Test
        @DisplayName("bracket-prefixed plain string argument survives round-trip")
        void bracketPrefixedPlainString() {
            var steps = List.of(step("10", "11", null, "out", "print",
                    "[batch 1/4] Reading 010_SimpleWorkflow, 020_UsingVariables, 030_Expressions ..."));
            var result = roundTrip("wf", steps);
            assertEquals("[batch 1/4] Reading 010_SimpleWorkflow, 020_UsingVariables, 030_Expressions ...",
                    result.steps().get(0).actions().get(0).arguments());
        }

        @Test
        @DisplayName("bracket-prefixed string in yaml output is quoted")
        void bracketPrefixedYamlIsQuoted() {
            var steps = List.of(step("10", "11", null, "out", "print",
                    "[batch 1/4] Reading files ..."));
            String yaml = WorkflowRunner.toYamlStructured("wf", null, steps);
            assertTrue(yaml.contains("arguments: \"[batch"),
                    "arguments must be quoted in YAML output, got:\n" + yaml);
        }

        @Test
        @DisplayName("curly-brace-prefixed plain string argument survives round-trip")
        void curlyBracePrefixedPlainString() {
            var steps = List.of(step("0", "1", null, "out", "print",
                    "{not-json} some text"));
            var result = roundTrip("wf", steps);
            assertEquals("{not-json} some text",
                    result.steps().get(0).actions().get(0).arguments());
        }

        @Test
        @DisplayName("bracket-prefixed yaml does not throw on re-parse with SnakeYAML")
        void bracketPrefixedYamlDoesNotThrow() {
            var steps = List.of(step("10", "11", null, "out", "print",
                    "[batch 1/4] Reading files ..."));
            String yaml = WorkflowRunner.toYamlStructured("wf", null, steps);
            assertDoesNotThrow(() -> new org.yaml.snakeyaml.Yaml().load(yaml));
        }

        @Test
        @DisplayName("multiline argument survives round-trip")
        void multiline() {
            var steps = List.of(step("0", "1", null, "str", "set",
                    "line one\nline two\nline three"));
            var result = roundTrip("wf", steps);
            String args = result.steps().get(0).actions().get(0).arguments();
            assertTrue(args.contains("line one"), "first line must be preserved");
            assertTrue(args.contains("line two"), "second line must be preserved");
        }
    }

    @Nested
    @DisplayName("Optional fields")
    class OptionalFields {

        @Test
        @DisplayName("label survives round-trip when set")
        void label() {
            var stepWithLabel = new StepDto("0", "1", "load-plugin-llm", "Load the plugin",
                    null, null, List.of(new ActionDto("loader", "loadJar", "x:y:1.0")));
            String yaml = WorkflowRunner.toYamlStructured("wf", null, List.of(stepWithLabel));
            var result = WorkflowRunner.fromYaml(yaml);
            assertEquals("load-plugin-llm", result.steps().get(0).label());
        }

        @Test
        @DisplayName("delay survives round-trip when set")
        void delay() {
            var stepWithDelay = new StepDto("0", "1", null, null,
                    500L, null, List.of(new ActionDto("out", "print", "waiting")));
            String yaml = WorkflowRunner.toYamlStructured("wf", null, List.of(stepWithDelay));
            var result = WorkflowRunner.fromYaml(yaml);
            assertEquals(500L, result.steps().get(0).delay());
        }

        @Test
        @DisplayName("multiple actions per step survive round-trip")
        void multipleActions() {
            var actions = List.of(
                    new ActionDto("promptBuilder", "clear", null),
                    new ActionDto("promptBuilder", "addWarning", "do not edit files"),
                    new ActionDto("promptBuilder", "addMessage", "${task}")
            );
            var stepWithMultiActions = new StepDto("4", "5", null, "Build the prompt", null, null, actions);
            String yaml = WorkflowRunner.toYamlStructured("wf", null, List.of(stepWithMultiActions));
            var result = WorkflowRunner.fromYaml(yaml);
            assertEquals(3, result.steps().get(0).actions().size());
            assertEquals("clear", result.steps().get(0).actions().get(0).method());
            assertEquals("addWarning", result.steps().get(0).actions().get(1).method());
            assertEquals("addMessage", result.steps().get(0).actions().get(2).method());
        }
    }

    @Nested
    @DisplayName("Catch-all step")
    class CatchAllStep {

        @Test
        @DisplayName("catch-all pattern [\"!end\", \"end\"] survives round-trip")
        void catchAll() {
            var steps = List.of(step("!end", "end", "Unexpected termination",
                    "out", "error", "Workflow ended unexpectedly."));
            var result = roundTrip("wf", steps);
            assertEquals("!end", result.steps().get(0).from());
            assertEquals("end", result.steps().get(0).to());
        }
    }
}
