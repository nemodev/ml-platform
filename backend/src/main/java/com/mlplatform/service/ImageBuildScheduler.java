package com.mlplatform.service;

import com.mlplatform.config.NotebookImageConfig.NotebookImageProperties;
import com.mlplatform.model.ImageBuild;
import com.mlplatform.model.ImageBuildStatus;
import com.mlplatform.repository.ImageBuildRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImageBuildScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImageBuildScheduler.class);

    private final ImageBuildRepository imageBuildRepository;
    private final ImageBuildService imageBuildService;
    private final NotebookImageProperties properties;
    private final Environment environment;

    public ImageBuildScheduler(
            ImageBuildRepository imageBuildRepository,
            ImageBuildService imageBuildService,
            NotebookImageProperties properties,
            Environment environment
    ) {
        this.imageBuildRepository = imageBuildRepository;
        this.imageBuildService = imageBuildService;
        this.properties = properties;
        this.environment = environment;
    }

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void processQueue() {
        if (isDevProfile()) return;

        // Check how many builds are currently running
        long activeBuilds = imageBuildRepository.countByStatusIn(List.of(ImageBuildStatus.BUILDING));
        int maxConcurrent = properties.getBuild().getMaxConcurrentBuilds();

        if (activeBuilds >= maxConcurrent) {
            return;
        }

        // Find the next queued build (FIFO)
        imageBuildRepository.findFirstByStatusOrderByCreatedAtAsc(ImageBuildStatus.QUEUED)
                .ifPresent(build -> {
                    log.info("Promoting queued build: buildId={}", build.getId());
                    try {
                        imageBuildService.createKanikoJob(build);
                    } catch (Exception e) {
                        log.error("Failed to promote build {}: {}", build.getId(), e.getMessage());
                    }
                });
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void refreshActiveBuilds() {
        if (isDevProfile()) return;

        List<ImageBuild> activeBuilds = imageBuildRepository.findByStatusIn(
                List.of(ImageBuildStatus.BUILDING));

        for (ImageBuild build : activeBuilds) {
            try {
                imageBuildService.refreshBuildStatus(build);
            } catch (Exception e) {
                log.warn("Failed to refresh status for build {}: {}", build.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void enforceTimeouts() {
        if (isDevProfile()) return;

        int timeoutMinutes = properties.getBuild().getTimeoutMinutes();
        Instant cutoff = Instant.now().minusSeconds((long) timeoutMinutes * 60);

        List<ImageBuild> activeBuilds = imageBuildRepository.findByStatusIn(
                List.of(ImageBuildStatus.BUILDING));

        for (ImageBuild build : activeBuilds) {
            if (build.getStartedAt() != null && build.getStartedAt().isBefore(cutoff)) {
                log.warn("Build timed out: buildId={}, started={}", build.getId(), build.getStartedAt());
                imageBuildService.cancelBuild(build,
                        "Build timed out after " + timeoutMinutes + " minutes");
            }
        }
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }
}
