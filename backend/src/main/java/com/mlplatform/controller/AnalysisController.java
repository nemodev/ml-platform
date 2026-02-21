package com.mlplatform.controller;

import com.mlplatform.dto.AnalysisDto;
import com.mlplatform.dto.CreateAnalysisRequest;
import com.mlplatform.service.AnalysisService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyses")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping
    public ResponseEntity<AnalysisDto> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateAnalysisRequest request
    ) {
        AnalysisDto analysis = analysisService.createAnalysis(jwt, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(analysis);
    }

    @GetMapping
    public List<AnalysisDto> list(@AuthenticationPrincipal Jwt jwt) {
        return analysisService.listAnalyses(jwt);
    }

    @GetMapping("/{analysisId}")
    public AnalysisDto get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId
    ) {
        return analysisService.getAnalysis(jwt, analysisId);
    }

    @DeleteMapping("/{analysisId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID analysisId
    ) {
        analysisService.deleteAnalysis(jwt, analysisId);
        return ResponseEntity.noContent().build();
    }
}
