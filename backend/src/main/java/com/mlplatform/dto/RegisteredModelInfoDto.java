package com.mlplatform.dto;

import java.time.Instant;

public record RegisteredModelInfoDto(
        String name,
        Integer latestVersion,
        String description,
        Instant lastUpdatedAt
) {}
