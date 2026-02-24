package com.mlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlplatform.config.KServeConfig.KServeProperties;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.ApiResponse;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KServeService {

    private static final Logger log = LoggerFactory.getLogger(KServeService.class);

    public record InferenceServiceStatus(
            String phase,
            String inferenceUrl,
            String errorMessage
    ) {}

    public record ProxyInferenceResponse(
            String modelName,
            String modelVersion,
            List<Map<String, Object>> outputs
    ) {}

    private record MockInferenceState(
            Instant readyAt,
            boolean failed,
            String errorMessage
    ) {}

    private static final String KSERVER_GROUP = "serving.kserve.io";
    private static final String KSERVER_VERSION = "v1beta1";
    private static final String KSERVER_PLURAL = "inferenceservices";
    private static final String READY = "READY";
    private static final String DEPLOYING = "DEPLOYING";
    private static final String FAILED = "FAILED";
    private static final String DELETED = "DELETED";
    private static final Set<String> POD_FAILURE_REASONS = Set.of(
            "crashloopbackoff",
            "errimagepull",
            "imagepullbackoff",
            "invalidimagename",
            "createcontainerconfigerror",
            "createcontainererror",
            "runcontainererror",
            "starterror"
    );

    private final ApiClient apiClient;
    private final KServeProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, MockInferenceState> mockInferenceStates = new ConcurrentHashMap<>();

    public KServeService(ApiClient apiClient, KServeProperties properties, ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void createInferenceService(String endpointName, String storageUri) {
        if (isMockMode()) {
            if (mockInferenceStates.containsKey(endpointName)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Inference endpoint already exists");
            }
            boolean shouldFail = endpointName.toLowerCase(Locale.ROOT).contains("invalid")
                    || (storageUri != null && storageUri.toLowerCase(Locale.ROOT).contains("invalid"));
            MockInferenceState state = new MockInferenceState(
                    Instant.now().plusSeconds(10),
                    shouldFail,
                    shouldFail ? "Unsupported model format for deployment" : null
            );
            mockInferenceStates.put(endpointName, state);
            return;
        }

        try {
            customObjectsApi()
                    .createNamespacedCustomObject(
                            KSERVER_GROUP,
                            KSERVER_VERSION,
                            properties.getNamespace(),
                            KSERVER_PLURAL,
                            buildInferenceServicePayload(endpointName, storageUri)
                    )
                    .execute();
        } catch (ApiException ex) {
            if (ex.getCode() == HttpStatus.CONFLICT.value()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Inference endpoint already exists");
            }
            throw new KServeUnavailableException("Failed to create KServe InferenceService: " + apiErrorMessage(ex), ex);
        }
    }

    public InferenceServiceStatus getInferenceServiceStatus(String endpointName) {
        if (isMockMode()) {
            MockInferenceState state = mockInferenceStates.get(endpointName);
            if (state == null) {
                return new InferenceServiceStatus(DELETED, null, null);
            }
            if (state.failed()) {
                return new InferenceServiceStatus(FAILED, buildClusterInferenceUrl(endpointName), state.errorMessage());
            }
            if (Instant.now().isBefore(state.readyAt())) {
                return new InferenceServiceStatus(DEPLOYING, buildClusterInferenceUrl(endpointName), null);
            }
            return new InferenceServiceStatus(READY, buildClusterInferenceUrl(endpointName), null);
        }

        try {
            Object response = customObjectsApi()
                    .getNamespacedCustomObject(
                            KSERVER_GROUP,
                            KSERVER_VERSION,
                            properties.getNamespace(),
                            KSERVER_PLURAL,
                            endpointName
                    )
                    .execute();

            JsonNode root = objectMapper.valueToTree(response);
            JsonNode statusNode = root.path("status");
            String url = buildClusterInferenceUrl(endpointName);

            JsonNode readyCondition = findReadyCondition(statusNode.path("conditions"));
            if (readyCondition == null) {
                return new InferenceServiceStatus(DEPLOYING, url, null);
            }

            String conditionStatus = text(readyCondition.path("status"));
            String reason = text(readyCondition.path("reason"));
            String message = text(readyCondition.path("message"));
            if ("True".equalsIgnoreCase(conditionStatus)) {
                return new InferenceServiceStatus(READY, url, null);
            }
            String podFailureMessage = detectPodFailureMessage(endpointName);
            if (podFailureMessage != null) {
                return new InferenceServiceStatus(FAILED, url, podFailureMessage);
            }
            if (isFailureReason(reason, message)) {
                return new InferenceServiceStatus(FAILED, url, message);
            }
            return new InferenceServiceStatus(DEPLOYING, url, message);
        } catch (ApiException ex) {
            if (ex.getCode() == HttpStatus.NOT_FOUND.value()) {
                return new InferenceServiceStatus(DELETED, null, null);
            }
            throw new KServeUnavailableException("Failed to read KServe InferenceService: " + apiErrorMessage(ex), ex);
        } catch (Exception ex) {
            throw new KServeUnavailableException("Failed to parse KServe InferenceService status", ex);
        }
    }

    public void deleteInferenceService(String endpointName) {
        if (isMockMode()) {
            mockInferenceStates.remove(endpointName);
            return;
        }

        try {
            customObjectsApi()
                    .deleteNamespacedCustomObject(
                            KSERVER_GROUP,
                            KSERVER_VERSION,
                            properties.getNamespace(),
                            KSERVER_PLURAL,
                            endpointName
                    )
                    .propagationPolicy("Background")
                    .execute();
        } catch (ApiException ex) {
            if (ex.getCode() != HttpStatus.NOT_FOUND.value()) {
                throw new KServeUnavailableException("Failed to delete KServe InferenceService: " + apiErrorMessage(ex), ex);
            }
        }
    }

    public ProxyInferenceResponse proxyPredict(String endpointName, Map<String, Object> requestPayload) {
        if (isMockMode()) {
            MockInferenceState state = mockInferenceStates.get(endpointName);
            if (state == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inference endpoint not found");
            }
            if (state.failed()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, state.errorMessage());
            }
            if (Instant.now().isBefore(state.readyAt())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inference endpoint is still deploying");
            }
            return new ProxyInferenceResponse(
                    endpointName,
                    "1",
                    List.of(Map.of("name", "predict", "shape", List.of(1, 1), "datatype", "FP64", "data", List.of(List.of(4.52))))
            );
        }
        List<String> serviceCandidates = List.of(endpointName, endpointName + "-predictor");
        ResponseStatusException notFound = null;
        try {
            for (String serviceName : serviceCandidates) {
                try {
                    return proxyPredictViaService(endpointName, serviceName, requestPayload);
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        notFound = ex;
                        continue;
                    }
                    throw ex;
                }
            }
            if (notFound != null) {
                throw notFound;
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inference endpoint service not found");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KServeUnavailableException("Inference endpoint is unavailable", ex);
        }
    }

    public String getNamespace() {
        return properties.getNamespace();
    }

    public String buildClusterInferenceUrl(String endpointName) {
        return "http://" + endpointName + "-predictor." + properties.getNamespace() + ".svc";
    }

    public boolean hasResidualRuntimeResources(String endpointName) {
        if (isMockMode()) {
            return false;
        }
        return hasResidualResources("/api/v1/namespaces/%s/pods", endpointName)
                || hasResidualResources("/api/v1/namespaces/%s/services", endpointName);
    }

    private CustomObjectsApi customObjectsApi() {
        return new CustomObjectsApi(apiClient);
    }

    private Map<String, Object> buildInferenceServicePayload(String endpointName, String storageUri) {
        Map<String, Object> modelSpec = new LinkedHashMap<>();
        modelSpec.put("modelFormat", Map.of("name", properties.getModelFormatName()));
        if (properties.getRuntimeName() != null && !properties.getRuntimeName().isBlank()) {
            modelSpec.put("runtime", properties.getRuntimeName());
        }
        modelSpec.put("storageUri", storageUri);
        modelSpec.put("resources", Map.of(
                "requests", Map.of("cpu", "1", "memory", "2Gi"),
                "limits", Map.of("cpu", "1", "memory", "2Gi")
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiVersion", "serving.kserve.io/v1beta1");
        payload.put("kind", "InferenceService");
        payload.put("metadata", Map.of(
                "name", endpointName,
                "namespace", properties.getNamespace(),
                "annotations", Map.of("serving.kserve.io/deploymentMode", "RawDeployment")
        ));
        payload.put("spec", Map.of(
                "predictor", Map.of(
                        "serviceAccountName", properties.getServiceAccountName(),
                        "model", modelSpec
                )
        ));
        return payload;
    }

    private ProxyInferenceResponse proxyPredictViaService(
            String endpointName,
            String serviceName,
            Map<String, Object> requestPayload
    ) throws Exception {
        String path = String.format(
                "/api/v1/namespaces/%s/services/%s:80/proxy/v2/models/%s/infer",
                properties.getNamespace(),
                serviceName,
                endpointName
        );

        Map<String, String> headerParams = new LinkedHashMap<>();
        headerParams.put("Accept", "application/json");
        headerParams.put("Content-Type", "application/json");

        try {
            Call call = apiClient.buildCall(
                    null,
                    path,
                    "POST",
                    new ArrayList<>(),
                    new ArrayList<>(),
                    requestPayload,
                    headerParams,
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    resolveAuthNames(),
                    null
            );
            ApiResponse<String> apiResponse = apiClient.execute(call, String.class);
            String body = apiResponse.getData();
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            return new ProxyInferenceResponse(
                    text(json.path("model_name")),
                    text(json.path("model_version")),
                    parseOutputs(json.path("outputs"))
            );
        } catch (ApiException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getCode());
            if (status == null) {
                status = HttpStatus.BAD_GATEWAY;
            }
            String message = extractInferenceErrorMessage(ex.getResponseBody());
            if (isLikelyInputValidationError(status, message)) {
                status = HttpStatus.BAD_REQUEST;
            }
            throw new ResponseStatusException(status, message, ex);
        }
    }

    private List<Map<String, Object>> parseOutputs(JsonNode outputsNode) {
        if (!outputsNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (JsonNode outputNode : outputsNode) {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = objectMapper.convertValue(outputNode, Map.class);
            outputs.add(output);
        }
        return outputs;
    }

    private JsonNode findReadyCondition(JsonNode conditionsNode) {
        if (!conditionsNode.isArray()) {
            return null;
        }
        for (JsonNode conditionNode : conditionsNode) {
            if ("Ready".equalsIgnoreCase(text(conditionNode.path("type")))) {
                return conditionNode;
            }
        }
        return null;
    }

    private boolean isFailureReason(String reason, String message) {
        String merged = ((reason == null ? "" : reason) + " " + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        return merged.contains("fail")
                || merged.contains("invalid")
                || merged.contains("unsupported")
                || merged.contains("crash")
                || merged.contains("error");
    }

    private String detectPodFailureMessage(String endpointName) {
        try {
            String path = String.format("/api/v1/namespaces/%s/pods", properties.getNamespace());
            Call call = apiClient.buildCall(
                    null,
                    path,
                    "GET",
                    new ArrayList<>(),
                    new ArrayList<>(),
                    null,
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    resolveAuthNames(),
                    null
            );
            ApiResponse<String> apiResponse = apiClient.execute(call, String.class);
            JsonNode root = objectMapper.readTree(apiResponse.getData() == null ? "{}" : apiResponse.getData());
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) {
                return null;
            }
            for (JsonNode podNode : items) {
                String podEndpoint = text(podNode.path("metadata").path("labels").path("serving.kserve.io/inferenceservice"));
                if (!endpointName.equals(podEndpoint)) {
                    continue;
                }
                JsonNode statusNode = podNode.path("status");
                String initFailure = failureFromStatuses(statusNode.path("initContainerStatuses"));
                if (initFailure != null) {
                    return initFailure;
                }
                String containerFailure = failureFromStatuses(statusNode.path("containerStatuses"));
                if (containerFailure != null) {
                    return containerFailure;
                }
            }
            return null;
        } catch (Exception ex) {
            log.warn("Unable to inspect pod failure state for endpoint {}: {}", endpointName, ex.getMessage());
            return null;
        }
    }

    private boolean hasResidualResources(String pathPattern, String endpointName) {
        String path = String.format(pathPattern, properties.getNamespace());
        try {
            Call call = apiClient.buildCall(
                    null,
                    path,
                    "GET",
                    new ArrayList<>(),
                    new ArrayList<>(),
                    null,
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    resolveAuthNames(),
                    null
            );
            ApiResponse<String> apiResponse = apiClient.execute(call, String.class);
            JsonNode root = objectMapper.readTree(apiResponse.getData() == null ? "{}" : apiResponse.getData());
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) {
                return false;
            }
            String predictorPrefix = endpointName + "-predictor";
            for (JsonNode itemNode : items) {
                String linkedEndpoint = text(itemNode.path("metadata").path("labels").path("serving.kserve.io/inferenceservice"));
                if (endpointName.equals(linkedEndpoint)) {
                    return true;
                }
                String name = text(itemNode.path("metadata").path("name"));
                if (name != null && (name.equals(predictorPrefix) || name.startsWith(predictorPrefix + "-"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            log.warn("Unable to verify runtime cleanup for endpoint {}: {}", endpointName, ex.getMessage());
            return true;
        }
    }

    private String failureFromStatuses(JsonNode statusesNode) {
        if (!statusesNode.isArray() || statusesNode.isEmpty()) {
            return null;
        }
        for (JsonNode statusNode : statusesNode) {
            String name = text(statusNode.path("name"));
            if (name == null) {
                name = "container";
            }
            String current = failureFromState(name, statusNode.path("state"));
            if (current != null) {
                return current;
            }
            String last = failureFromState(name, statusNode.path("lastState"));
            if (last != null) {
                return last;
            }
        }
        return null;
    }

    private String failureFromState(String containerName, JsonNode stateNode) {
        if (stateNode == null || stateNode.isMissingNode() || stateNode.isNull()) {
            return null;
        }

        JsonNode waiting = stateNode.path("waiting");
        if (!waiting.isMissingNode() && !waiting.isNull()) {
            String reason = text(waiting.path("reason"));
            if (reason != null && POD_FAILURE_REASONS.contains(reason.toLowerCase(Locale.ROOT))) {
                String detail = nonBlank(text(waiting.path("message")), reason);
                return "Container " + containerName + " failed: " + detail;
            }
        }

        JsonNode terminated = stateNode.path("terminated");
        if (!terminated.isMissingNode() && !terminated.isNull()) {
            String reason = text(terminated.path("reason"));
            int exitCode = terminated.path("exitCode").asInt(0);
            boolean nonZeroExit = exitCode != 0;
            boolean reasonFailed = reason != null && isFailureReason(reason, text(terminated.path("message")));
            if (nonZeroExit || reasonFailed) {
                String detail = nonBlank(text(terminated.path("message")), reason);
                if (detail == null) {
                    detail = "exitCode=" + exitCode;
                }
                return "Container " + containerName + " failed: " + detail;
            }
        }

        return null;
    }

    private String apiErrorMessage(ApiException ex) {
        String responseBody = ex.getResponseBody();
        if (responseBody != null && !responseBody.isBlank()) {
            return responseBody;
        }
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return "code=" + ex.getCode();
    }

    private String[] resolveAuthNames() {
        if (apiClient.getAuthentications() == null || apiClient.getAuthentications().isEmpty()) {
            return new String[0];
        }
        if (apiClient.getAuthentications().containsKey("BearerToken")) {
            return new String[]{"BearerToken"};
        }
        String[] authNames = apiClient.getAuthentications().keySet().toArray(String[]::new);
        return Arrays.stream(authNames).filter(name -> name != null && !name.isBlank()).toArray(String[]::new);
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

    private String extractInferenceErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "Inference request failed";
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String extracted = text(node.path("error"));
            if (extracted != null) {
                return extracted;
            }
            extracted = text(node.path("message"));
            if (extracted != null) {
                return extracted;
            }
        } catch (Exception ignored) {
            // Fall back to raw response body if it is not valid JSON.
        }
        return responseBody;
    }

    private boolean isLikelyInputValidationError(HttpStatus status, String message) {
        if (status != HttpStatus.INTERNAL_SERVER_ERROR || message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("expecting")
                || normalized.contains("feature")
                || normalized.contains("shape")
                || normalized.contains("datatype")
                || normalized.contains("invalid input")
                || normalized.contains("cannot reshape");
    }

    private String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private boolean isMockMode() {
        return properties.isMockEnabled();
    }
}
