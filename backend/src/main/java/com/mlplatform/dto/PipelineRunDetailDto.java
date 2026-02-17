package com.mlplatform.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PipelineRunDetailDto(
        UUID id,
        String notebookName,
        String status,
        boolean enableSpark,
        Map<String, String> parameters,
        String inputPath,
        String outputPath,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}
