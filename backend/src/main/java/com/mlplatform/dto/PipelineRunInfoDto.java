package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record PipelineRunInfoDto(
        UUID id,
        String notebookName,
        String status,
        boolean enableSpark,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}
