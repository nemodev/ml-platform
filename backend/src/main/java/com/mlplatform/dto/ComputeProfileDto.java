package com.mlplatform.dto;

public record ComputeProfileDto(
        String id,
        String name,
        String description,
        String cpuRequest,
        String cpuLimit,
        String memoryRequest,
        String memoryLimit,
        Integer gpuLimit,
        boolean isDefault
) {
}
