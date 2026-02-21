package com.mlplatform.controller;

import com.mlplatform.dto.ComputeProfileDto;
import com.mlplatform.dto.WorkspaceStatusDto;
import com.mlplatform.dto.WorkspaceUrlDto;
import com.mlplatform.service.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/workspaces")
public class WorkspaceController {

    public record LaunchWorkspaceRequest(String profile) {}

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping("/profiles")
    public List<ComputeProfileDto> getProfiles() {
        return workspaceService.getProfiles();
    }

    @PostMapping
    public ResponseEntity<WorkspaceStatusDto> launch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId,
            @RequestBody(required = false) LaunchWorkspaceRequest request
    ) {
        String profile = request == null ? null : request.profile();
        WorkspaceStatusDto response = workspaceService.launchWorkspace(jwt, analysisId, profile);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping
    public WorkspaceStatusDto status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId
    ) {
        return workspaceService.getWorkspaceStatus(jwt, analysisId);
    }

    @DeleteMapping
    public ResponseEntity<Void> terminate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId
    ) {
        workspaceService.terminateWorkspace(jwt, analysisId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/kernel-status")
    public Map<String, String> kernelStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId
    ) {
        String status = workspaceService.getKernelStatus(jwt, analysisId);
        return Map.of("status", status);
    }

    @GetMapping("/url")
    public WorkspaceUrlDto url(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId,
            @RequestParam(required = false) String notebookPath) {
        return workspaceService.getWorkspaceUrl(jwt, analysisId, notebookPath);
    }
}
