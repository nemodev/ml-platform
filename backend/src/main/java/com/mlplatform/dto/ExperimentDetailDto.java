package com.mlplatform.dto;

import java.time.Instant;
import java.util.List;

public record ExperimentDetailDto(
        String experimentId,
        String name,
        String artifactLocation,
        String lifecycleStage,
        Instant creationTime,
        Instant lastUpdateTime,
        List<RunInfoDto> runs
) {
}
