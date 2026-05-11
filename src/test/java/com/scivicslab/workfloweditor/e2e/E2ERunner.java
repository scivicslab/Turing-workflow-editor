package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

/**
 * E2E test runner. Connects to a running Workflow Editor instance and
 * executes all E2E test scenarios in state-machine order (S0→S1, S1→S2, ...).
 *
 * Usage:
 *   mvn test-compile exec:java -Dexec.mainClass=com.scivicslab.workfloweditor.e2e.E2ERunner \
 *       [-Deditor.url=http://localhost:8500]
 *
 * Fails with non-zero exit on the first assertion error.
 */
public class E2ERunner {

    public static void main(String[] args) {
        String url = System.getProperty("editor.url", "http://localhost:8500");
        System.out.println("E2E: connecting to " + url);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            BrowserContext ctx = browser.newContext();
            Page page = ctx.newPage();

            new S01_StepTableEditorE2E(page, url).run();
            new S12_YamlIOE2E(page, url).run();
            new BracketArgsExecutionE2E(page, url).run();
            new S23_TabManagementE2E(page, url).run();
            new S34_RunParameterDialogE2E(page, url).run();
            new S45_SseLogDisplayE2E(page, url).run();
            new S56_ActorTreeBrowserE2E(page, url).run();
            new S67_ActorActionDiscoveryE2E(page, url).run();

            System.out.println("E2E: ALL PASSED");
        } catch (AssertionError | RuntimeException e) {
            System.err.println("E2E FAILED: " + e.getMessage());
            System.exit(1);
        }
    }
}
