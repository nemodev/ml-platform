package com.mlplatform.dto;

import java.time.Instant;

public record NotebookInfoDto(
        String name,
        String path,
        Instant lastModified,
        Long sizeBytes
) {}
