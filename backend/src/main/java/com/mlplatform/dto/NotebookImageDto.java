package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record NotebookImageDto(
        UUID id,
        String name,
        String pythonVersion,
        int packageCount,
        String status,
        String imageReference,
        Instant createdAt,
        Instant updatedAt
) {
}
