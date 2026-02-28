package com.mlplatform.dto;

public record UpdateNotebookImageRequest(
        String name,
        String pythonVersion,
        String packages,
        String extraPipIndexUrl
) {
}
