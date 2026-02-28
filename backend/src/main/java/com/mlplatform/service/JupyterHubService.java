package com.mlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlplatform.config.JupyterHubConfig.JupyterHubProperties;
import com.mlplatform.model.Workspace.WorkspaceStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

@Service
public class JupyterHubService {

    private static final Logger log = LoggerFactory.getLogger(JupyterHubService.class);

    public record ServerStatus(
            WorkspaceStatus status,
            Instant startedAt,
            Instant lastActivity,
            String podName,
            String message
    ) {}

    public record NotebookFileInfo(
            String name,
            String path,
            Instant lastModified,
            Long sizeBytes
    ) {}

    private final WebClient webClient;
    private final WebClient proxyWebClient;
    private final JupyterHubProperties properties;
    private final ObjectMapper objectMapper;

    public JupyterHubService(WebClient jupyterHubWebClient, WebClient jupyterHubProxyWebClient,
                             JupyterHubProperties properties, ObjectMapper objectMapper) {
        this.webClient = jupyterHubWebClient;
        this.proxyWebClient = jupyterHubProxyWebClient;
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

    public void spawnNamedServer(String username, String serverName) {
        spawnNamedServer(username, serverName, null);
    }

    public void spawnNamedServer(String username, String serverName, String imageReference) {
        if (imageReference != null && !imageReference.isBlank()) {
            try {
                String body = objectMapper.writeValueAsString(java.util.Map.of("image", imageReference));
                webClient.post()
                        .uri("/hub/api/users/{username}/servers/{serverName}", username, serverName)
                        .header("Authorization", "Bearer " + properties.getApiToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
            } catch (WebClientRequestException ex) {
                throw new JupyterHubUnavailableException("JupyterHub is unreachable", ex);
            } catch (Exception ex) {
                throw new JupyterHubUnavailableException("Failed to spawn server with custom image", ex);
            }
        } else {
            executePost("/hub/api/users/{username}/servers/{serverName}", username, serverName);
        }
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

            return parseServerNode(server);
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

    public ServerStatus getNamedServerStatus(String username, String serverName) {
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

            JsonNode server = servers.path(serverName);
            if (server.isMissingNode() || server.isEmpty()) {
                return new ServerStatus(WorkspaceStatus.STOPPED, null, null, null, "No running server");
            }

            return parseServerNode(server);
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

    public void stopNamedServer(String username, String serverName) {
        try {
            webClient.delete()
                    .uri("/hub/api/users/{username}/servers/{serverName}", username, serverName)
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
        return getLabUrl(username, null);
    }

    public String getLabUrl(String username, String defaultNotebook) {
        String base = properties.getUrl().replaceAll("/$", "") + "/user/" + username + "/lab";
        if (defaultNotebook != null && !defaultNotebook.isBlank()) {
            if (defaultNotebook.contains("..") || defaultNotebook.startsWith("/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "defaultNotebook must be a relative path without '..' segments");
            }
            return base + "/tree/" + defaultNotebook;
        }
        return base;
    }

    public String getDocUrl(String username, String notebookPath) {
        if (notebookPath == null || notebookPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notebookPath must not be blank");
        }
        if (notebookPath.contains("..") || notebookPath.startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notebookPath must be a relative path without '..' segments");
        }
        return properties.getUrl().replaceAll("/$", "") + "/user/" + username + "/doc/tree/" + notebookPath;
    }

    public String getNamedServerLabUrl(String username, String serverName, String defaultNotebook) {
        String base = "/user/" + username + "/" + serverName + "/lab";
        if (defaultNotebook != null && !defaultNotebook.isBlank()) {
            if (defaultNotebook.contains("..") || defaultNotebook.startsWith("/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "defaultNotebook must be a relative path without '..' segments");
            }
            return base + "/tree/" + defaultNotebook;
        }
        return base;
    }

    public String getNamedServerDocUrl(String username, String serverName, String notebookPath) {
        if (notebookPath == null || notebookPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notebookPath must not be blank");
        }
        if (notebookPath.contains("..") || notebookPath.startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notebookPath must be a relative path without '..' segments");
        }
        return "/user/" + username + "/" + serverName + "/doc/tree/" + notebookPath;
    }

    public String getKernelStatus(String username, String serverName) {
        try {
            String body = proxyWebClient.get()
                    .uri("/user/{username}/{serverName}/api/sessions", username, serverName)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode sessions = objectMapper.readTree(body);
            if (!sessions.isArray() || sessions.isEmpty()) {
                return "no_kernel";
            }
            for (JsonNode session : sessions) {
                String execState = session.path("kernel").path("execution_state").asText("");
                if ("busy".equals(execState)) {
                    return "busy";
                }
                if ("starting".equals(execState)) {
                    return "starting";
                }
            }
            return "idle";
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND || ex.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                return "disconnected";
            }
            return "disconnected";
        } catch (Exception ex) {
            return "disconnected";
        }
    }

    public List<NotebookFileInfo> listNotebookFiles(String username) {
        List<String> serverNames = getRunningServerNames(username);
        if (serverNames.isEmpty()) {
            return List.of();
        }

        List<NotebookFileInfo> notebooks = new ArrayList<>();
        for (String serverName : serverNames) {
            try {
                collectNotebookFiles(username, serverName, "", notebooks);
            } catch (Exception ex) {
                log.warn("Failed to list notebooks from server '{}' for user '{}': {}", serverName, username, ex.getMessage());
            }
        }
        notebooks.sort(Comparator.comparing(NotebookFileInfo::path));
        return notebooks;
    }

    public byte[] getNotebookContent(String username, String notebookPath) {
        String normalizedPath = normalizePath(notebookPath);
        List<String> serverNames = getRunningServerNames(username);
        if (serverNames.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No running JupyterHub server found for user " + username);
        }

        Exception lastException = null;
        for (String serverName : serverNames) {
            try {
                JsonNode notebookNode = fetchContents(username, serverName, normalizedPath, true, null);

                String type = notebookNode.path("type").asText("");
                if (!"notebook".equals(type) && !"file".equals(type)) {
                    continue;
                }

                JsonNode contentNode = notebookNode.path("content");
                if (contentNode.isMissingNode() || contentNode.isNull()) {
                    continue;
                }

                String format = notebookNode.path("format").asText("");
                if ("base64".equalsIgnoreCase(format) && contentNode.isTextual()) {
                    return Base64.getDecoder().decode(contentNode.asText(""));
                }

                if (contentNode.isTextual()) {
                    return contentNode.asText("").getBytes(StandardCharsets.UTF_8);
                }
                return objectMapper.writeValueAsBytes(contentNode);
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                    lastException = ex;
                    continue;
                }
                throw ex;
            } catch (Exception ex) {
                lastException = ex;
            }
        }

        if (lastException instanceof ResponseStatusException rse) {
            throw rse;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found in any running server");
    }

    private void collectNotebookFiles(String username, String serverName, String path, List<NotebookFileInfo> notebooks) {
        JsonNode root = fetchContents(username, serverName, path, true, null);
        String type = root.path("type").asText("");

        if ("directory".equals(type)) {
            JsonNode items = root.path("content");
            if (!items.isArray()) {
                return;
            }
            for (JsonNode item : items) {
                String itemType = item.path("type").asText("");
                String itemPath = item.path("path").asText(null);
                if (itemPath == null || itemPath.isBlank()) {
                    continue;
                }

                if ("directory".equals(itemType)) {
                    if (itemPath.contains(".ipynb_checkpoints")) {
                        continue;
                    }
                    collectNotebookFiles(username, serverName, itemPath, notebooks);
                    continue;
                }

                if ("notebook".equals(itemType) || itemPath.toLowerCase().endsWith(".ipynb")) {
                    notebooks.add(toNotebookInfo(item));
                }
            }
            return;
        }

        if (("notebook".equals(type) || path.toLowerCase().endsWith(".ipynb")) && !path.isBlank()) {
            notebooks.add(toNotebookInfo(root));
        }
    }

    private NotebookFileInfo toNotebookInfo(JsonNode node) {
        String name = node.path("name").asText(null);
        String path = node.path("path").asText(null);
        Instant lastModified = parseInstant(node.path("last_modified").asText(null));
        Long size = node.path("size").isNumber() ? node.path("size").asLong() : null;
        return new NotebookFileInfo(name, path, lastModified, size);
    }

    private JsonNode fetchContents(String username, String serverName, String path, boolean includeContent, String format) {
        log.debug("fetchContents called: username={}, server={}, path={}, includeContent={}", username, serverName, path, includeContent);
        String serverSegment = (serverName == null || serverName.isBlank()) ? "" : "/" + serverName;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String encodedPath = path == null || path.isBlank() ? "" : "/" + UriUtils.encodePath(path, StandardCharsets.UTF_8);
                log.debug("fetchContents attempt {}: requesting /user/{}{}/api/contents{}", attempt, username, serverSegment, encodedPath);
                String response = proxyWebClient.get()
                        .uri(uriBuilder -> {
                            var builder = uriBuilder.path("/user/{username}" + serverSegment + "/api/contents" + encodedPath);
                            if (includeContent) {
                                builder = builder.queryParam("content", 1);
                            }
                            if (format != null && !format.isBlank()) {
                                builder = builder.queryParam("format", format);
                            }
                            return builder.build(username);
                        })
                        .header("Authorization", "Bearer " + properties.getApiToken())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                log.debug("fetchContents succeeded for user={}, server={}, path={}", username, serverName, path);
                if (response == null) {
                    throw new JupyterHubUnavailableException("JupyterHub returned empty response for user " + username, new IllegalStateException("null response body"));
                }
                return objectMapper.readTree(response);
            } catch (WebClientResponseException ex) {
                log.warn("fetchContents WebClientResponseException: status={}, body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
                if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found");
                }
                throw ex;
            } catch (WebClientRequestException ex) {
                log.warn("fetchContents WebClientRequestException attempt {}: {}", attempt, ex.getMessage());
                if (attempt == 2) {
                    throw new JupyterHubUnavailableException("JupyterHub is unreachable", ex);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new JupyterHubUnavailableException("JupyterHub is unreachable", ex);
                }
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (Exception ex) {
                log.error("fetchContents unexpected exception: type={}, message={}", ex.getClass().getName(), ex.getMessage(), ex);
                throw new JupyterHubUnavailableException("Unable to read notebook metadata from JupyterHub", ex);
            }
        }
        throw new IllegalStateException("Unreachable code");
    }

    private ServerStatus parseServerNode(JsonNode server) {
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
    }

    private List<String> getRunningServerNames(String username) {
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
                return List.of();
            }

            List<String> names = new ArrayList<>();
            servers.fieldNames().forEachRemaining(name -> {
                JsonNode server = servers.path(name);
                if (server.path("ready").asBoolean(false)) {
                    names.add(name);
                }
            });
            return names;
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return List.of();
            }
            throw ex;
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("JupyterHub is unreachable", ex);
        } catch (Exception ex) {
            log.error("Failed to get running servers for user {}: {}", username, ex.getMessage());
            return List.of();
        }
    }

    private void executePost(String uri, Object... uriVariables) {
        try {
            webClient.post()
                    .uri(uri, uriVariables)
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

    private String normalizePath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
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
