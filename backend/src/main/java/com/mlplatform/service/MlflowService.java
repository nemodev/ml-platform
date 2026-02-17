package com.mlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlplatform.config.MlflowConfig.MlflowProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlflowService {

    public record MlflowExperiment(
            String experimentId,
            String name,
            String artifactLocation,
            String lifecycleStage,
            Long creationTime,
            Long lastUpdateTime
    ) {}

    public record MlflowRun(
            String runId,
            String experimentId,
            String status,
            Long startTime,
            Long endTime,
            Map<String, String> parameters,
            Map<String, Double> metrics,
            String artifactUri
    ) {}

    private final RestTemplate mlflowRestTemplate;
    private final MlflowProperties properties;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public MlflowService(
            RestTemplate mlflowRestTemplate,
            MlflowProperties properties,
            ObjectMapper objectMapper,
            Environment environment
    ) {
        this.mlflowRestTemplate = mlflowRestTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    public MlflowExperiment createExperiment(String prefixedName) {
        if (isDevProfile()) {
            return new MlflowExperiment(
                    "dev-" + Instant.now().toEpochMilli(),
                    prefixedName,
                    "s3://ml-platform-mlflow/artifacts/dev",
                    "active",
                    Instant.now().toEpochMilli(),
                    Instant.now().toEpochMilli()
            );
        }

        Map<String, Object> payload = Map.of("name", prefixedName);
        JsonNode body = postForNode("/api/2.0/mlflow/experiments/create", payload, true);
        String experimentId = text(body.path("experiment_id"));
        if (experimentId == null || experimentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MLflow did not return experiment id");
        }
        return getExperiment(experimentId);
    }

    public List<MlflowExperiment> searchExperiments(String nameFilter) {
        if (isDevProfile()) {
            List<MlflowExperiment> experiments = new ArrayList<>();
            experiments.add(new MlflowExperiment(
                    "101",
                    "dev-user/iris-classification",
                    "s3://ml-platform-mlflow/artifacts/101",
                    "active",
                    Instant.now().minusSeconds(3600).toEpochMilli(),
                    Instant.now().minusSeconds(600).toEpochMilli()
            ));
            experiments.add(new MlflowExperiment(
                    "102",
                    "dev-user/churn-model",
                    "s3://ml-platform-mlflow/artifacts/102",
                    "active",
                    Instant.now().minusSeconds(1800).toEpochMilli(),
                    Instant.now().minusSeconds(120).toEpochMilli()
            ));
            if (nameFilter == null || nameFilter.isBlank()) {
                return experiments;
            }
            String lowerFilter = nameFilter.toLowerCase(Locale.ROOT);
            return experiments.stream()
                    .filter(exp -> exp.name() != null && exp.name().toLowerCase(Locale.ROOT).contains(lowerFilter))
                    .toList();
        }

        List<MlflowExperiment> experiments = new ArrayList<>();
        String pageToken = null;

        do {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("max_results", 1000);
            if (pageToken != null && !pageToken.isBlank()) {
                payload.put("page_token", pageToken);
            }

            JsonNode body = postForNode("/api/2.0/mlflow/experiments/search", payload, false);
            JsonNode items = body.path("experiments");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    experiments.add(toExperiment(item));
                }
            }
            pageToken = text(body.path("next_page_token"));
        } while (pageToken != null && !pageToken.isBlank());

        if (nameFilter == null || nameFilter.isBlank()) {
            return experiments;
        }
        String lowerFilter = nameFilter.toLowerCase(Locale.ROOT);
        return experiments.stream()
                .filter(exp -> exp.name() != null && exp.name().toLowerCase(Locale.ROOT).contains(lowerFilter))
                .toList();
    }

    public MlflowExperiment getExperiment(String experimentId) {
        if (isDevProfile()) {
            return searchExperiments(null).stream()
                    .filter(exp -> exp.experimentId().equals(experimentId))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        }

        try {
            String path = "/api/2.0/mlflow/experiments/get?experiment_id=" + experimentId;
            String response = mlflowRestTemplate.getForObject(path, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode experiment = root.path("experiment");
            if (!experiment.isObject() || experiment.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found");
            }
            return toExperiment(experiment);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found");
            }
            throw toResponseStatus(ex);
        } catch (ResourceAccessException ex) {
            throw new MlflowUnavailableException("Experiment tracking server is unavailable", ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MlflowUnavailableException("Experiment tracking server is unavailable", ex);
        }
    }

    public List<MlflowRun> searchRuns(String experimentId) {
        if (isDevProfile()) {
            if (!"101".equals(experimentId) && !"102".equals(experimentId)) {
                return List.of();
            }
            return List.of(
                    new MlflowRun(
                            "11111111-1111-1111-1111-111111111111",
                            experimentId,
                            "FINISHED",
                            Instant.now().minusSeconds(300).toEpochMilli(),
                            Instant.now().minusSeconds(240).toEpochMilli(),
                            Map.of("n_estimators", "100"),
                            Map.of("accuracy", 0.95),
                            "s3://ml-platform-mlflow/artifacts/" + experimentId + "/11111111"
                    ),
                    new MlflowRun(
                            "22222222-2222-2222-2222-222222222222",
                            experimentId,
                            "FINISHED",
                            Instant.now().minusSeconds(200).toEpochMilli(),
                            Instant.now().minusSeconds(160).toEpochMilli(),
                            Map.of("n_estimators", "200"),
                            Map.of("accuracy", 0.97),
                            "s3://ml-platform-mlflow/artifacts/" + experimentId + "/22222222"
                    )
            );
        }

        List<MlflowRun> runs = new ArrayList<>();
        String pageToken = null;

        do {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("experiment_ids", List.of(experimentId));
            payload.put("max_results", 1000);
            payload.put("order_by", List.of("attributes.start_time DESC"));
            if (pageToken != null && !pageToken.isBlank()) {
                payload.put("page_token", pageToken);
            }

            JsonNode body = postForNode("/api/2.0/mlflow/runs/search", payload, false);
            JsonNode runNodes = body.path("runs");
            if (runNodes.isArray()) {
                for (JsonNode runNode : runNodes) {
                    runs.add(toRun(runNode));
                }
            }
            pageToken = text(body.path("next_page_token"));
        } while (pageToken != null && !pageToken.isBlank());

        return runs;
    }

    public String getUserPrefix(Jwt jwt) {
        String preferredUsername = jwt == null ? null : jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        String sub = jwt == null ? null : jwt.getSubject();
        if (sub != null && !sub.isBlank()) {
            return sub;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unable to determine user identity");
    }

    public String prefixExperimentName(String username, String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Experiment name is required");
        }
        if (name.startsWith(username + "/")) {
            return name;
        }
        return username + "/" + name;
    }

    public String stripPrefix(String prefixedName, String username) {
        String prefix = username + "/";
        if (prefixedName == null) {
            return null;
        }
        if (prefixedName.startsWith(prefix)) {
            return prefixedName.substring(prefix.length());
        }
        return prefixedName;
    }

    public List<MlflowExperiment> filterByUserPrefix(List<MlflowExperiment> experiments, String username) {
        String prefix = username + "/";
        return experiments.stream()
                .filter(exp -> exp.name() != null && exp.name().startsWith(prefix))
                .toList();
    }

    public String getTrackingUrl() {
        if (isDevProfile()) {
            return "https://mlflow.example.invalid";
        }
        return properties.getTrackingUrl();
    }

    private JsonNode postForNode(String path, Map<String, Object> payload, boolean conflictOnDuplicate) {
        try {
            String response = mlflowRestTemplate.postForObject(path, payload, String.class);
            return objectMapper.readTree(response == null ? "{}" : response);
        } catch (HttpStatusCodeException ex) {
            if (conflictOnDuplicate && isAlreadyExistsError(ex.getStatusCode(), ex.getResponseBodyAsString())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment with this name already exists");
            }
            throw toResponseStatus(ex);
        } catch (ResourceAccessException ex) {
            throw new MlflowUnavailableException("Experiment tracking server is unavailable", ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MlflowUnavailableException("Experiment tracking server is unavailable", ex);
        }
    }

    private ResponseStatusException toResponseStatus(HttpStatusCodeException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        String message = ex.getResponseBodyAsString();
        if (message == null || message.isBlank()) {
            message = "MLflow request failed";
        }
        return new ResponseStatusException(status, message, ex);
    }

    private boolean isAlreadyExistsError(HttpStatusCode status, String body) {
        return status.value() == HttpStatus.BAD_REQUEST.value()
                && body != null
                && body.contains("RESOURCE_ALREADY_EXISTS");
    }

    private MlflowExperiment toExperiment(JsonNode node) {
        return new MlflowExperiment(
                text(node.path("experiment_id")),
                text(node.path("name")),
                text(node.path("artifact_location")),
                text(node.path("lifecycle_stage")),
                longValue(node.path("creation_time")),
                longValue(node.path("last_update_time"))
        );
    }

    private MlflowRun toRun(JsonNode node) {
        JsonNode info = node.path("info");
        JsonNode data = node.path("data");

        Map<String, String> parameters = new LinkedHashMap<>();
        JsonNode paramsNode = data.path("params");
        if (paramsNode.isArray()) {
            for (JsonNode paramNode : paramsNode) {
                String key = text(paramNode.path("key"));
                if (key != null) {
                    parameters.put(key, text(paramNode.path("value")));
                }
            }
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        JsonNode metricsNode = data.path("metrics");
        if (metricsNode.isArray()) {
            for (JsonNode metricNode : metricsNode) {
                String key = text(metricNode.path("key"));
                if (key != null && metricNode.path("value").isNumber()) {
                    metrics.put(key, metricNode.path("value").doubleValue());
                }
            }
        }

        return new MlflowRun(
                text(info.path("run_id")),
                text(info.path("experiment_id")),
                text(info.path("status")),
                longValue(info.path("start_time")),
                longValue(info.path("end_time")),
                parameters,
                metrics,
                text(info.path("artifact_uri"))
        );
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    private Long longValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }
}
