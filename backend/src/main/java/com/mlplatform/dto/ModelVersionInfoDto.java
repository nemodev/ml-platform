package com.mlplatform.dto;

import java.time.Instant;

public record ModelVersionInfoDto(
        Integer version,
        String status,
        String stage,
        String artifactUri,
        String runId,
        Instant createdAt
) {}
