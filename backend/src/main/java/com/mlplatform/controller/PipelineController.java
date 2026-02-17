package com.mlplatform.controller;

import com.mlplatform.dto.NotebookInfoDto;
import com.mlplatform.dto.PipelineOutputUrlDto;
import com.mlplatform.dto.PipelineRunDetailDto;
import com.mlplatform.dto.PipelineRunInfoDto;
import com.mlplatform.dto.TriggerPipelineRequest;
import com.mlplatform.model.PipelineRun.PipelineStatus;
import com.mlplatform.service.PipelineService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping
    public ResponseEntity<PipelineRunInfoDto> trigger(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody TriggerPipelineRequest request
    ) {
        PipelineRunInfoDto response = pipelineService.triggerPipeline(jwt, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/notebooks")
    public List<NotebookInfoDto> listNotebooks(@AuthenticationPrincipal Jwt jwt) {
        return pipelineService.listNotebooks(jwt);
    }

    @GetMapping
    public List<PipelineRunInfoDto> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit
    ) {
        PipelineStatus parsedStatus = parseStatus(status);
        return pipelineService.listPipelineRuns(jwt, parsedStatus, limit);
    }

    @GetMapping("/{runId}")
    public PipelineRunDetailDto detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID runId
    ) {
        return pipelineService.getPipelineRun(jwt, runId);
    }

    @GetMapping("/{runId}/output")
    public PipelineOutputUrlDto output(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID runId
    ) {
        return pipelineService.getOutputUrl(jwt, runId);
    }

    private PipelineStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PipelineStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status filter");
        }
    }
}
