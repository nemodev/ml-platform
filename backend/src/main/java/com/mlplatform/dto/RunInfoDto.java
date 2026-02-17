package com.mlplatform.dto;

import java.time.Instant;
import java.util.Map;

public record RunInfoDto(
        String runId,
        String experimentId,
        String status,
        Instant startTime,
        Instant endTime,
        Map<String, String> parameters,
        Map<String, Double> metrics,
        String artifactUri
) {
}
