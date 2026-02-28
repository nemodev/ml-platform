package com.mlplatform.dto;

public record CreateNotebookImageRequest(
        String name,
        String pythonVersion,
        String packages,
        String extraPipIndexUrl
) {
}
