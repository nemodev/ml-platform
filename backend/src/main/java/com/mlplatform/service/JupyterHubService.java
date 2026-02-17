package com.mlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlplatform.config.JupyterHubConfig.JupyterHubProperties;
import com.mlplatform.model.Workspace.WorkspaceStatus;
import java.time.Instant;
import java.util.Iterator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
public class JupyterHubService {

    public record ServerStatus(
            WorkspaceStatus status,
            Instant startedAt,
            Instant lastActivity,
            String podName,
            String message
    ) {}

    private final WebClient webClient;
    private final JupyterHubProperties properties;
    private final ObjectMapper objectMapper;

    public JupyterHubService(WebClient jupyterHubWebClient, JupyterHubProperties properties, ObjectMapper objectMapper) {
        this.webClient = jupyterHubWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void createUser(String username) {
        try {
            webClient.post()
                    .uri("/hub/api/users/{username}", username)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode() != HttpStatus.CONFLICT) {
                throw ex;
            }
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("JupyterHub is unreachable", ex);
        }
    }

    public void spawnServer(String username) {
        executePost("/hub/api/users/{username}/server", username);
    }

    public ServerStatus getServerStatus(String username) {
        try {
            String body = webClient.get()
                    .uri("/hub/api/users/{username}", username)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(body);
            JsonNode servers = root.path("servers");
            if (!servers.isObject() || servers.isEmpty()) {
                return new ServerStatus(WorkspaceStatus.STOPPED, null, null, null, "No running server");
            }

            JsonNode server = servers.path("");
            if (server.isMissingNode()) {
                Iterator<JsonNode> iterator = servers.elements();
                if (iterator.hasNext()) {
                    server = iterator.next();
                }
            }

            String pending = server.path("pending").isNull() ? null : server.path("pending").asText(null);
            boolean ready = server.path("ready").asBoolean(false);
            Instant startedAt = parseInstant(server.path("started").asText(null));
            Instant lastActivity = parseInstant(server.path("last_activity").asText(null));
            String podName = server.path("state").path("pod_name").asText(null);

            if (pending != null && !pending.isBlank()) {
                return new ServerStatus(WorkspaceStatus.PENDING, startedAt, lastActivity, podName, "Server is starting");
            }
            if (ready) {
                return new ServerStatus(WorkspaceStatus.RUNNING, startedAt, lastActivity, podName, "Server is running");
            }

            return new ServerStatus(WorkspaceStatus.IDLE, startedAt, lastActivity, podName, "Server is idle");
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return new ServerStatus(WorkspaceStatus.STOPPED, null, null, null, "No running server");
            }
            throw ex;
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("JupyterHub is unreachable", ex);
        } catch (Exception ex) {
            throw new JupyterHubUnavailableException("Unable to parse JupyterHub response", ex);
        }
    }

    public void stopServer(String username) {
        try {
            webClient.delete()
                    .uri("/hub/api/users/{username}/server", username)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("JupyterHub is unreachable", ex);
        }
    }

    public String getLabUrl(String username) {
        return properties.getUrl().replaceAll("/$", "") + "/user/" + username + "/lab";
    }

    private void executePost(String uri, String username) {
        try {
            webClient.post()
                    .uri(uri, username)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("JupyterHub is unreachable", ex);
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }
}
