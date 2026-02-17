package com.mlplatform.service;

import com.mlplatform.dto.NotebookInfoDto;
import com.mlplatform.dto.PipelineOutputUrlDto;
import com.mlplatform.dto.PipelineRunDetailDto;
import com.mlplatform.dto.PipelineRunInfoDto;
import com.mlplatform.dto.TriggerPipelineRequest;
import com.mlplatform.model.PipelineRun;
import com.mlplatform.model.PipelineRun.PipelineStatus;
import com.mlplatform.model.User;
import com.mlplatform.repository.PipelineRunRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineService {

    private static final String NOTEBOOK_RUNNER_DAG_ID = "notebook_runner";

    private final PipelineRunRepository pipelineRunRepository;
    private final UserService userService;
    private final JupyterHubService jupyterHubService;
    private final NotebookStorageService notebookStorageService;
    private final AirflowService airflowService;

    public PipelineService(
            PipelineRunRepository pipelineRunRepository,
            UserService userService,
            JupyterHubService jupyterHubService,
            NotebookStorageService notebookStorageService,
            AirflowService airflowService
    ) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.userService = userService;
        this.jupyterHubService = jupyterHubService;
        this.notebookStorageService = notebookStorageService;
        this.airflowService = airflowService;
    }

    @Transactional
    public PipelineRunInfoDto triggerPipeline(Jwt jwt, TriggerPipelineRequest request) {
        if (request == null || request.notebookPath() == null || request.notebookPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notebook path is required");
        }

        String notebookPath = normalizeNotebookPath(request.notebookPath());
        if (!notebookPath.toLowerCase().endsWith(".ipynb")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notebook path must reference an .ipynb file");
        }

        User user = userService.syncFromJwt(jwt);
        String username = resolveUsername(jwt, user);

        byte[] notebookBytes = jupyterHubService.getNotebookContent(username, notebookPath);
        Map<String, String> parameters = normalizeParameters(request.parameters());

        PipelineRun run = new PipelineRun();
        UUID runId = UUID.randomUUID();
        run.setId(runId);
        run.setUser(user);
        run.setNotebookName(extractNotebookName(notebookPath));
        run.setParameters(parameters);
        run.setEnableSpark(Boolean.TRUE.equals(request.enableSpark()));
        run.setStatus(PipelineStatus.PENDING);

        String inputPath = notebookStorageService.copyNotebookToMinIO(username, notebookPath, runId, notebookBytes);
        String outputPath = notebookStorageService.buildOutputPath(username, runId);
        run.setInputPath(inputPath);
        run.setOutputPath(outputPath);

        run = pipelineRunRepository.save(run);

        try {
            Map<String, Object> conf = new LinkedHashMap<>();
            conf.put("notebook_path", inputPath);
            conf.put("input_path", inputPath);
            conf.put("output_path", outputPath);
            conf.put("parameters", parameters);
            conf.put("enable_spark", run.isEnableSpark());
            conf.put("username", username);

            AirflowService.AirflowDagRun dagRun = airflowService.triggerDagRun(NOTEBOOK_RUNNER_DAG_ID, conf);
            run.setAirflowDagRunId(dagRun.dagRunId());
            applyAirflowStatus(run, dagRun);
            run = pipelineRunRepository.save(run);
            return toRunInfo(run);
        } catch (RuntimeException ex) {
            run.setStatus(PipelineStatus.FAILED);
            run.setCompletedAt(Instant.now());
            run.setErrorMessage(trimError(ex.getMessage()));
            pipelineRunRepository.save(run);
            throw ex;
        }
    }

    @Transactional
    public PipelineRunDetailDto getPipelineRun(Jwt jwt, UUID runId) {
        User user = userService.syncFromJwt(jwt);
        PipelineRun run = getOwnedRun(runId, user.getId());

        if (run.getStatus() == PipelineStatus.PENDING || run.getStatus() == PipelineStatus.RUNNING) {
            String dagRunId = run.getAirflowDagRunId();
            if (dagRunId != null && !dagRunId.isBlank()) {
                AirflowService.AirflowDagRun status = airflowService.getDagRunStatus(NOTEBOOK_RUNNER_DAG_ID, dagRunId);
                applyAirflowStatus(run, status);
                run = pipelineRunRepository.save(run);
            }
        }

        return toRunDetail(run);
    }

    @Transactional
    public List<PipelineRunInfoDto> listPipelineRuns(Jwt jwt, PipelineStatus status, int limit) {
        User user = userService.syncFromJwt(jwt);
        int maxRows = Math.min(Math.max(limit, 1), 100);
        PageRequest pageRequest = PageRequest.of(0, maxRows);
        Page<PipelineRun> page = status == null
                ? pipelineRunRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), pageRequest)
                : pipelineRunRepository.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), status, pageRequest);
        List<PipelineRun> runs = page.getContent();

        runs.stream()
                .filter(run -> run.getStatus() == PipelineStatus.PENDING || run.getStatus() == PipelineStatus.RUNNING)
                .forEach(this::refreshRunStatusSafely);

        if (status == null) {
            return runs.stream().map(this::toRunInfo).toList();
        }

        return runs.stream()
                .filter(run -> run.getStatus() == status)
                .map(this::toRunInfo)
                .toList();
    }

    @Transactional
    public PipelineOutputUrlDto getOutputUrl(Jwt jwt, UUID runId) {
        User user = userService.syncFromJwt(jwt);
        PipelineRun run = getOwnedRun(runId, user.getId());
        boolean completed = run.getStatus() == PipelineStatus.SUCCEEDED || run.getStatus() == PipelineStatus.FAILED;
        if (!completed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pipeline run has not finished yet");
        }
        if (run.getOutputPath() == null || run.getOutputPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pipeline output is not available");
        }
        return notebookStorageService.generatePresignedUrl(run.getOutputPath());
    }

    @Transactional(readOnly = true)
    public List<NotebookInfoDto> listNotebooks(Jwt jwt) {
        User user = userService.syncFromJwt(jwt);
        String username = resolveUsername(jwt, user);

        return jupyterHubService.listNotebookFiles(username).stream()
                .map(notebook -> new NotebookInfoDto(
                        notebook.name(),
                        notebook.path(),
                        notebook.lastModified(),
                        notebook.sizeBytes()
                ))
                .toList();
    }

    private PipelineRun getOwnedRun(UUID runId, UUID userId) {
        return pipelineRunRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pipeline run not found"));
    }

    private PipelineRunInfoDto toRunInfo(PipelineRun run) {
        return new PipelineRunInfoDto(
                run.getId(),
                run.getNotebookName(),
                run.getStatus().name(),
                run.isEnableSpark(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt()
        );
    }

    private PipelineRunDetailDto toRunDetail(PipelineRun run) {
        return new PipelineRunDetailDto(
                run.getId(),
                run.getNotebookName(),
                run.getStatus().name(),
                run.isEnableSpark(),
                run.getParameters(),
                run.getInputPath(),
                run.getOutputPath(),
                run.getErrorMessage(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt()
        );
    }

    private void applyAirflowStatus(PipelineRun run, AirflowService.AirflowDagRun dagRun) {
        String state = dagRun.state() == null ? "" : dagRun.state().toLowerCase();

        if (state.contains("queued") || state.contains("scheduled") || state.contains("deferred") || state.contains("none")) {
            run.setStatus(PipelineStatus.PENDING);
            if (run.getErrorMessage() == null || run.getErrorMessage().isBlank()) {
                run.setErrorMessage("Waiting for resources");
            }
            return;
        }

        if (state.contains("running")) {
            run.setStatus(PipelineStatus.RUNNING);
            run.setStartedAt(dagRun.startDate() == null ? Instant.now() : dagRun.startDate());
            run.setErrorMessage(null);
            return;
        }

        if (state.contains("success")) {
            run.setStatus(PipelineStatus.SUCCEEDED);
            if (run.getStartedAt() == null) {
                run.setStartedAt(dagRun.startDate() == null ? Instant.now() : dagRun.startDate());
            }
            run.setCompletedAt(dagRun.endDate() == null ? Instant.now() : dagRun.endDate());
            run.setErrorMessage(null);
            return;
        }

        run.setStatus(PipelineStatus.FAILED);
        if (run.getStartedAt() == null && dagRun.startDate() != null) {
            run.setStartedAt(dagRun.startDate());
        }
        run.setCompletedAt(dagRun.endDate() == null ? Instant.now() : dagRun.endDate());
        run.setErrorMessage(trimError(dagRun.note() == null ? "Pipeline execution failed" : dagRun.note()));
    }

    private void refreshRunStatusSafely(PipelineRun run) {
        String dagRunId = run.getAirflowDagRunId();
        if (dagRunId == null || dagRunId.isBlank()) {
            return;
        }
        try {
            AirflowService.AirflowDagRun status = airflowService.getDagRunStatus(NOTEBOOK_RUNNER_DAG_ID, dagRunId);
            applyAirflowStatus(run, status);
            pipelineRunRepository.save(run);
        } catch (RuntimeException ignored) {
            // Keep the last known state if Airflow is temporarily unavailable.
        }
    }

    private Map<String, String> normalizeParameters(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            sanitized.put(key.trim(), entry.getValue() == null ? "" : entry.getValue());
        }
        return Map.copyOf(sanitized);
    }

    private String normalizeNotebookPath(String value) {
        String normalized = value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notebook path is required");
        }
        return normalized;
    }

    private String extractNotebookName(String notebookPath) {
        int index = notebookPath.lastIndexOf('/');
        return index >= 0 ? notebookPath.substring(index + 1) : notebookPath;
    }

    private String resolveUsername(Jwt jwt, User user) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return user.getId().toString();
    }

    private String trimError(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
