package com.scivicslab.workfloweditor.rest;

import com.scivicslab.workfloweditor.rest.WorkflowResource.WorkflowEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the S4→S5 SSE log display contract:
 * - WorkflowEvent fields are accessible
 * - Log-level OFF filter suppresses step/info but passes everything else
 * - Always-visible event types are never filtered
 *
 * Pure-Java unit tests. No Quarkus, no HTTP, no actual workflow execution.
 */
@DisplayName("S4→S5 SSE log display: WorkflowEvent structure and log-level filtering")
class WorkflowEventTest {

    // ---- WorkflowEvent record ------------------------------------------

    @Nested
    @DisplayName("WorkflowEvent fields")
    class EventFields {

        @Test
        @DisplayName("full constructor exposes all fields")
        void fullConstructor() {
            var event = new WorkflowEvent("step", "0 → 1", "1", "loadJar", "loader",
                    Map.of("key", "value"));
            assertEquals("step", event.type());
            assertEquals("0 → 1", event.message());
            assertEquals("1", event.state());
            assertEquals("loadJar", event.action());
            assertEquals("loader", event.actorName());
            assertEquals("value", event.data().get("key"));
        }

        @Test
        @DisplayName("compact constructor defaults actorName and data to null")
        void compactConstructor() {
            var event = new WorkflowEvent("output", "hello", null, null);
            assertEquals("output", event.type());
            assertEquals("hello", event.message());
            assertNull(event.actorName());
            assertNull(event.data());
        }

        @Test
        @DisplayName("actor-tree event carries actors list in data")
        void actorTreeEvent() {
            var actors = List.of(Map.of("name", "ROOT", "children", List.of()));
            var event = new WorkflowEvent("actor-tree", null, null, null, null,
                    Map.of("actors", actors));
            assertEquals("actor-tree", event.type());
            assertNull(event.message());
            assertNotNull(event.data());
            assertTrue(event.data().containsKey("actors"));
        }
    }

    // ---- Log-level filtering -------------------------------------------

    /**
     * Replicates the filtering logic from WorkflowRunner.runYaml() so it can be
     * tested without spinning up an actual workflow execution.
     */
    static Consumer<WorkflowEvent> applyLogLevelFilter(Level level, Consumer<WorkflowEvent> sink) {
        if (level == Level.OFF) {
            return event -> {
                String type = event.type();
                if (!"step".equals(type) && !"info".equals(type)) {
                    sink.accept(event);
                }
            };
        }
        return sink;
    }

    @Nested
    @DisplayName("Log-level OFF filtering")
    class LogLevelFiltering {

        private List<WorkflowEvent> capture(Level level, List<WorkflowEvent> events) {
            List<WorkflowEvent> received = new ArrayList<>();
            Consumer<WorkflowEvent> filtered = applyLogLevelFilter(level, received::add);
            events.forEach(filtered);
            return received;
        }

        @Test
        @DisplayName("Level.OFF suppresses 'step' events")
        void suppressesStep() {
            var events = List.of(new WorkflowEvent("step", "0 → 1", "1", null));
            var received = capture(Level.OFF, events);
            assertTrue(received.isEmpty());
        }

        @Test
        @DisplayName("Level.OFF suppresses 'info' events")
        void suppressesInfo() {
            var events = List.of(new WorkflowEvent("info", "Workflow started", null, null));
            var received = capture(Level.OFF, events);
            assertTrue(received.isEmpty());
        }

        @Test
        @DisplayName("Level.OFF passes 'output' events")
        void passesOutput() {
            var events = List.of(new WorkflowEvent("output", "hello", null, null));
            var received = capture(Level.OFF, events);
            assertEquals(1, received.size());
        }

        @Test
        @DisplayName("Level.OFF passes 'completed' events")
        void passesCompleted() {
            var events = List.of(new WorkflowEvent("completed", "Workflow completed", null, null));
            var received = capture(Level.OFF, events);
            assertEquals(1, received.size());
        }

        @Test
        @DisplayName("Level.OFF passes 'error' events")
        void passesError() {
            var events = List.of(new WorkflowEvent("error", "Something failed", null, null));
            var received = capture(Level.OFF, events);
            assertEquals(1, received.size());
        }

        @Test
        @DisplayName("Level.OFF passes 'warning' events")
        void passesWarning() {
            var events = List.of(new WorkflowEvent("warning", "Retrying...", null, null));
            var received = capture(Level.OFF, events);
            assertEquals(1, received.size());
        }

        @Test
        @DisplayName("Level.OFF passes 'stopped' events")
        void passesStopped() {
            var events = List.of(new WorkflowEvent("stopped", "Stopped by user", null, null));
            var received = capture(Level.OFF, events);
            assertEquals(1, received.size());
        }

        @Test
        @DisplayName("Level.OFF passes 'paused' events")
        void passesPaused() {
            var events = List.of(new WorkflowEvent("paused", "Breakpoint at state: 3", "3", null));
            var received = capture(Level.OFF, events);
            assertEquals(1, received.size());
        }

        @Test
        @DisplayName("Level.FINE passes all event types including step and info")
        void finePassesAll() {
            var events = List.of(
                    new WorkflowEvent("step", "0 → 1", "1", null),
                    new WorkflowEvent("info", "started", null, null),
                    new WorkflowEvent("output", "hello", null, null)
            );
            var received = capture(Level.FINE, events);
            assertEquals(3, received.size());
        }

        @Test
        @DisplayName("all always-visible types pass Level.OFF filter")
        void allAlwaysVisibleTypesPass() {
            Set<String> alwaysVisible = Set.of("output", "completed", "error", "warning", "stopped", "paused");
            for (String type : alwaysVisible) {
                var events = List.of(new WorkflowEvent(type, "msg", null, null));
                var received = capture(Level.OFF, events);
                assertEquals(1, received.size(), type + " should always be visible");
            }
        }
    }
}
