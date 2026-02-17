package com.mlplatform.dto;

import java.time.Instant;

public record HealthResponse(String status, Instant timestamp) {
}
