package com.mlplatform.controller;

import com.mlplatform.dto.NotificationDto;
import com.mlplatform.model.ImageBuild;
import com.mlplatform.model.ImageBuildStatus;
import com.mlplatform.repository.ImageBuildRepository;
import com.mlplatform.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final ImageBuildRepository imageBuildRepository;
    private final UserService userService;

    public NotificationController(ImageBuildRepository imageBuildRepository, UserService userService) {
        this.imageBuildRepository = imageBuildRepository;
        this.userService = userService;
    }

    @GetMapping
    public List<NotificationDto> getNotifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String since
    ) {
        UUID userId = userService.syncFromJwt(jwt).getId();
        Instant sinceTime = parseSince(since);

        List<ImageBuild> completedBuilds = imageBuildRepository
                .findByNotebookImage_UserIdAndStatusInAndCompletedAtAfter(
                        userId,
                        List.of(ImageBuildStatus.SUCCEEDED, ImageBuildStatus.FAILED),
                        sinceTime
                );

        return completedBuilds.stream()
                .map(build -> {
                    String type = build.getStatus() == ImageBuildStatus.SUCCEEDED
                            ? "BUILD_SUCCEEDED" : "BUILD_FAILED";
                    String imageName = build.getNotebookImage().getName();
                    String message = build.getStatus() == ImageBuildStatus.SUCCEEDED
                            ? "Image '" + imageName + "' built successfully."
                            : "Image '" + imageName + "' build failed.";
                    return new NotificationDto(
                            build.getId(),
                            type,
                            message,
                            build.getNotebookImage().getId(),
                            imageName,
                            build.getCompletedAt()
                    );
                })
                .toList();
    }

    private Instant parseSince(String since) {
        if (since != null && !since.isBlank()) {
            try {
                return Instant.parse(since);
            } catch (Exception ignored) {
                // Fall through to default
            }
        }
        return Instant.now().minusSeconds(60);
    }
}
