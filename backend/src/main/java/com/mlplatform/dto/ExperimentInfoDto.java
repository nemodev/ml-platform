package com.mlplatform.dto;

import java.time.Instant;

public record ExperimentInfoDto(
        String experimentId,
        String name,
        String artifactLocation,
        String lifecycleStage,
        Instant creationTime,
        Instant lastUpdateTime
) {
}
