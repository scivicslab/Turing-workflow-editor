package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * E2E tests for S0→S1: Step Table Editor.
 * Verifies that steps and actions can be added, inserted, deleted,
 * and that step numbers are renumbered correctly after each operation.
 */
public class S01_StepTableEditorE2E {

    private final Page page;
    private final String url;

    public S01_StepTableEditorE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S01 StepTableEditor: start");
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        addStep_stepAppearsWithCorrectNumber();
        fromTo_inputsRetainValues();
        addAction_actionRowAppears();
        insertStep_fromIsAutoFilledFromPreviousTo();
        deleteStep_stepRemovedAndRenumbered();
        deleteAction_actionRowRemovedOthersRemain();

        System.out.println("S01 StepTableEditor: PASSED");
    }

    // ---- scenarios -----------------------------------------------------

    private void addStep_stepAppearsWithCorrectNumber() {
        // Reset to a known state: new workflow with 0 custom steps
        resetToEmpty();
        int before = page.locator(".step-group").count();

        // Click the first "+" Step button
        page.locator(".step-group").first().locator("button", new Locator.LocatorOptions())
                .filter(new Locator.FilterOptions().setHasText("+ Step"))
                .first().click();

        int after = page.locator(".step-group").count();
        assertEqual("addStep: step count increased", before + 1, after);

        // New step's number should reflect its position
        String newNum = page.locator(".step-group").last().locator(".step-num").textContent();
        assertTrue("addStep: step number is set", newNum.startsWith("("));

        System.out.println("  addStep_stepAppearsWithCorrectNumber: PASSED");
    }

    private void fromTo_inputsRetainValues() {
        resetToEmpty();

        Locator firstGroup = page.locator(".step-group").first();
        firstGroup.locator(".step-from").fill("start");
        firstGroup.locator(".step-to").fill("finish");

        assertEqual("fromTo: from value retained",
                "start", firstGroup.locator(".step-from").inputValue());
        assertEqual("fromTo: to value retained",
                "finish", firstGroup.locator(".step-to").inputValue());

        System.out.println("  fromTo_inputsRetainValues: PASSED");
    }

    private void addAction_actionRowAppears() {
        resetToEmpty();

        Locator firstGroup = page.locator(".step-group").first();
        int before = firstGroup.locator(".action-table tbody tr").count();

        firstGroup.locator("button", new Locator.LocatorOptions())
                .filter(new Locator.FilterOptions().setHasText("+ Action"))
                .first().click();

        int after = firstGroup.locator(".action-table tbody tr").count();
        assertEqual("addAction: action row count increased", before + 1, after);

        System.out.println("  addAction_actionRowAppears: PASSED");
    }

    private void insertStep_fromIsAutoFilledFromPreviousTo() {
        resetToEmpty();

        Locator firstGroup = page.locator(".step-group").first();
        firstGroup.locator(".step-from").fill("0");
        firstGroup.locator(".step-to").fill("99");

        // Insert a step after the first one
        firstGroup.locator("button", new Locator.LocatorOptions())
                .filter(new Locator.FilterOptions().setHasText("+ Step"))
                .first().click();

        // The newly inserted step's "from" should equal the previous step's "to"
        Locator newGroup = page.locator(".step-group").nth(1);
        String autoFrom = newGroup.locator(".step-from").inputValue();
        assertEqual("insertStep: new step's from = previous step's to", "99", autoFrom);

        System.out.println("  insertStep_fromIsAutoFilledFromPreviousTo: PASSED");
    }

    private void deleteStep_stepRemovedAndRenumbered() {
        resetToEmpty();

        // Add a second step so we have something to delete
        page.locator(".step-group").first()
                .locator("button", new Locator.LocatorOptions())
                .filter(new Locator.FilterOptions().setHasText("+ Step"))
                .first().click();
        page.locator(".step-group").first()
                .locator("button", new Locator.LocatorOptions())
                .filter(new Locator.FilterOptions().setHasText("+ Step"))
                .first().click();

        int before = page.locator(".step-group").count();
        assertTrue("deleteStep: at least 2 steps before delete", before >= 2);

        // Delete the first step
        page.locator(".step-group").first().locator(".delete-step-btn").click();

        int after = page.locator(".step-group").count();
        assertEqual("deleteStep: one step removed", before - 1, after);

        // Remaining first step should now be numbered (1)
        String num = page.locator(".step-group").first().locator(".step-num").textContent();
        assertEqual("deleteStep: first step renumbered to (1)", "(1)", num);

        System.out.println("  deleteStep_stepRemovedAndRenumbered: PASSED");
    }

    private void deleteAction_actionRowRemovedOthersRemain() {
        resetToEmpty();

        Locator firstGroup = page.locator(".step-group").first();

        // Add a second action row
        firstGroup.locator("button", new Locator.LocatorOptions())
                .filter(new Locator.FilterOptions().setHasText("+ Action"))
                .first().click();

        int before = firstGroup.locator(".action-table tbody tr").count();
        assertTrue("deleteAction: at least 2 action rows before delete", before >= 2);

        // Delete the first action row
        firstGroup.locator(".action-table tbody tr").first()
                .locator(".act-delete-btn").click();

        int after = firstGroup.locator(".action-table tbody tr").count();
        assertEqual("deleteAction: one action row removed", before - 1, after);

        System.out.println("  deleteAction_actionRowRemovedOthersRemain: PASSED");
    }

    // ---- helpers -------------------------------------------------------

    private void resetToEmpty() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");
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
