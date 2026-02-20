package com.mlplatform.controller;

import com.mlplatform.dto.ComputeProfileDto;
import com.mlplatform.dto.WorkspaceStatusDto;
import com.mlplatform.dto.WorkspaceUrlDto;
import com.mlplatform.service.WorkspaceService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
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
            @RequestBody(required = false) LaunchWorkspaceRequest request
    ) {
        String profile = request == null ? null : request.profile();
        WorkspaceStatusDto response = workspaceService.launchWorkspace(jwt, profile);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping
    public WorkspaceStatusDto status(@AuthenticationPrincipal Jwt jwt) {
        return workspaceService.getWorkspaceStatus(jwt);
    }

    @DeleteMapping
    public ResponseEntity<Void> terminate(@AuthenticationPrincipal Jwt jwt) {
        workspaceService.terminateWorkspace(jwt);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/url")
    public WorkspaceUrlDto url(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String notebookPath) {
        return workspaceService.getWorkspaceUrl(jwt, notebookPath);
    }
}
