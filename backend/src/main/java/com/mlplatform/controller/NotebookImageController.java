package com.mlplatform.controller;

import com.mlplatform.dto.CreateNotebookImageRequest;
import com.mlplatform.dto.ImageBuildDetailDto;
import com.mlplatform.dto.ImageBuildDto;
import com.mlplatform.dto.NotebookImageDetailDto;
import com.mlplatform.dto.NotebookImageDto;
import com.mlplatform.dto.UpdateNotebookImageRequest;
import com.mlplatform.service.ImageBuildService;
import com.mlplatform.service.NotebookImageService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notebook-images")
public class NotebookImageController {

    private final NotebookImageService notebookImageService;
    private final ImageBuildService imageBuildService;

    public NotebookImageController(NotebookImageService notebookImageService,
                                    ImageBuildService imageBuildService) {
        this.notebookImageService = notebookImageService;
        this.imageBuildService = imageBuildService;
    }

    @PostMapping
    public ResponseEntity<NotebookImageDto> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateNotebookImageRequest request
    ) {
        NotebookImageDto dto = notebookImageService.createImage(
                jwt, request.name(), request.pythonVersion(),
                request.packages(), request.extraPipIndexUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping
    public List<NotebookImageDto> list(@AuthenticationPrincipal Jwt jwt) {
        return notebookImageService.listImages(jwt);
    }

    @GetMapping("/{imageId}")
    public NotebookImageDetailDto get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID imageId
    ) {
        return notebookImageService.getImage(jwt, imageId);
    }

    @PutMapping("/{imageId}")
    public NotebookImageDto update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID imageId,
            @RequestBody UpdateNotebookImageRequest request
    ) {
        return notebookImageService.updateImage(
                jwt, imageId, request.name(), request.pythonVersion(),
                request.packages(), request.extraPipIndexUrl());
    }

    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID imageId
    ) {
        notebookImageService.deleteImage(jwt, imageId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/python-versions")
    public List<String> pythonVersions() {
        return notebookImageService.getPythonVersions();
    }

    @PostMapping("/{imageId}/builds")
    public ResponseEntity<ImageBuildDto> triggerBuild(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID imageId
    ) {
        ImageBuildDto dto = imageBuildService.triggerBuild(jwt, imageId);
        return ResponseEntity.accepted().body(dto);
    }

    @GetMapping("/{imageId}/builds")
    public List<ImageBuildDto> listBuilds(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID imageId
    ) {
        return imageBuildService.listBuilds(jwt, imageId);
    }

    @GetMapping("/{imageId}/builds/{buildId}")
    public ImageBuildDetailDto getBuild(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID imageId,
            @PathVariable UUID buildId
    ) {
        return imageBuildService.getBuild(jwt, imageId, buildId);
    }

    @GetMapping(value = "/{imageId}/builds/{buildId}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getBuildLogs(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID imageId,
            @PathVariable UUID buildId
    ) {
        return imageBuildService.getBuildLogs(jwt, imageId, buildId);
    }
}
