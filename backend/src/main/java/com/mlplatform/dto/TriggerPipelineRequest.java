package com.mlplatform.dto;

import java.util.Map;

public record TriggerPipelineRequest(
        String notebookPath,
        Map<String, String> parameters,
        Boolean enableSpark
) {}
