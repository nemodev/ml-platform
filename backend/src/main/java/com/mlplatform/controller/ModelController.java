package com.mlplatform.controller;

import com.mlplatform.dto.ModelVersionInfoDto;
import com.mlplatform.dto.RegisteredModelInfoDto;
import com.mlplatform.service.ModelRegistryService;
import com.mlplatform.service.ModelRegistryService.ModelVersionDetail;
import com.mlplatform.service.ModelRegistryService.RegisteredModel;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final ModelRegistryService modelRegistryService;

    public ModelController(ModelRegistryService modelRegistryService) {
        this.modelRegistryService = modelRegistryService;
    }

    @GetMapping
    public List<RegisteredModelInfoDto> listRegisteredModels(@AuthenticationPrincipal Jwt jwt) {
        String username = resolveUsername(jwt);
        return modelRegistryService.listRegisteredModels(username).stream()
                .map(this::toRegisteredModelInfo)
                .toList();
    }

    @GetMapping("/{modelName}/versions")
    public List<ModelVersionInfoDto> listModelVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String modelName
    ) {
        String username = resolveUsername(jwt);
        return modelRegistryService.getModelVersions(username, modelName).stream()
                .map(this::toModelVersionInfo)
                .toList();
    }

    private String resolveUsername(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        return jwt.getSubject();
    }

    private RegisteredModelInfoDto toRegisteredModelInfo(RegisteredModel model) {
        return new RegisteredModelInfoDto(
                model.name(),
                model.latestVersion(),
                model.description(),
                model.lastUpdatedAt()
        );
    }

    private ModelVersionInfoDto toModelVersionInfo(ModelVersionDetail version) {
        return new ModelVersionInfoDto(
                version.version(),
                version.status(),
                version.stage(),
                version.artifactUri(),
                version.runId(),
                version.createdAt()
        );
    }
}
