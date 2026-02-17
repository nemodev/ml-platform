package com.mlplatform.service;

import com.mlplatform.dto.ComputeProfileDto;
import com.mlplatform.dto.WorkspaceStatusDto;
import com.mlplatform.dto.WorkspaceUrlDto;
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
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final UserService userService;
    private final JupyterHubService jupyterHubService;
    private final Environment environment;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            UserService userService,
            JupyterHubService jupyterHubService,
            Environment environment
    ) {
        this.workspaceRepository = workspaceRepository;
        this.userService = userService;
        this.jupyterHubService = jupyterHubService;
        this.environment = environment;
    }

    @Transactional
    public WorkspaceStatusDto launchWorkspace(Jwt jwt, String profile) {
        if (isDevProfile()) {
            return mockRunningStatus("exploratory", "Dev profile mock workspace");
        }

        User user = userService.syncFromJwt(jwt);
        String username = resolveUsername(jwt, user);

        List<Workspace> active = workspaceRepository.findByUserIdAndStatusIn(
                user.getId(),
                List.of(WorkspaceStatus.PENDING, WorkspaceStatus.RUNNING, WorkspaceStatus.IDLE)
        );
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already has an active workspace");
        }

        Workspace workspace = new Workspace();
        workspace.setUser(user);
        workspace.setProfile(normalizeProfile(profile));
        workspace.setStatus(WorkspaceStatus.PENDING);
        workspace.setJupyterhubUsername(username);
        workspace = workspaceRepository.save(workspace);

        try {
            jupyterHubService.createUser(username);
            jupyterHubService.spawnServer(username);
            return toDto(workspace, "Workspace launch initiated");
        } catch (JupyterHubUnavailableException ex) {
            workspace.setStatus(WorkspaceStatus.FAILED);
            workspaceRepository.save(workspace);
            throw ex;
        }
    }

    @Transactional
    public WorkspaceStatusDto getWorkspaceStatus(Jwt jwt) {
        if (isDevProfile()) {
            return mockRunningStatus("exploratory", "Dev profile mock workspace");
        }

        User user = userService.syncFromJwt(jwt);
        Workspace workspace = workspaceRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElse(null);

        if (workspace == null) {
            return new WorkspaceStatusDto(null, WorkspaceStatus.STOPPED.name(), "EXPLORATORY", null, null, "No workspace");
        }

        JupyterHubService.ServerStatus serverStatus = jupyterHubService.getServerStatus(workspace.getJupyterhubUsername());
        workspace.setStatus(serverStatus.status());
        workspace.setStartedAt(serverStatus.startedAt());
        workspace.setLastActivity(serverStatus.lastActivity());
        workspace.setPodName(serverStatus.podName());
        workspace = workspaceRepository.save(workspace);

        return toDto(workspace, serverStatus.message());
    }

    @Transactional
    public void terminateWorkspace(Jwt jwt) {
        if (isDevProfile()) {
            return;
        }

        User user = userService.syncFromJwt(jwt);
        Workspace workspace = workspaceRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active workspace found"));

        if (workspace.getStatus() == WorkspaceStatus.STOPPED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No active workspace found");
        }

        jupyterHubService.stopServer(workspace.getJupyterhubUsername());
        workspace.setStatus(WorkspaceStatus.STOPPED);
        workspace.setLastActivity(Instant.now());
        workspaceRepository.save(workspace);
    }

    @Transactional
    public WorkspaceUrlDto getWorkspaceUrl(Jwt jwt) {
        if (isDevProfile()) {
            return new WorkspaceUrlDto("https://jupyter.org/try-jupyter/lab/");
        }

        WorkspaceStatusDto statusDto = getWorkspaceStatus(jwt);
        if (!WorkspaceStatus.RUNNING.name().equals(statusDto.status())
                && !WorkspaceStatus.IDLE.name().equals(statusDto.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No running workspace");
        }

        String username = claimOrFallback(jwt, "preferred_username", jwt.getClaimAsString("email"));
        return new WorkspaceUrlDto(jupyterHubService.getLabUrl(username));
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
