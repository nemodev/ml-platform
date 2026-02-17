package com.mlplatform.controller;

import com.mlplatform.dto.DeployModelRequest;
import com.mlplatform.dto.DeploymentDetailDto;
import com.mlplatform.dto.DeploymentInfoDto;
import com.mlplatform.dto.PredictionRequestDto;
import com.mlplatform.dto.PredictionResponseDto;
import com.mlplatform.model.ModelDeployment.DeploymentStatus;
import com.mlplatform.service.ServingService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/serving/deployments")
public class ServingController {

    private final ServingService servingService;

    public ServingController(ServingService servingService) {
        this.servingService = servingService;
    }

    @PostMapping
    public ResponseEntity<DeploymentInfoDto> deployModel(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody DeployModelRequest request
    ) {
        DeploymentInfoDto deployment = servingService.deployModel(jwt, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(deployment);
    }

    @GetMapping
    public List<DeploymentInfoDto> listDeployments(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status
    ) {
        return servingService.listDeployments(jwt, parseStatus(status));
    }

    @GetMapping("/{deploymentId}")
    public DeploymentDetailDto getDeployment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deploymentId
    ) {
        return servingService.getDeployment(jwt, deploymentId);
    }

    @DeleteMapping("/{deploymentId}")
    public ResponseEntity<Void> deleteDeployment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deploymentId
    ) {
        servingService.deleteDeployment(jwt, deploymentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{deploymentId}/predict")
    public PredictionResponseDto predict(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deploymentId,
            @RequestBody PredictionRequestDto request
    ) {
        return servingService.predict(jwt, deploymentId, request);
    }

    private DeploymentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DeploymentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status filter");
        }
    }
}
