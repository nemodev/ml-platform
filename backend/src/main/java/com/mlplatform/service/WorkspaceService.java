package com.mlplatform.service;

import com.mlplatform.dto.ComputeProfileDto;
import com.mlplatform.dto.WorkspaceStatusDto;
import com.mlplatform.dto.WorkspaceUrlDto;
import com.mlplatform.model.Analysis;
import com.mlplatform.model.User;
import com.mlplatform.model.Workspace;
import com.mlplatform.model.Workspace.WorkspaceStatus;
import com.mlplatform.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final UserService userService;
    private final AnalysisService analysisService;
    private final JupyterHubService jupyterHubService;
    private final Environment environment;

    @Value("${workspace.default-notebook:}")
    private String defaultNotebook;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            UserService userService,
            AnalysisService analysisService,
            JupyterHubService jupyterHubService,
            Environment environment
    ) {
        this.workspaceRepository = workspaceRepository;
        this.userService = userService;
        this.analysisService = analysisService;
        this.jupyterHubService = jupyterHubService;
        this.environment = environment;
    }

    @Transactional
    public WorkspaceStatusDto launchWorkspace(Jwt jwt, UUID analysisId, String profile) {
        if (isDevProfile()) {
            return mockRunningStatus("exploratory", "Dev profile mock workspace");
        }

        User user = userService.syncFromJwt(jwt);
        Analysis analysis = analysisService.resolveAnalysis(jwt, analysisId);
        String username = resolveUsername(jwt, user);
        String serverName = toServerName(analysis);

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

        Workspace workspace = new Workspace();
        workspace.setUser(user);
        workspace.setAnalysis(analysis);
        workspace.setProfile(normalizeProfile(profile));
        workspace.setStatus(WorkspaceStatus.PENDING);
        workspace.setJupyterhubUsername(username);
        workspace = workspaceRepository.save(workspace);

        try {
            jupyterHubService.createUser(username);
            jupyterHubService.spawnNamedServer(username, serverName);
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
            return new WorkspaceStatusDto(null, WorkspaceStatus.STOPPED.name(), "EXPLORATORY", null, null, "No workspace");
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

    public List<ComputeProfileDto> getProfiles() {
        return List.of(new ComputeProfileDto(
                "exploratory",
                "Exploratory",
                "Interactive data exploration and small experiments",
                "1",
                "2",
                "2Gi",
                "4Gi",
                0
        ));
    }

    private String toServerName(Analysis analysis) {
        return analysis.getId().toString();
    }

    private WorkspaceStatusDto toDto(Workspace workspace, String message) {
        return new WorkspaceStatusDto(
                workspace.getId(),
                workspace.getStatus().name(),
                workspace.getProfile(),
                workspace.getStartedAt(),
                workspace.getLastActivity(),
                message
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
            return "EXPLORATORY";
        }
        return profile.toUpperCase();
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }

    private WorkspaceStatusDto mockRunningStatus(String profile, String message) {
        return new WorkspaceStatusDto(
                UUID.nameUUIDFromBytes("dev-workspace".getBytes()),
                WorkspaceStatus.RUNNING.name(),
                profile.toUpperCase(),
                Instant.now().minusSeconds(60),
                Instant.now(),
                message
        );
    }
}
