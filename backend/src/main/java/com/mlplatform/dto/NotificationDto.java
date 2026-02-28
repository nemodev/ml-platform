package com.mlplatform.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        String type,
        String message,
        UUID resourceId,
        String resourceName,
        Instant timestamp
) {
}
