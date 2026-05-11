package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

/**
 * E2E tests for S2→S3: Tab Management.
 * Verifies creating, switching, content isolation, renaming, deleting, and
 * persisting tabs across page reloads.
 *
 * Tab names include a timestamp suffix to ensure uniqueness across test runs,
 * since the server deduplicates duplicate names with a "-1" suffix.
 */
public class S23_TabManagementE2E {

    private final Page page;
    private final String url;
    private final String suffix;

    public S23_TabManagementE2E(Page page, String url) {
        this.page = page;
        this.url = url;
        // Unique suffix per test run to avoid name collisions on the server
        this.suffix = String.valueOf(System.currentTimeMillis() % 100000);
    }

    public void run() {
        System.out.println("S23 TabManagement: start");

        createTab_newTabAppearsAndBecomesActive();
        switchTab_stepTableChangesToThatTab();
        tabContentIsolation_editsSurviveSwitchAndReturn();
        renameTab_tabNameUpdates();
        deleteActiveTab_neighborBecomesActive();
        reloadPage_tabsAndContentRestored();

        System.out.println("S23 TabManagement: PASSED");
    }

    // ---- scenarios -----------------------------------------------------

    private void createTab_newTabAppearsAndBecomesActive() {
        page.navigate(url);
        page.waitForSelector("#tabList .tab-item");

        int before = page.locator("#tabList .tab-item").count();
        String tabName = "create-" + suffix;

        acceptNextDialog(tabName);
        page.click("#addTabBtn");

        // Wait until the new tab is created AND active
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name') && " +
                "document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + tabName + "'",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        int after = page.locator("#tabList .tab-item").count();
        assertEqual("createTab: tab count increased", before + 1, after);

        String activeName = page.locator("#tabList .tab-item.active .tab-name").textContent();
        assertEqual("createTab: new tab is active", tabName, activeName);

        System.out.println("  createTab_newTabAppearsAndBecomesActive: PASSED");
    }

    private void switchTab_stepTableChangesToThatTab() {
        page.navigate(url);
        page.waitForSelector("#tabList .tab-item");

        String tabBName = "switch-b-" + suffix;

        // Create a second tab and wait for it to become active
        acceptNextDialog(tabBName);
        page.click("#addTabBtn");
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name') && " +
                "document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + tabBName + "'",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        // Add distinct content to the second tab
        Locator firstGroup = page.locator(".step-group").first();
        firstGroup.locator(".step-from").fill("switch-test");

        // Switch to the first tab (index 0)
        page.locator("#tabList .tab-item").first().locator(".tab-name").click();
        page.waitForFunction("() => document.querySelector('.tab-item').classList.contains('active')");

        // First tab's from should NOT be "switch-test"
        String fromVal = page.locator(".step-group").first().locator(".step-from").inputValue();
        assertTrue("switchTab: first tab has different content from second tab",
                !"switch-test".equals(fromVal));

        System.out.println("  switchTab_stepTableChangesToThatTab: PASSED");
    }

    private void tabContentIsolation_editsSurviveSwitchAndReturn() {
        page.navigate(url);
        page.waitForSelector("#tabList .tab-item");

        // Get the initial (first) tab name
        String tabAName = page.locator("#tabList .tab-item.active .tab-name").textContent();
        String tabBName = "iso-b-" + suffix;

        // Edit in tab A
        page.locator(".step-group").first().locator(".step-from").fill("isolation-value");

        // Create and switch to tab B
        acceptNextDialog(tabBName);
        page.click("#addTabBtn");
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name') && " +
                "document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + tabBName + "'",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        // Switch back to tab A
        Locator tabA = page.locator("#tabList .tab-item .tab-name")
                .filter(new Locator.FilterOptions().setHasText(tabAName));
        tabA.click();

        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + tabAName + "'");

        String retained = page.locator(".step-group").first().locator(".step-from").inputValue();
        assertEqual("tabContentIsolation: tab A content preserved after switch", "isolation-value", retained);

        System.out.println("  tabContentIsolation_editsSurviveSwitchAndReturn: PASSED");
    }

    private void renameTab_tabNameUpdates() {
        page.navigate(url);
        page.waitForSelector("#tabList .tab-item.active .tab-name");

        String oldName = page.locator("#tabList .tab-item.active .tab-name").textContent();
        String newTabName = "renamed-" + suffix;

        acceptNextDialog(newTabName);
        page.locator("#tabList .tab-item.active .tab-name").dblclick();

        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + newTabName + "'");

        String newName = page.locator("#tabList .tab-item.active .tab-name").textContent();
        assertEqual("renameTab: tab name updated", newTabName, newName);

        // Restore name to avoid state bleed
        acceptNextDialog(oldName);
        page.locator("#tabList .tab-item.active .tab-name").dblclick();
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + oldName + "'");

        System.out.println("  renameTab_tabNameUpdates: PASSED");
    }

    private void deleteActiveTab_neighborBecomesActive() {
        page.navigate(url);
        page.waitForSelector("#tabList .tab-item");

        String neighborTabName = "del-nb-" + suffix;

        // Ensure at least 2 tabs exist
        acceptNextDialog(neighborTabName);
        page.click("#addTabBtn");
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name') && " +
                "document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + neighborTabName + "'",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        int before = page.locator("#tabList .tab-item").count();
        assertTrue("deleteActiveTab: at least 2 tabs before delete", before >= 2);

        // Delete the currently active tab (triggers a confirm() dialog)
        acceptNextDialog("");
        page.locator("#tabList .tab-item.active .tab-close").click();

        page.waitForFunction(
                "() => document.querySelectorAll('#tabList .tab-item').length === " + (before - 1));

        int after = page.locator("#tabList .tab-item").count();
        assertEqual("deleteActiveTab: tab count decreased", before - 1, after);

        // There must still be an active tab
        int activeCount = page.locator("#tabList .tab-item.active").count();
        assertEqual("deleteActiveTab: a tab is still active", 1, activeCount);

        System.out.println("  deleteActiveTab_neighborBecomesActive: PASSED");
    }

    private void reloadPage_tabsAndContentRestored() {
        page.navigate(url);
        page.waitForSelector("#tabList .tab-item");

        String persistTabName = "persist-" + suffix;

        // Create a tab with known content
        acceptNextDialog(persistTabName);
        page.click("#addTabBtn");
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name') && " +
                "document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + persistTabName + "'",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        page.locator(".step-group").first().locator(".step-from").fill("persisted-from");

        // Trigger saveCurrentTabToServer by switching to the first tab and back.
        // The server only persists in-memory state on tab switch (PUT /api/workflow).
        String firstTabName = page.locator("#tabList .tab-item").first().locator(".tab-name").textContent();
        page.locator("#tabList .tab-item").first().locator(".tab-name").click();
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + firstTabName + "'");

        Locator persistTab = page.locator("#tabList .tab-item .tab-name")
                .filter(new Locator.FilterOptions().setHasText(persistTabName));
        persistTab.click();
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + persistTabName + "'");
        page.waitForTimeout(300);

        int tabCountBefore = page.locator("#tabList .tab-item").count();

        // Reload
        page.reload();
        page.waitForSelector("#tabList .tab-item");

        int tabCountAfter = page.locator("#tabList .tab-item").count();
        assertEqual("reloadPage: tab count restored", tabCountBefore, tabCountAfter);

        // Switch to the persisted tab
        Locator persistedTab = page.locator("#tabList .tab-item .tab-name")
                .filter(new Locator.FilterOptions().setHasText(persistTabName));
        persistedTab.click();
        page.waitForFunction(
                "() => document.querySelector('#tabList .tab-item.active .tab-name').textContent === '" + persistTabName + "'");

        String restoredFrom = page.locator(".step-group").first().locator(".step-from").inputValue();
        assertEqual("reloadPage: tab content restored", "persisted-from", restoredFrom);

        System.out.println("  reloadPage_tabsAndContentRestored: PASSED");
    }

    // ---- helpers -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void acceptNextDialog(String answer) {
        java.util.function.Consumer<Dialog>[] ref = new java.util.function.Consumer[1];
        ref[0] = dialog -> {
            page.offDialog(ref[0]);
            dialog.accept(answer);
        };
        page.onDialog(ref[0]);
    }

    private void openFileMenu() {
        page.click("#fileMenuBtn");
        page.waitForFunction("() => document.getElementById('fileMenu').style.display !== 'none'");
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
