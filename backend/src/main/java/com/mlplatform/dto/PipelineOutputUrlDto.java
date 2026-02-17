package com.mlplatform.dto;

import java.time.Instant;

public record PipelineOutputUrlDto(
        String url,
        Instant expiresAt
) {}
