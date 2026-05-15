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

            // Clear localStorage and reset server state before starting
            page.navigate(url);
            page.evaluate("() => localStorage.clear()");
            resetServerState(url);

            new S01_StepTableEditorE2E(page, url).run();
            new S12_YamlIOE2E(page, url).run();
            new BracketArgsExecutionE2E(page, url).run();
            new S23_TabManagementE2E(page, url).run();
            new S34_RunParameterDialogE2E(page, url).run();
            new S45_SseLogDisplayE2E(page, url).run();
            new S56_ActorTreeBrowserE2E(page, url).run();
            new S67_ActorActionDiscoveryE2E(page, url).run();
            new S78_CatalogImportE2E(page, url).run();

            System.out.println("E2E: ALL PASSED");
        } catch (AssertionError | RuntimeException e) {

            System.err.println("E2E FAILED: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void resetServerState(String baseUrl) {
        try {
            var client = java.net.http.HttpClient.newHttpClient();

            // Import a clean "workflow" to ensure it exists
            String yaml = "name: workflow\nsteps:\n- states: [\"0\",\"1\"]\n  actions:\n  - actor: out\n    method: print\n    arguments: init\n";
            client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/yaml/import"))
                    .header("Content-Type", "text/plain")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(yaml))
                    .build(), java.net.http.HttpResponse.BodyHandlers.discarding());

            // Get the current tab list
            var listResp = client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/tabs"))
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());

            // Parse tab names with simple string extraction and delete all except "workflow"
            String body = listResp.body();
            var tabsStart = body.indexOf("[");
            var tabsEnd = body.indexOf("]");
            if (tabsStart >= 0 && tabsEnd > tabsStart) {
                String tabsJson = body.substring(tabsStart + 1, tabsEnd);
                for (String token : tabsJson.split(",")) {
                    String tabName = token.trim().replaceAll("^\"|\"$", "");
                    if (!tabName.isEmpty() && !tabName.equals("workflow")) {
                        client.send(java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(baseUrl + "/api/tabs/" +
                                        java.net.URLEncoder.encode(tabName, java.nio.charset.StandardCharsets.UTF_8)))
                                .DELETE().build(), java.net.http.HttpResponse.BodyHandlers.discarding());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("E2E: server state reset warning: " + e.getMessage());
        }
    }
}
