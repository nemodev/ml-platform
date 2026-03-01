package com.mlplatform.dto;

public record StreamlitStatusDto(
        String status,
        String filePath,
        String url,
        String errorMessage
) {}
