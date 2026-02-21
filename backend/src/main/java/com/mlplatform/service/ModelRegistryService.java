package com.mlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlplatform.config.MlflowConfig.MlflowProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);
    private static final String MLFLOW_ARTIFACTS_SCHEME = "mlflow-artifacts:/";

    public record RegisteredModel(
            String name,
            Integer latestVersion,
            String description,
            Instant lastUpdatedAt
    ) {}

    public record ModelVersionDetail(
            Integer version,
            String status,
            String stage,
            String artifactUri,
            String runId,
            Instant createdAt
    ) {}

    private final RestTemplate mlflowRestTemplate;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final MlflowProperties mlflowProperties;

    public ModelRegistryService(
            @Qualifier("mlflowRestTemplate") RestTemplate mlflowRestTemplate,
            ObjectMapper objectMapper,
            Environment environment,
            MlflowProperties mlflowProperties
    ) {
        this.mlflowRestTemplate = mlflowRestTemplate;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.mlflowProperties = mlflowProperties;
    }

    public List<RegisteredModel> listRegisteredModels(String username) {
        if (isDevProfile()) {
            return List.of(
                    new RegisteredModel("housing-regressor", 1, "Mock model for dev profile", Instant.now()),
                    new RegisteredModel("churn-classifier", 3, "Mock model for dev profile", Instant.now().minusSeconds(600))
            );
        }

        List<RegisteredModel> models = new ArrayList<>();
        String pageToken = null;

        do {
            String path = buildRegisteredModelSearchPath(pageToken);
            JsonNode body = getForNode(path);
            JsonNode modelNodes = body.path("registered_models");
            if (modelNodes.isArray()) {
                for (JsonNode modelNode : modelNodes) {
                    String prefixedName = text(modelNode.path("name"));
                    if (prefixedName == null || !prefixedName.startsWith(username + "/")) {
                        continue;
                    }
                    models.add(new RegisteredModel(
                            stripPrefix(prefixedName, username),
                            resolveLatestVersion(modelNode),
                            text(modelNode.path("description")),
                            parseEpochMillis(modelNode.path("last_updated_timestamp"))
                    ));
                }
            }
            pageToken = text(body.path("next_page_token"));
        } while (pageToken != null && !pageToken.isBlank());

        return models.stream()
                .sorted(Comparator.comparing(RegisteredModel::name, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    public List<ModelVersionDetail> getModelVersions(String username, String modelName) {
        if (isDevProfile()) {
            return List.of(
                    new ModelVersionDetail(1, "READY", "Production", "s3://ml-platform-mlflow/artifacts/mock/model", "dev-run-1", Instant.now()),
                    new ModelVersionDetail(2, "READY", "Staging", "s3://ml-platform-mlflow/artifacts/mock/model-v2", "dev-run-2", Instant.now().minusSeconds(120))
            );
        }

        String prefixedModelName = prefixModelName(username, modelName);

        List<ModelVersionDetail> versions = new ArrayList<>();
        String pageToken = null;
        do {
            String path = buildModelVersionSearchPath(pageToken);
            JsonNode body = getForNode(path);
            JsonNode versionNodes = body.path("model_versions");
            if (versionNodes.isArray()) {
                for (JsonNode versionNode : versionNodes) {
                    String foundName = text(versionNode.path("name"));
                    if (prefixedModelName.equals(foundName)) {
                        versions.add(toModelVersion(versionNode));
                    }
                }
            }
            pageToken = text(body.path("next_page_token"));
        } while (pageToken != null && !pageToken.isBlank());

        if (versions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found");
        }

        return versions.stream()
                .sorted(Comparator.comparing(ModelVersionDetail::version, Comparator.nullsLast(Integer::compareTo)).reversed())
                .toList();
    }

    public ModelVersionDetail getModelVersionDetail(String username, String modelName, int version) {
        if (isDevProfile()) {
            return new ModelVersionDetail(
                    version,
                    "READY",
                    "Production",
                    "s3://ml-platform-mlflow/artifacts/mock/model",
                    "dev-run-" + version,
                    Instant.now()
            );
        }

        return getModelVersions(username, modelName).stream()
                .filter(item -> item.version() != null && item.version() == version)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model version not found"));
    }

    /**
     * Resolve the S3 storage URI for a model version by calling MLflow's
     * get-download-uri API. MLflow 3.x stores model version sources as
     * {@code models:/m-<id>} which KServe cannot download. This method
     * resolves the URI through MLflow and converts {@code mlflow-artifacts:/}
     * to the actual S3 path using the configured artifact destination.
     */
    public String resolveModelStorageUri(String username, String modelName, int version) {
        if (isDevProfile()) {
            return "s3://ml-platform-mlflow/artifacts/mock/model";
        }

        String prefixedModelName = prefixModelName(username, modelName);
        String path = UriComponentsBuilder
                .fromPath("/api/2.0/mlflow/model-versions/get-download-uri")
                .queryParam("name", prefixedModelName)
                .queryParam("version", version)
                .build().encode().toUriString();

        JsonNode body = getForNode(path);
        String artifactUri = text(body.path("artifact_uri"));
        if (artifactUri == null || artifactUri.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MLflow did not return an artifact URI for model version");
        }

        if (artifactUri.startsWith("s3://") || artifactUri.startsWith("gs://")) {
            return artifactUri;
        }

        if (artifactUri.startsWith(MLFLOW_ARTIFACTS_SCHEME)) {
            String destination = mlflowProperties.getArtifactDestination();
            if (destination == null || destination.isBlank()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "MLflow artifact destination is not configured (services.mlflow.artifact-destination)");
            }
            String suffix = artifactUri.substring(MLFLOW_ARTIFACTS_SCHEME.length());
            String resolved = destination.replaceAll("/+$", "") + "/" + suffix;
            log.info("Resolved model storage URI: {} -> {}", artifactUri, resolved);
            return resolved;
        }

        log.warn("Unrecognized MLflow artifact URI scheme: {}", artifactUri);
        return artifactUri;
    }

    public String prefixModelName(String username, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model name is required");
        }
        if (modelName.startsWith(username + "/")) {
            return modelName;
        }
        return username + "/" + modelName;
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

    private String buildRegisteredModelSearchPath(String pageToken) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromPath("/api/2.0/mlflow/registered-models/search")
                .queryParam("max_results", 200);
        if (pageToken != null && !pageToken.isBlank()) {
            builder.queryParam("page_token", pageToken);
        }
        return builder.build().encode().toUriString();
    }

    private String buildModelVersionSearchPath(String pageToken) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromPath("/api/2.0/mlflow/model-versions/search")
                .queryParam("max_results", 200);
        if (pageToken != null && !pageToken.isBlank()) {
            builder.queryParam("page_token", pageToken);
        }
        return builder.build().encode().toUriString();
    }

    private JsonNode getForNode(String path) {
        try {
            String response = mlflowRestTemplate.getForObject(path, String.class);
            return objectMapper.readTree(response == null ? "{}" : response);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found");
            }
            throw toResponseStatus(ex);
        } catch (ResourceAccessException ex) {
            throw new MlflowUnavailableException("Model registry is unavailable", ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MlflowUnavailableException("Model registry is unavailable", ex);
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
            message = "Model registry request failed";
        }
        return new ResponseStatusException(status, message, ex);
    }

    private Integer resolveLatestVersion(JsonNode modelNode) {
        JsonNode latestVersions = modelNode.path("latest_versions");
        if (!latestVersions.isArray() || latestVersions.isEmpty()) {
            return null;
        }
        Integer latest = null;
        for (JsonNode versionNode : latestVersions) {
            Integer parsed = parseInt(versionNode.path("version").asText(null));
            if (parsed == null) {
                continue;
            }
            if (latest == null || parsed > latest) {
                latest = parsed;
            }
        }
        return latest;
    }

    private ModelVersionDetail toModelVersion(JsonNode versionNode) {
        return new ModelVersionDetail(
                parseInt(versionNode.path("version").asText(null)),
                normalizeStatus(text(versionNode.path("status"))),
                text(versionNode.path("current_stage")),
                text(versionNode.path("source")),
                text(versionNode.path("run_id")),
                parseEpochMillis(versionNode.path("creation_timestamp"))
        );
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "READY", "PENDING_REGISTRATION", "FAILED_REGISTRATION" -> normalized;
            default -> normalized;
        };
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Instant parseEpochMillis(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        long millis;
        if (node.isNumber()) {
            millis = node.asLong();
        } else {
            String value = text(node);
            if (value == null) {
                return null;
            }
            try {
                millis = Long.parseLong(value);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (millis <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(millis);
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }
}
