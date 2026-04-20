package com.scivicslab.workfloweditor;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * On startup, registers this workflow-editor instance with the MCP Gateway.
 * The gateway URL is read from the MCP_GATEWAY_URL env var (set by service-portal)
 * or from the workflow-editor.gateway-url config property.
 */
@ApplicationScoped
public class GatewayRegistrar {

    private static final Logger logger = Logger.getLogger(GatewayRegistrar.class.getName());

    @ConfigProperty(name = "workflow-editor.gateway-url")
    Optional<String> configuredGatewayUrl;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8091")
    int httpPort;

    void onStart(@Observes StartupEvent event) {
        String gatewayUrl = configuredGatewayUrl.filter(s -> !s.isBlank())
                .orElseGet(() -> System.getenv("MCP_GATEWAY_URL"));
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            logger.fine("No gateway URL configured, skipping registration");
            return;
        }

        String name = "workflow-editor-" + httpPort;
        String url = "http://localhost:" + httpPort;
        String body = "{\"name\":\"" + name + "\",\"url\":\"" + url
                + "\",\"description\":\"Turing Workflow Editor (port " + httpPort + ")\"}";

        Thread.startVirtualThread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3)).build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(gatewayUrl + "/api/servers"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                logger.info("Registered with gateway: " + name + " -> HTTP " + response.statusCode());
            } catch (Exception e) {
                logger.warning("Failed to register with gateway: " + e.getMessage());
            }
        });
    }
}
