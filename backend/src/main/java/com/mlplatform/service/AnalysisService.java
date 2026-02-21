package com.mlplatform.service;

import com.mlplatform.dto.AnalysisDto;
import com.mlplatform.dto.CreateAnalysisRequest;
import com.mlplatform.model.Analysis;
import com.mlplatform.model.User;
import com.mlplatform.model.Workspace.WorkspaceStatus;
import com.mlplatform.repository.AnalysisRepository;
import com.mlplatform.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisService {

    private final AnalysisRepository analysisRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserService userService;
    private final Environment environment;

    public AnalysisService(
            AnalysisRepository analysisRepository,
            WorkspaceRepository workspaceRepository,
            UserService userService,
            Environment environment
    ) {
        this.analysisRepository = analysisRepository;
        this.workspaceRepository = workspaceRepository;
        this.userService = userService;
        this.environment = environment;
    }

    @Transactional
    public AnalysisDto createAnalysis(Jwt jwt, CreateAnalysisRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Analysis name is required");
        }

        User user = userService.syncFromJwt(jwt);
        String name = request.name().trim();

        if (analysisRepository.findByUserIdAndName(user.getId(), name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis with this name already exists");
        }

        Analysis analysis = new Analysis();
        analysis.setUser(user);
        analysis.setName(name);
        analysis.setDescription(request.description());
        analysis = analysisRepository.save(analysis);

        return toDto(analysis);
    }

    public List<AnalysisDto> listAnalyses(Jwt jwt) {
        if (isDevProfile()) {
            return List.of(
                    new AnalysisDto(
                            UUID.nameUUIDFromBytes("dev-analysis-1".getBytes()),
                            "Iris Classification",
                            "Iris flower species classification analysis",
                            Instant.now().minusSeconds(3600)
                    ),
                    new AnalysisDto(
                            UUID.nameUUIDFromBytes("dev-analysis-2".getBytes()),
                            "Churn Prediction",
                            "Customer churn prediction model",
                            Instant.now().minusSeconds(1800)
                    )
            );
        }

        User user = userService.syncFromJwt(jwt);
        return analysisRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toDto)
                .toList();
    }

    public AnalysisDto getAnalysis(Jwt jwt, UUID analysisId) {
        if (isDevProfile()) {
            return listAnalyses(jwt).stream()
                    .filter(a -> a.id().equals(analysisId))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found"));
        }

        User user = userService.syncFromJwt(jwt);
        Analysis analysis = analysisRepository.findByIdAndUserId(analysisId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found"));
        return toDto(analysis);
    }

    public Analysis resolveAnalysis(Jwt jwt, UUID analysisId) {
        User user = userService.syncFromJwt(jwt);
        return analysisRepository.findByIdAndUserId(analysisId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found"));
    }

    @Transactional
    public void deleteAnalysis(Jwt jwt, UUID analysisId) {
        if (isDevProfile()) {
            return;
        }

        User user = userService.syncFromJwt(jwt);
        Analysis analysis = analysisRepository.findByIdAndUserId(analysisId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found"));

        List<com.mlplatform.model.Workspace> active = workspaceRepository.findByAnalysisIdAndStatusIn(
                analysis.getId(),
                List.of(WorkspaceStatus.PENDING, WorkspaceStatus.RUNNING, WorkspaceStatus.IDLE)
        );
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete analysis with an active workspace");
        }

        analysisRepository.delete(analysis);
    }

    private AnalysisDto toDto(Analysis analysis) {
        return new AnalysisDto(
                analysis.getId(),
                analysis.getName(),
                analysis.getDescription(),
                analysis.getCreatedAt()
        );
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }
}
