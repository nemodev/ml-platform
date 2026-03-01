package com.mlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlplatform.config.JupyterHubConfig.JupyterHubProperties;
import com.mlplatform.dto.StartStreamlitRequestDto;
import com.mlplatform.dto.StreamlitFileDto;
import com.mlplatform.dto.StreamlitFileListDto;
import com.mlplatform.dto.StreamlitStatusDto;
import com.mlplatform.dto.WorkspaceStatusDto;
import com.mlplatform.model.Analysis;
import com.mlplatform.model.Workspace.WorkspaceStatus;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StreamlitService {

    private static final Logger log = LoggerFactory.getLogger(StreamlitService.class);

    private final WebClient proxyWebClient;
    private final JupyterHubProperties properties;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final AnalysisService analysisService;
    private final UserService userService;
    private final Environment environment;

    public StreamlitService(
            WebClient jupyterHubProxyWebClient,
            JupyterHubProperties properties,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            AnalysisService analysisService,
            UserService userService,
            Environment environment
    ) {
        this.proxyWebClient = jupyterHubProxyWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.analysisService = analysisService;
        this.userService = userService;
        this.environment = environment;
    }

    public StreamlitFileListDto listFiles(Jwt jwt, java.util.UUID analysisId) {
        if (isDevProfile()) {
            return new StreamlitFileListDto(List.of(
                    new StreamlitFileDto("sample_dashboard.py", "visualize/sample_dashboard.py", null)
            ));
        }

        WorkspaceContext ctx = resolveWorkspace(jwt, analysisId);
        try {
            String body = proxyWebClient.get()
                    .uri("/user/{username}/{serverName}/api/streamlit/files", ctx.username, ctx.serverName)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(body);
            JsonNode filesNode = root.path("files");
            List<StreamlitFileDto> files = new ArrayList<>();
            if (filesNode.isArray()) {
                for (JsonNode f : filesNode) {
                    files.add(new StreamlitFileDto(
                            f.path("name").asText(null),
                            f.path("path").asText(null),
                            f.path("lastModified").asText(null)
                    ));
                }
            }
            return new StreamlitFileListDto(files);
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("Notebook server is unreachable", ex);
        } catch (WebClientResponseException ex) {
            log.warn("Failed to list Streamlit files: {}", ex.getMessage());
            throw new ResponseStatusException(ex.getStatusCode(), "Failed to list Streamlit files");
        } catch (Exception ex) {
            throw new JupyterHubUnavailableException("Failed to list Streamlit files", ex);
        }
    }

    public StreamlitStatusDto startApp(Jwt jwt, java.util.UUID analysisId, StartStreamlitRequestDto request) {
        if (isDevProfile()) {
            return new StreamlitStatusDto("running", request.filePath(),
                    "/user/user1/mock-server/proxy/8501/", null);
        }

        WorkspaceContext ctx = resolveWorkspace(jwt, analysisId);
        try {
            String requestBody = objectMapper.writeValueAsString(
                    java.util.Map.of("filePath", request.filePath()));

            String body = proxyWebClient.post()
                    .uri("/user/{username}/{serverName}/api/streamlit/start", ctx.username, ctx.serverName)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(body);
            int port = root.path("port").asInt(8501);
            String proxyUrl = "/user/" + ctx.username + "/" + ctx.serverName + "/proxy/" + port + "/";

            return new StreamlitStatusDto(
                    root.path("status").asText("starting"),
                    root.path("filePath").asText(null),
                    proxyUrl,
                    root.path("errorMessage").asText(null)
            );
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("Notebook server is unreachable", ex);
        } catch (WebClientResponseException ex) {
            log.warn("Failed to start Streamlit: {}", ex.getMessage());
            throw new ResponseStatusException(ex.getStatusCode(), "Failed to start Streamlit app");
        } catch (Exception ex) {
            throw new JupyterHubUnavailableException("Failed to start Streamlit app", ex);
        }
    }

    public StreamlitStatusDto stopApp(Jwt jwt, java.util.UUID analysisId) {
        if (isDevProfile()) {
            return new StreamlitStatusDto("stopped", null, null, null);
        }

        WorkspaceContext ctx = resolveWorkspace(jwt, analysisId);
        try {
            String body = proxyWebClient.post()
                    .uri("/user/{username}/{serverName}/api/streamlit/stop", ctx.username, ctx.serverName)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(body);
            return new StreamlitStatusDto(
                    root.path("status").asText("stopped"),
                    null, null, null
            );
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("Notebook server is unreachable", ex);
        } catch (Exception ex) {
            log.warn("Failed to stop Streamlit: {}", ex.getMessage());
            return new StreamlitStatusDto("stopped", null, null, null);
        }
    }

    public StreamlitStatusDto getStatus(Jwt jwt, java.util.UUID analysisId) {
        if (isDevProfile()) {
            return new StreamlitStatusDto("stopped", null, null, null);
        }

        WorkspaceContext ctx = resolveWorkspace(jwt, analysisId);
        try {
            String body = proxyWebClient.get()
                    .uri("/user/{username}/{serverName}/api/streamlit/status", ctx.username, ctx.serverName)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(body);
            String status = root.path("status").asText("stopped");
            String filePath = root.path("filePath").asText(null);
            int port = root.path("port").asInt(0);
            String proxyUrl = port > 0
                    ? "/user/" + ctx.username + "/" + ctx.serverName + "/proxy/" + port + "/"
                    : null;

            return new StreamlitStatusDto(status, filePath, proxyUrl,
                    root.path("errorMessage").asText(null));
        } catch (WebClientRequestException ex) {
            throw new JupyterHubUnavailableException("Notebook server is unreachable", ex);
        } catch (Exception ex) {
            log.warn("Failed to get Streamlit status: {}", ex.getMessage());
            return new StreamlitStatusDto("stopped", null, null, null);
        }
    }

    private WorkspaceContext resolveWorkspace(Jwt jwt, java.util.UUID analysisId) {
        userService.syncFromJwt(jwt);
        Analysis analysis = analysisService.resolveAnalysis(jwt, analysisId);

        WorkspaceStatusDto status = workspaceService.getWorkspaceStatus(jwt, analysisId);
        if (!WorkspaceStatus.RUNNING.name().equals(status.status())
                && !WorkspaceStatus.IDLE.name().equals(status.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace is not running");
        }

        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            username = jwt.getClaimAsString("email");
        }
        String serverName = analysis.getId().toString();

        return new WorkspaceContext(username, serverName);
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }

    private record WorkspaceContext(String username, String serverName) {}
}
