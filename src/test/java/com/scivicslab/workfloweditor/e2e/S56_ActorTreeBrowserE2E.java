package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

/**
 * E2E tests for S5→S6: Actor Tree Browser.
 * Migrated from e2e/actor-tree.spec.js. Covers Actor Tree and Plugins browser
 * panels. Note: all sidebar buttons are inside the Sidebar dropdown menu.
 */
public class S56_ActorTreeBrowserE2E {

    private final Page page;
    private final String url;

    public S56_ActorTreeBrowserE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S56 ActorTreeBrowser: start");

        // Actor Tree
        sidebarMenuBtn_exists();
        panelOpensOnTreeBtnClick();
        fiveStandardActorsShown();
        shellActor_showsExecAction();
        interpreterActor_showsStatusLabel();
        loaderActor_showsLoadJarAndCreateChild();
        panelClosesWithXButton();

        // Plugins Browser
        pluginsPanel_opensAndShowsItems();

        // Side panel tabs
        switchingTabs_changesPanelContent();

        System.out.println("S56 ActorTreeBrowser: PASSED");
    }

    // ---- Actor Tree scenarios ------------------------------------------

    private void sidebarMenuBtn_exists() {
        page.navigate(url);
        page.waitForSelector("#sidebarMenuBtn");

        assertTrue("sidebarMenuBtn: button is visible", page.locator("#sidebarMenuBtn").isVisible());

        // Open menu and verify Actor Tree item exists with correct label
        openSidebarMenu();
        assertEqual("treeBtn: text is 'Actor Tree'",
                "Actor Tree", page.locator("#treeBtn").textContent().trim());

        System.out.println("  sidebarMenuBtn_exists: PASSED");
    }

    private void panelOpensOnTreeBtnClick() {
        page.navigate(url);

        openSidebarMenu();
        page.click("#treeBtn");
        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");

        assertTrue("panelOpensOnClick: side panel is visible",
                page.locator("#sidePanel").isVisible());

        System.out.println("  panelOpensOnTreeBtnClick: PASSED");
    }

    private void fiveStandardActorsShown() {
        page.navigate(url);
        openSidebarMenu();
        page.click("#treeBtn");

        page.waitForFunction(
                "() => document.querySelectorAll('#actorTreeBody .tree-node').length === 5",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        assertEqual("fiveActors: exactly 5 tree nodes", 5,
                page.locator("#actorTreeBody .tree-node").count());

        System.out.println("  fiveStandardActorsShown: PASSED");
    }

    private void shellActor_showsExecAction() {
        page.navigate(url);
        openSidebarMenu();
        page.click("#treeBtn");
        page.waitForSelector("#actorTreeBody .tree-node");

        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("shell"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");

        assertTrue("shellActor: data panel visible",
                page.locator("#actorDataPanel").isVisible());
        assertTrue("shellActor: exec action tag visible",
                page.locator("#actorDataContent .actor-action-tag")
                        .filter(new Locator.FilterOptions().setHasText("exec"))
                        .isVisible());

        System.out.println("  shellActor_showsExecAction: PASSED");
    }

    private void interpreterActor_showsStatusLabel() {
        page.navigate(url);
        openSidebarMenu();
        page.click("#treeBtn");
        page.waitForSelector("#actorTreeBody .tree-node");

        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("interpreter"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");

        assertTrue("interpreterActor: Status label visible",
                page.locator("#actorDataContent .actor-section-label")
                        .filter(new Locator.FilterOptions().setHasText("Status"))
                        .isVisible());

        System.out.println("  interpreterActor_showsStatusLabel: PASSED");
    }

    private void loaderActor_showsLoadJarAndCreateChild() {
        page.navigate(url);
        openSidebarMenu();
        page.click("#treeBtn");
        page.waitForSelector("#actorTreeBody .tree-node");

        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("loader"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");

        assertTrue("loaderActor: loadJar action tag visible",
                page.locator("#actorDataContent .actor-action-tag")
                        .filter(new Locator.FilterOptions().setHasText("loadJar"))
                        .isVisible());
        assertTrue("loaderActor: createChild action tag visible",
                page.locator("#actorDataContent .actor-action-tag")
                        .filter(new Locator.FilterOptions().setHasText("createChild"))
                        .isVisible());

        System.out.println("  loaderActor_showsLoadJarAndCreateChild: PASSED");
    }

    private void panelClosesWithXButton() {
        page.navigate(url);
        openSidebarMenu();
        page.click("#treeBtn");
        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");

        page.click("#sidePanelClose");
        page.waitForFunction("() => document.getElementById('sidePanel').style.display === 'none'");

        assertTrue("panelClosesWithX: side panel hidden",
                !page.locator("#sidePanel").isVisible());

        System.out.println("  panelClosesWithXButton: PASSED");
    }

    // ---- Plugins Browser scenarios -------------------------------------

    private void pluginsPanel_opensAndShowsItems() {
        page.navigate(url);
        openSidebarMenu();
        page.click("#pluginsBtn");

        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");
        page.waitForFunction("() => document.getElementById('sidePanelPlugins').style.display !== 'none'");

        // Wait for at least one browse item to appear
        page.waitForSelector("#pluginsBody .browse-item",
                new Page.WaitForSelectorOptions().setTimeout(10000));

        assertTrue("pluginsPanel: at least one browse item",
                page.locator("#pluginsBody .browse-item").count() > 0);
        assertTrue("pluginsPanel: Load button visible",
                page.locator("#pluginsBody .browse-item-actions button")
                        .filter(new Locator.FilterOptions().setHasText("Load"))
                        .first().isVisible());

        System.out.println("  pluginsPanel_opensAndShowsItems: PASSED");
    }

    // ---- Side panel tab switching -------------------------------------

    private void switchingTabs_changesPanelContent() {
        page.navigate(url);

        // Open Actors panel via sidebar menu
        openSidebarMenu();
        page.click("#treeBtn");
        page.waitForFunction("() => document.getElementById('sidePanelActors').style.display !== 'none'");

        assertTrue("switchTabs: actors panel visible after treeBtn",
                page.locator("#sidePanelActors").isVisible());

        // Switch to Plugins tab via side-tab inside the panel
        page.locator(".side-tab[data-tab='plugins']").click();
        page.waitForFunction("() => document.getElementById('sidePanelPlugins').style.display !== 'none'");

        assertTrue("switchTabs: plugins panel visible", page.locator("#sidePanelPlugins").isVisible());
        assertTrue("switchTabs: actors panel hidden", !page.locator("#sidePanelActors").isVisible());

        // Switch to Run tab
        page.locator(".side-tab[data-tab='run']").click();
        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");

        assertTrue("switchTabs: run panel visible", page.locator("#sidePanelRun").isVisible());
        assertTrue("switchTabs: plugins panel hidden", !page.locator("#sidePanelPlugins").isVisible());

        System.out.println("  switchingTabs_changesPanelContent: PASSED");
    }

    // ---- helpers -------------------------------------------------------

    private void openSidebarMenu() {
        page.click("#sidebarMenuBtn");
        page.waitForFunction("() => document.getElementById('sidebarMenu').style.display !== 'none'");
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
