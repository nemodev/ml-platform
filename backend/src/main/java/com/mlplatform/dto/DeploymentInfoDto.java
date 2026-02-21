package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record DeploymentInfoDto(
        UUID id,
        String modelName,
        Integer modelVersion,
        String endpointName,
        String inferenceUrl,
        String status,
        Instant createdAt,
        Instant readyAt
) {}
