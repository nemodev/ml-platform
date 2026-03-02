package com.mlplatform.dto;

public record WorkspaceMetricsDto(
        String profileId,
        String profileName,
        String cpuUsage,
        String cpuLimit,
        Long memoryUsageBytes,
        String memoryLimit,
        boolean metricsAvailable
) {
}
