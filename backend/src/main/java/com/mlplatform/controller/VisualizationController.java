package com.mlplatform.controller;

import com.mlplatform.dto.StartStreamlitRequestDto;
import com.mlplatform.dto.StreamlitFileListDto;
import com.mlplatform.dto.StreamlitStatusDto;
import com.mlplatform.service.StreamlitService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/visualizations")
public class VisualizationController {

    private final StreamlitService streamlitService;

    public VisualizationController(StreamlitService streamlitService) {
        this.streamlitService = streamlitService;
    }

    @GetMapping("/files")
    public StreamlitFileListDto listFiles(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId
    ) {
        return streamlitService.listFiles(jwt, analysisId);
    }

    @PostMapping("/start")
    public StreamlitStatusDto startApp(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId,
            @RequestBody StartStreamlitRequestDto request
    ) {
        return streamlitService.startApp(jwt, analysisId, request);
    }

    @PostMapping("/stop")
    public StreamlitStatusDto stopApp(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId
    ) {
        return streamlitService.stopApp(jwt, analysisId);
    }

    @GetMapping("/status")
    public StreamlitStatusDto getStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId
    ) {
        return streamlitService.getStatus(jwt, analysisId);
    }
}
