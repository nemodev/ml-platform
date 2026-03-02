package com.mlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlplatform.config.WorkspaceProfileProperties.ProfileConfig;
import com.mlplatform.config.WorkspaceProfileProperties.WorkspaceProfiles;
import com.mlplatform.dto.ComputeProfileDto;
import com.mlplatform.dto.WorkspaceMetricsDto;
import com.mlplatform.dto.WorkspaceStatusDto;
import com.mlplatform.dto.WorkspaceUrlDto;
import com.mlplatform.model.Analysis;
import com.mlplatform.model.NotebookImage;
import com.mlplatform.model.NotebookImageStatus;
import com.mlplatform.model.User;
import com.mlplatform.model.Workspace;
import com.mlplatform.model.Workspace.WorkspaceStatus;
import com.mlplatform.repository.NotebookImageRepository;
import com.mlplatform.repository.WorkspaceRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository workspaceRepository;
    private final NotebookImageRepository notebookImageRepository;
    private final UserService userService;
    private final AnalysisService analysisService;
    private final JupyterHubService jupyterHubService;
    private final Environment environment;
    private final WorkspaceProfiles workspaceProfiles;
    private final ApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Value("${workspace.default-notebook:}")
    private String defaultNotebook;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            NotebookImageRepository notebookImageRepository,
            UserService userService,
            AnalysisService analysisService,
            JupyterHubService jupyterHubService,
            Environment environment,
            WorkspaceProfiles workspaceProfiles,
            ApiClient apiClient,
            ObjectMapper objectMapper
    ) {
        this.workspaceRepository = workspaceRepository;
        this.notebookImageRepository = notebookImageRepository;
        this.userService = userService;
        this.analysisService = analysisService;
        this.jupyterHubService = jupyterHubService;
        this.environment = environment;
        this.workspaceProfiles = workspaceProfiles;
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkspaceStatusDto launchWorkspace(Jwt jwt, UUID analysisId, String profile) {
        return launchWorkspace(jwt, analysisId, profile, null);
    }

    @Transactional
    public WorkspaceStatusDto launchWorkspace(Jwt jwt, UUID analysisId, String profile, UUID notebookImageId) {
        if (isDevProfile()) {
            return mockRunningStatus("exploratory", "Dev profile mock workspace");
        }

        User user = userService.syncFromJwt(jwt);
        Analysis analysis = analysisService.resolveAnalysis(jwt, analysisId);
        String username = resolveUsername(jwt, user);
        String serverName = toServerName(analysis);

        // Resolve custom image reference if requested
        String imageReference = null;
        if (notebookImageId != null) {
            NotebookImage notebookImage = notebookImageRepository.findByIdAndUserId(notebookImageId, user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook image not found"));
            if (notebookImage.getStatus() != NotebookImageStatus.READY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notebook image is not ready. Current status: " + notebookImage.getStatus());
            }
            imageReference = notebookImage.getImageReference();
        }

        List<Workspace> active = workspaceRepository.findByAnalysisIdAndStatusIn(
                analysis.getId(),
                List.of(WorkspaceStatus.PENDING, WorkspaceStatus.RUNNING, WorkspaceStatus.IDLE)
        );
        if (!active.isEmpty()) {
            // Reconcile DB state with JupyterHub — servers may have been removed
            // (e.g. helm upgrade, cluster restart) leaving stale DB records.
            boolean stillActive = false;
            for (Workspace candidate : active) {
                JupyterHubService.ServerStatus serverStatus =
                        jupyterHubService.getNamedServerStatus(candidate.getJupyterhubUsername(), serverName);
                if (serverStatus.status() == WorkspaceStatus.STOPPED
                        || serverStatus.status() == WorkspaceStatus.FAILED) {
                    candidate.setStatus(WorkspaceStatus.STOPPED);
                    candidate.setLastActivity(Instant.now());
                    workspaceRepository.save(candidate);
                } else {
                    stillActive = true;
                }
            }
            if (stillActive) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis already has an active workspace");
            }
        }

        // Resolve profile configuration
        String normalizedProfile = normalizeProfile(profile);
        ProfileConfig profileConfig = workspaceProfiles.getById(normalizedProfile);
        if (profileConfig == null) {
            List<String> available = workspaceProfiles.getProfiles().stream()
                    .map(ProfileConfig::getId)
                    .collect(Collectors.toList());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown profile '" + normalizedProfile + "'. Available: " + available);
        }

        Workspace workspace = new Workspace();
        workspace.setUser(user);
        workspace.setAnalysis(analysis);
        workspace.setProfile(normalizedProfile);
        workspace.setStatus(WorkspaceStatus.PENDING);
        workspace.setJupyterhubUsername(username);
        workspace.setNotebookImageId(notebookImageId);
        workspace = workspaceRepository.save(workspace);

        try {
            // Build spawn options map with image and resource limits
            Map<String, Object> spawnOptions = new LinkedHashMap<>();
            if (imageReference != null) {
                spawnOptions.put("image", imageReference);
            }
            spawnOptions.put("cpu_guarantee", profileConfig.getCpuRequest());
            spawnOptions.put("cpu_limit", profileConfig.getCpuLimit());
            spawnOptions.put("mem_guarantee", profileConfig.getMemoryRequest());
            spawnOptions.put("mem_limit", profileConfig.getMemoryLimit());

            jupyterHubService.createUser(username);
            jupyterHubService.spawnNamedServer(username, serverName, spawnOptions);
            return toDto(workspace, "Workspace launch initiated");
        } catch (JupyterHubUnavailableException ex) {
            workspace.setStatus(WorkspaceStatus.FAILED);
            workspaceRepository.save(workspace);
            throw ex;
        }
    }

    @Transactional
    public WorkspaceStatusDto getWorkspaceStatus(Jwt jwt, UUID analysisId) {
        if (isDevProfile()) {
            return mockRunningStatus("exploratory", "Dev profile mock workspace");
        }

        userService.syncFromJwt(jwt);
        Analysis analysis = analysisService.resolveAnalysis(jwt, analysisId);
        Workspace workspace = workspaceRepository.findTopByAnalysisIdOrderByCreatedAtDesc(analysis.getId())
                .orElse(null);

        if (workspace == null) {
            return new WorkspaceStatusDto(null, WorkspaceStatus.STOPPED.name(), "EXPLORATORY", null, null, "No workspace", null, null);
        }

        String serverName = toServerName(analysis);
        JupyterHubService.ServerStatus serverStatus = jupyterHubService.getNamedServerStatus(
                workspace.getJupyterhubUsername(), serverName);
        workspace.setStatus(serverStatus.status());
        workspace.setStartedAt(serverStatus.startedAt());
        workspace.setLastActivity(serverStatus.lastActivity());
        workspace.setPodName(serverStatus.podName());
        workspace = workspaceRepository.save(workspace);

        return toDto(workspace, serverStatus.message());
    }

    @Transactional
    public void terminateWorkspace(Jwt jwt, UUID analysisId) {
        if (isDevProfile()) {
            return;
        }

        userService.syncFromJwt(jwt);
        Analysis analysis = analysisService.resolveAnalysis(jwt, analysisId);
        Workspace workspace = workspaceRepository.findTopByAnalysisIdOrderByCreatedAtDesc(analysis.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active workspace found"));

        if (workspace.getStatus() == WorkspaceStatus.STOPPED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No active workspace found");
        }

        String serverName = toServerName(analysis);
        jupyterHubService.stopNamedServer(workspace.getJupyterhubUsername(), serverName);
        workspace.setStatus(WorkspaceStatus.STOPPED);
        workspace.setLastActivity(Instant.now());
        workspaceRepository.save(workspace);
    }

    @Transactional
    public WorkspaceUrlDto getWorkspaceUrl(Jwt jwt, UUID analysisId, String notebookPath) {
        if (isDevProfile()) {
            if (notebookPath != null && !notebookPath.isBlank()) {
                return new WorkspaceUrlDto("http://localhost:8888/doc/tree/" + notebookPath);
            }
            String devDefault = (defaultNotebook != null && !defaultNotebook.isBlank())
                    ? defaultNotebook : null;
            if (devDefault != null) {
                return new WorkspaceUrlDto("http://localhost:8888/lab/tree/" + devDefault);
            }
            return new WorkspaceUrlDto("http://localhost:8888/lab");
        }

        WorkspaceStatusDto statusDto = getWorkspaceStatus(jwt, analysisId);
        if (!WorkspaceStatus.RUNNING.name().equals(statusDto.status())
                && !WorkspaceStatus.IDLE.name().equals(statusDto.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No running workspace");
        }

        Analysis analysis = analysisService.resolveAnalysis(jwt, analysisId);
        String username = claimOrFallback(jwt, "preferred_username", jwt.getClaimAsString("email"));
        String serverName = toServerName(analysis);
        if (notebookPath != null && !notebookPath.isBlank()) {
            return new WorkspaceUrlDto(jupyterHubService.getNamedServerDocUrl(username, serverName, notebookPath));
        }
        return new WorkspaceUrlDto(jupyterHubService.getNamedServerLabUrl(username, serverName, defaultNotebook));
    }

    public String getKernelStatus(Jwt jwt, UUID analysisId) {
        if (isDevProfile()) {
            return "idle";
        }

        userService.syncFromJwt(jwt);
        Analysis analysis = analysisService.resolveAnalysis(jwt, analysisId);
        Workspace workspace = workspaceRepository.findTopByAnalysisIdOrderByCreatedAtDesc(analysis.getId())
                .orElse(null);
        if (workspace == null || workspace.getStatus() == WorkspaceStatus.STOPPED) {
            return "disconnected";
        }

        String serverName = toServerName(analysis);
        return jupyterHubService.getKernelStatus(workspace.getJupyterhubUsername(), serverName);
    }

    public WorkspaceMetricsDto getWorkspaceMetrics(Jwt jwt, UUID analysisId) {
        if (isDevProfile()) {
            return new WorkspaceMetricsDto("exploratory", "Exploratory", "0.5", "2", 1073741824L, "4G", true);
        }

        userService.syncFromJwt(jwt);
        Analysis analysis = analysisService.resolveAnalysis(jwt, analysisId);
        Workspace workspace = workspaceRepository.findTopByAnalysisIdOrderByCreatedAtDesc(analysis.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active workspace found"));

        if (workspace.getStatus() != WorkspaceStatus.RUNNING && workspace.getStatus() != WorkspaceStatus.IDLE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No running workspace found");
        }

        String profileId = workspace.getProfile();
        ProfileConfig profileConfig = workspaceProfiles.getById(profileId);
        String profileName = profileConfig != null ? profileConfig.getName() : profileId;
        String cpuLimit = profileConfig != null ? profileConfig.getCpuLimit() : "unknown";
        String memoryLimit = profileConfig != null ? profileConfig.getMemoryLimit() : "unknown";

        String podName = workspace.getPodName();
        if (podName == null || podName.isBlank()) {
            return new WorkspaceMetricsDto(profileId, profileName, null, cpuLimit, null, memoryLimit, false);
        }

        try {
            CustomObjectsApi customApi = new CustomObjectsApi(apiClient);
            Object metricsObj = customApi.getNamespacedCustomObject(
                    "metrics.k8s.io", "v1beta1", "ml-platform", "pods", podName);
            JsonNode metricsNode = objectMapper.valueToTree(metricsObj);

            JsonNode containers = metricsNode.path("containers");
            if (!containers.isArray() || containers.isEmpty()) {
                return new WorkspaceMetricsDto(profileId, profileName, null, cpuLimit, null, memoryLimit, false);
            }

            JsonNode container = containers.get(0);
            String cpuRaw = container.path("usage").path("cpu").asText("");
            String memoryRaw = container.path("usage").path("memory").asText("");

            String cpuUsage = parseCpuToDecimal(cpuRaw);
            Long memoryUsageBytes = parseMemoryToBytes(memoryRaw);

            return new WorkspaceMetricsDto(profileId, profileName, cpuUsage, cpuLimit, memoryUsageBytes, memoryLimit, true);
        } catch (Exception ex) {
            log.warn("Failed to fetch pod metrics for {}: {}", podName, ex.getMessage());
            return new WorkspaceMetricsDto(profileId, profileName, null, cpuLimit, null, memoryLimit, false);
        }
    }

    private String parseCpuToDecimal(String cpuRaw) {
        if (cpuRaw == null || cpuRaw.isBlank()) return null;
        try {
            if (cpuRaw.endsWith("n")) {
                long nanocores = Long.parseLong(cpuRaw.substring(0, cpuRaw.length() - 1));
                return String.format("%.1f", nanocores / 1_000_000_000.0);
            }
            if (cpuRaw.endsWith("m")) {
                long millicores = Long.parseLong(cpuRaw.substring(0, cpuRaw.length() - 1));
                return String.format("%.1f", millicores / 1000.0);
            }
            return cpuRaw;
        } catch (NumberFormatException ex) {
            return cpuRaw;
        }
    }

    private Long parseMemoryToBytes(String memoryRaw) {
        if (memoryRaw == null || memoryRaw.isBlank()) return null;
        try {
            if (memoryRaw.endsWith("Ki")) {
                return Long.parseLong(memoryRaw.substring(0, memoryRaw.length() - 2)) * 1024;
            }
            if (memoryRaw.endsWith("Mi")) {
                return Long.parseLong(memoryRaw.substring(0, memoryRaw.length() - 2)) * 1024 * 1024;
            }
            if (memoryRaw.endsWith("Gi")) {
                return Long.parseLong(memoryRaw.substring(0, memoryRaw.length() - 2)) * 1024 * 1024 * 1024;
            }
            return Long.parseLong(memoryRaw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public List<ComputeProfileDto> getProfiles() {
        return workspaceProfiles.getProfiles().stream()
                .map(p -> new ComputeProfileDto(
                        p.getId(),
                        p.getName(),
                        p.getDescription(),
                        p.getCpuRequest(),
                        p.getCpuLimit(),
                        p.getMemoryRequest(),
                        p.getMemoryLimit(),
                        0,
                        p.isDefault()
                ))
                .collect(Collectors.toList());
    }

    private String toServerName(Analysis analysis) {
        return analysis.getId().toString();
    }

    private WorkspaceStatusDto toDto(Workspace workspace, String message) {
        UUID imageId = workspace.getNotebookImageId();
        String imageName = null;
        if (imageId != null) {
            imageName = notebookImageRepository.findById(imageId)
                    .map(img -> img.getName())
                    .orElse(null);
        }
        return new WorkspaceStatusDto(
                workspace.getId(),
                workspace.getStatus().name(),
                workspace.getProfile(),
                workspace.getStartedAt(),
                workspace.getLastActivity(),
                message,
                imageId,
                imageName
        );
    }

    private String resolveUsername(Jwt jwt, User user) {
        String username = claimOrFallback(jwt, "preferred_username", null);
        if (username != null && !username.isBlank()) {
            return username;
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return user.getId().toString();
    }

    private String claimOrFallback(Jwt jwt, String claim, String fallback) {
        String value = jwt.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String normalizeProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            ProfileConfig defaultProfile = workspaceProfiles.getDefault();
            return defaultProfile != null ? defaultProfile.getId() : "exploratory";
        }
        return profile.toLowerCase();
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }

    private WorkspaceStatusDto mockRunningStatus(String profile, String message) {
        return new WorkspaceStatusDto(
                UUID.nameUUIDFromBytes("dev-workspace".getBytes()),
                WorkspaceStatus.RUNNING.name(),
                profile,
                Instant.now().minusSeconds(60),
                Instant.now(),
                message,
                null,
                null
        );
    }
}
