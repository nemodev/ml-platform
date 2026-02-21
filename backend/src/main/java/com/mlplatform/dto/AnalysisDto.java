package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record AnalysisDto(
        UUID id,
        String name,
        String description,
        Instant createdAt
) {
}
