package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record ImageBuildDetailDto(
        UUID id,
        String status,
        String progressStage,
        String imageReference,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        long elapsedSeconds,
        UUID notebookImageId,
        String notebookImageName
) {
}
