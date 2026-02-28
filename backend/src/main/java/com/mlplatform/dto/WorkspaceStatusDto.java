package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceStatusDto(
        UUID id,
        String status,
        String profile,
        Instant startedAt,
        Instant lastActivity,
        String message,
        UUID notebookImageId,
        String notebookImageName
) {
}
