package com.mlplatform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mlplatform.dto.CreateExperimentRequest;
import com.mlplatform.dto.ExperimentDetailDto;
import com.mlplatform.dto.ExperimentInfoDto;
import com.mlplatform.dto.RunInfoDto;
import com.mlplatform.dto.TrackingUrlDto;
import com.mlplatform.service.MlflowService;
import com.mlplatform.service.MlflowService.MlflowExperiment;
import com.mlplatform.service.MlflowService.MlflowRun;
import com.mlplatform.service.MlflowUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class ExperimentController {

    private static final String PROXY_PREFIX = "/api/v1/mlflow-proxy";

    private final MlflowService mlflowService;
    private final RestTemplate mlflowRestTemplate;
    private final ObjectMapper objectMapper;

    public ExperimentController(MlflowService mlflowService, RestTemplate mlflowRestTemplate, ObjectMapper objectMapper) {
        this.mlflowService = mlflowService;
        this.mlflowRestTemplate = mlflowRestTemplate;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/experiments")
    public ResponseEntity<ExperimentInfoDto> createExperiment(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateExperimentRequest request
    ) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Experiment name is required");
        }

        String username = mlflowService.getUserPrefix(jwt);
        String prefixedName = mlflowService.prefixExperimentName(username, request.name().trim());
        MlflowExperiment experiment = mlflowService.createExperiment(prefixedName);
        return ResponseEntity.status(HttpStatus.CREATED).body(toExperimentInfo(experiment, username));
    }

    @GetMapping("/experiments")
    public List<ExperimentInfoDto> listExperiments(@AuthenticationPrincipal Jwt jwt) {
        String username = mlflowService.getUserPrefix(jwt);
        return mlflowService.filterByUserPrefix(mlflowService.searchExperiments(null), username).stream()
                .map(experiment -> toExperimentInfo(experiment, username))
                .toList();
    }

    @GetMapping("/experiments/tracking-url")
    public TrackingUrlDto getTrackingUrl() {
        return new TrackingUrlDto(mlflowService.getTrackingUrl());
    }

    @GetMapping("/experiments/{experimentId}")
    public ExperimentDetailDto getExperiment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String experimentId
    ) {
        String username = mlflowService.getUserPrefix(jwt);
        MlflowExperiment experiment = requireOwnedExperiment(experimentId, username);
        List<RunInfoDto> runs = mlflowService.searchRuns(experimentId).stream()
                .map(this::toRunInfo)
                .toList();

        return new ExperimentDetailDto(
                experiment.experimentId(),
                mlflowService.stripPrefix(experiment.name(), username),
                experiment.artifactLocation(),
                experiment.lifecycleStage(),
                toInstant(experiment.creationTime()),
                toInstant(experiment.lastUpdateTime()),
                runs
        );
    }

    @GetMapping("/experiments/{experimentId}/runs")
    public List<RunInfoDto> listRuns(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String experimentId
    ) {
        String username = mlflowService.getUserPrefix(jwt);
        requireOwnedExperiment(experimentId, username);
        return mlflowService.searchRuns(experimentId).stream()
                .map(this::toRunInfo)
                .toList();
    }

    @RequestMapping("/mlflow-proxy/**")
    public ResponseEntity<byte[]> proxyMlflow(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        String username = mlflowService.getUserPrefix(jwt);
        String path = extractProxyPath(request);
        String query = request.getQueryString();
        String targetUri = buildTargetUri(path, query, username);
        byte[] requestBody = maybeRewriteCreateExperimentBody(path, body, username);

        HttpHeaders headers = copyRequestHeaders(request);
        headers.set("X-ML-Platform-User", username);
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE) && requestBody != null && requestBody.length > 0) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        HttpEntity<byte[]> entity = new HttpEntity<>(requestBody, headers);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        try {
            ResponseEntity<byte[]> response = mlflowRestTemplate.exchange(targetUri, method, entity, byte[].class);
            HttpHeaders responseHeaders = filterResponseHeaders(response.getHeaders());
            return ResponseEntity.status(response.getStatusCode()).headers(responseHeaders).body(response.getBody());
        } catch (HttpStatusCodeException ex) {
            HttpHeaders responseHeaders = filterResponseHeaders(ex.getResponseHeaders());
            return ResponseEntity.status(ex.getStatusCode())
                    .headers(responseHeaders)
                    .body(ex.getResponseBodyAsByteArray());
        } catch (ResourceAccessException ex) {
            throw new MlflowUnavailableException("Experiment tracking server is unavailable", ex);
        }
    }

    private MlflowExperiment requireOwnedExperiment(String experimentId, String username) {
        MlflowExperiment experiment = mlflowService.getExperiment(experimentId);
        String expectedPrefix = username + "/";
        if (experiment.name() == null || !experiment.name().startsWith(expectedPrefix)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found");
        }
        return experiment;
    }

    private ExperimentInfoDto toExperimentInfo(MlflowExperiment experiment, String username) {
        return new ExperimentInfoDto(
                experiment.experimentId(),
                mlflowService.stripPrefix(experiment.name(), username),
                experiment.artifactLocation(),
                experiment.lifecycleStage(),
                toInstant(experiment.creationTime()),
                toInstant(experiment.lastUpdateTime())
        );
    }

    private RunInfoDto toRunInfo(MlflowRun run) {
        return new RunInfoDto(
                run.runId(),
                run.experimentId(),
                run.status(),
                toInstant(run.startTime()),
                toInstant(run.endTime()),
                run.parameters(),
                run.metrics(),
                run.artifactUri()
        );
    }

    private Instant toInstant(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        return Instant.ofEpochMilli(epochMillis);
    }

    private String extractProxyPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        int startIndex = requestUri.indexOf(PROXY_PREFIX);
        if (startIndex < 0) {
            return "/";
        }
        String path = requestUri.substring(startIndex + PROXY_PREFIX.length());
        if (path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String buildTargetUri(String path, String query, String username) {
        if (isGetByNamePath(path)) {
            MultiValueMap<String, String> params = parseQueryParams(query);
            List<String> names = params.get("experiment_name");
            if (names != null && !names.isEmpty()) {
                List<String> prefixed = new ArrayList<>();
                for (String name : names) {
                    prefixed.add(mlflowService.prefixExperimentName(username, name));
                }
                params.put("experiment_name", prefixed);
            }
            String rewrittenQuery = UriComponentsBuilder.newInstance().queryParams(params).build(true).getQuery();
            return rewrittenQuery == null || rewrittenQuery.isBlank() ? path : path + "?" + rewrittenQuery;
        }

        if (query == null || query.isBlank()) {
            return path;
        }
        return path + "?" + query;
    }

    private MultiValueMap<String, String> parseQueryParams(String query) {
        if (query == null || query.isBlank()) {
            return new LinkedMultiValueMap<>();
        }
        return UriComponentsBuilder.newInstance()
                .query(query)
                .build(true)
                .getQueryParams();
    }

    private boolean isGetByNamePath(String path) {
        return "/api/2.0/mlflow/experiments/get-by-name".equals(path);
    }

    private byte[] maybeRewriteCreateExperimentBody(String path, byte[] body, String username) {
        if (!"/api/2.0/mlflow/experiments/create".equals(path) || body == null || body.length == 0) {
            return body;
        }
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(body);
            if (node.hasNonNull("name")) {
                String name = node.path("name").asText();
                node.put("name", mlflowService.prefixExperimentName(username, name));
            }
            return objectMapper.writeValueAsBytes(node);
        } catch (IOException | ClassCastException ex) {
            return body;
        }
    }

    private HttpHeaders copyRequestHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (HttpHeaders.HOST.equalsIgnoreCase(name)
                    || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
                    || HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                headers.add(name, values.nextElement());
            }
        }
        return headers;
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders original) {
        HttpHeaders filtered = new HttpHeaders();
        if (original == null) {
            return filtered;
        }
        original.forEach((name, values) -> {
            if (HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)
                    || HttpHeaders.CONNECTION.equalsIgnoreCase(name)
                    || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                return;
            }
            filtered.put(name, values);
        });
        return filtered;
    }
}
