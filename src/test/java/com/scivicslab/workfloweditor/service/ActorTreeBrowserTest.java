package com.scivicslab.workfloweditor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the S5→S6 ActorTreeBrowser contract:
 * pure-Java logic for extracting workflow file names from actor names.
 *
 * getActorTree() itself requires a live IIActorSystem and is covered by
 * integration tests. This class targets the one pure-Java piece: the
 * "subwf-{baseName}-{timestamp}-{random}" naming convention parser.
 *
 * Pure-Java unit tests. No Quarkus, no HTTP, no actor system.
 */
@DisplayName("S5→S6 Actor tree browser: actor name parsing")
class ActorTreeBrowserTest {

    @Nested
    @DisplayName("workflowFileFromActorName: subwf- naming convention")
    class WorkflowFileFromActorName {

        @Test
        @DisplayName("typical subwf name yields baseName.yaml")
        void typicalSubwf() {
            // "subwf-{baseName}-{timestamp}-{random}"
            String name = "subwf-check-status-1746543000000-4f2a";
            assertEquals("check-status.yaml", WorkflowRunner.workflowFileFromActorName(name));
        }

        @Test
        @DisplayName("hyphenated baseName is preserved intact")
        void hyphenatedBaseName() {
            String name = "subwf-install-docker-setup-1746543000000-9b1c";
            assertEquals("install-docker-setup.yaml", WorkflowRunner.workflowFileFromActorName(name));
        }

        @Test
        @DisplayName("non-subwf actor name returns null")
        void nonSubwfReturnsNull() {
            assertNull(WorkflowRunner.workflowFileFromActorName("interpreter-main"));
            assertNull(WorkflowRunner.workflowFileFromActorName("llmClient"));
            assertNull(WorkflowRunner.workflowFileFromActorName("ROOT"));
        }

        @Test
        @DisplayName("null name returns null")
        void nullReturnsNull() {
            assertNull(WorkflowRunner.workflowFileFromActorName(null));
        }

        @Test
        @DisplayName("subwf- prefix with no further dashes falls back gracefully")
        void subwfMinimal() {
            // degenerate: "subwf-onlyone" — no timestamp/random segments
            String result = WorkflowRunner.workflowFileFromActorName("subwf-onlyone");
            // falls back to rest + ".yaml"
            assertNotNull(result);
            assertTrue(result.endsWith(".yaml"));
        }
    }
}
