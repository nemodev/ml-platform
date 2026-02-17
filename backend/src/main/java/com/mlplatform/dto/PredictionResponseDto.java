package com.mlplatform.dto;

import java.util.List;
import java.util.Map;

public record PredictionResponseDto(
        String modelName,
        String modelVersion,
        List<Map<String, Object>> outputs
) {}
