package com.scivicslab.workfloweditor.service;

import com.scivicslab.workfloweditor.service.WorkflowRunner.ActionDto;
import com.scivicslab.workfloweditor.service.WorkflowRunner.StepDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the S2→S3 tab management contract:
 * WorkflowState tab CRUD operations, active-tab tracking, and name deduplication.
 *
 * Pure-Java unit tests. No Quarkus, no HTTP.
 */
@DisplayName("S2→S3 Tab management: WorkflowState tab operations")
class TabManagementTest {

    private WorkflowState state;

    @BeforeEach
    void setUp() {
        state = new WorkflowState();
    }

    // ---- createTab -----------------------------------------------------

    @Nested
    @DisplayName("createTab")
    class CreateTab {

        @Test
        @DisplayName("first tab becomes active automatically")
        void firstTabBecomesActive() {
            String name = state.createTab("my-wf");
            assertEquals("my-wf", name);
            assertEquals("my-wf", state.getActiveTab());
        }

        @Test
        @DisplayName("second tab does not change active tab")
        void secondTabDoesNotChangeActive() {
            state.createTab("first");
            state.createTab("second");
            assertEquals("first", state.getActiveTab());
        }

        @Test
        @DisplayName("duplicate name gets -1 suffix")
        void duplicateNameSuffix() {
            state.createTab("my-wf");
            String second = state.createTab("my-wf");
            assertEquals("my-wf-1", second);
        }

        @Test
        @DisplayName("triple duplicate gets incrementing suffix")
        void tripleDuplicate() {
            state.createTab("my-wf");
            state.createTab("my-wf");
            String third = state.createTab("my-wf");
            assertEquals("my-wf-2", third);
        }

        @Test
        @DisplayName("null name defaults to 'workflow'")
        void nullNameDefault() {
            String name = state.createTab(null);
            assertEquals("workflow", name);
        }

        @Test
        @DisplayName("blank name defaults to 'workflow'")
        void blankNameDefault() {
            String name = state.createTab("   ");
            assertEquals("workflow", name);
        }

        @Test
        @DisplayName("tab appears in listTabs after creation")
        void appearsInList() {
            state.createTab("alpha");
            state.createTab("beta");
            var tabs = state.listTabs();
            assertTrue(tabs.contains("alpha"));
            assertTrue(tabs.contains("beta"));
            assertEquals(2, tabs.size());
        }
    }

    // ---- deleteTab -----------------------------------------------------

    @Nested
    @DisplayName("deleteTab")
    class DeleteTab {

        @Test
        @DisplayName("deleting active tab switches active to first remaining tab")
        void deleteActiveSwitchesToFirst() {
            state.createTab("first");
            state.createTab("second");
            state.activateTab("first");
            state.deleteTab("first");
            assertEquals("second", state.getActiveTab());
            assertFalse(state.listTabs().contains("first"));
        }

        @Test
        @DisplayName("deleting non-active tab does not change active")
        void deleteNonActiveLeavesActive() {
            state.createTab("first");
            state.createTab("second");
            state.deleteTab("second");
            assertEquals("first", state.getActiveTab());
        }

        @Test
        @DisplayName("deleting last tab sets active to null")
        void deleteLastTabNullActive() {
            state.createTab("only");
            state.activateTab("only");
            state.deleteTab("only");
            assertNull(state.getActiveTab());
            assertTrue(state.listTabs().isEmpty());
        }

        @Test
        @DisplayName("deleting non-existent tab returns false")
        void deleteNonExistent() {
            assertFalse(state.deleteTab("does-not-exist"));
        }
    }

    // ---- activateTab ---------------------------------------------------

    @Nested
    @DisplayName("activateTab")
    class ActivateTab {

        @Test
        @DisplayName("activateTab changes active tab")
        void switchesActive() {
            state.createTab("first");
            state.createTab("second");
            state.activateTab("second");
            assertEquals("second", state.getActiveTab());
        }

        @Test
        @DisplayName("activateTab returns false for unknown tab")
        void unknownReturnsFalse() {
            assertFalse(state.activateTab("ghost"));
        }
    }

    // ---- renameTab -----------------------------------------------------

    @Nested
    @DisplayName("renameTab")
    class RenameTab {

        @Test
        @DisplayName("renamed tab has new name in list")
        void renamedAppearsInList() {
            state.createTab("old");
            state.renameTab("old", "new");
            assertTrue(state.listTabs().contains("new"));
            assertFalse(state.listTabs().contains("old"));
        }

        @Test
        @DisplayName("renaming active tab updates activeTab")
        void renamingActiveUpdatesActiveTab() {
            state.createTab("old");
            state.activateTab("old");
            state.renameTab("old", "new");
            assertEquals("new", state.getActiveTab());
        }

        @Test
        @DisplayName("renaming non-active tab does not change active")
        void renamingNonActivePreservesActive() {
            state.createTab("first");
            state.createTab("second");
            state.renameTab("second", "renamed");
            assertEquals("first", state.getActiveTab());
        }

        @Test
        @DisplayName("rename to conflicting name gets -1 suffix")
        void renameConflictSuffix() {
            state.createTab("alpha");
            state.createTab("beta");
            String actual = state.renameTab("alpha", "beta");
            assertEquals("beta-1", actual);
        }

        @Test
        @DisplayName("tab order is preserved after rename")
        void orderPreserved() {
            state.createTab("first");
            state.createTab("second");
            state.createTab("third");
            state.renameTab("second", "middle");
            var tabs = state.listTabs();
            assertEquals(List.of("first", "middle", "third"), tabs);
        }

        @Test
        @DisplayName("renaming non-existent tab returns null")
        void renamingNonExistentReturnsNull() {
            assertNull(state.renameTab("ghost", "new"));
        }
    }

    // ---- replaceAll (used by import) -----------------------------------

    @Nested
    @DisplayName("replaceAll (import)")
    class ReplaceAll {

        @Test
        @DisplayName("replaceAll creates tab if not exists and activates it")
        void createsAndActivates() {
            var steps = List.of(new StepDto("0", "end", null, null, null, null,
                    List.of(new ActionDto("out", "print", "done"))));
            state.replaceAll("imported-wf", steps, 100);
            assertEquals("imported-wf", state.getActiveTab());
            assertEquals(1, state.getSteps().size());
        }

        @Test
        @DisplayName("replaceAll updates existing tab without creating duplicate")
        void updatesExisting() {
            state.createTab("my-wf");
            var steps = List.of(new StepDto("0", "1", null, "step one", null, null,
                    List.of(new ActionDto("out", "print", "a"))));
            state.replaceAll("my-wf", steps, 50);
            assertEquals(1, state.listTabs().size());
            assertEquals(1, state.getSteps().size());
        }
    }
}
