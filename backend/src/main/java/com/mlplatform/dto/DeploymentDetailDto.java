package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record DeploymentDetailDto(
        UUID id,
        String modelName,
        Integer modelVersion,
        String endpointName,
        String status,
        String inferenceUrl,
        String storageUri,
        String errorMessage,
        Instant createdAt,
        Instant readyAt
) {}
