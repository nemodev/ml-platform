package com.mlplatform.dto;

public record DeployModelRequest(
        String modelName,
        Integer modelVersion
) {}
