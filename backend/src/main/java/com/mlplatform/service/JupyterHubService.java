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

    public List<NotebookFileInfo> listNotebookFiles(String username) {
        List<NotebookFileInfo> notebooks = new ArrayList<>();
        collectNotebookFiles(username, "", notebooks);
        notebooks.sort(Comparator.comparing(NotebookFileInfo::path));
        return notebooks;
    }

    public byte[] getNotebookContent(String username, String notebookPath) {
        String normalizedPath = normalizePath(notebookPath);
        JsonNode notebookNode = fetchContents(username, normalizedPath, true, null);

        String type = notebookNode.path("type").asText("");
        if (!"notebook".equals(type) && !"file".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path does not reference a notebook file");
        }

        JsonNode contentNode = notebookNode.path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook content is unavailable");
        }

        String format = notebookNode.path("format").asText("");
        if ("base64".equalsIgnoreCase(format) && contentNode.isTextual()) {
            return Base64.getDecoder().decode(contentNode.asText(""));
        }

        try {
            if (contentNode.isTextual()) {
                return contentNode.asText("").getBytes(StandardCharsets.UTF_8);
            }
            return objectMapper.writeValueAsBytes(contentNode);
        } catch (Exception ex) {
            throw new JupyterHubUnavailableException("Unable to read notebook content", ex);
        }
    }

    private void collectNotebookFiles(String username, String path, List<NotebookFileInfo> notebooks) {
        JsonNode root = fetchContents(username, path, true, null);
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
                    collectNotebookFiles(username, itemPath, notebooks);
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

    private JsonNode fetchContents(String username, String path, boolean includeContent, String format) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String encodedPath = path == null || path.isBlank() ? "" : "/" + UriUtils.encodePath(path, StandardCharsets.UTF_8);
                String response = webClient.get()
                        .uri(uriBuilder -> {
                            var builder = uriBuilder.path("/user/{username}/api/contents" + encodedPath);
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
                return objectMapper.readTree(response);
            } catch (WebClientResponseException ex) {
                if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found");
                }
                throw ex;
            } catch (WebClientRequestException ex) {
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
                throw new JupyterHubUnavailableException("Unable to read notebook metadata from JupyterHub", ex);
            }
        }
        throw new IllegalStateException("Unreachable code");
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
