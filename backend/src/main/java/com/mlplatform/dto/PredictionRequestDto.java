package com.mlplatform.dto;

import java.util.List;
import java.util.Map;

public record PredictionRequestDto(
        List<Map<String, Object>> inputs
) {}
