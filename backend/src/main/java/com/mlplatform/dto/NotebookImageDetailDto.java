package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record NotebookImageDetailDto(
        UUID id,
        String name,
        String pythonVersion,
        int packageCount,
        String status,
        String imageReference,
        String packages,
        String extraPipIndexUrl,
        String errorMessage,
        ImageBuildDto latestBuild,
        Instant createdAt,
        Instant updatedAt
) {
}
