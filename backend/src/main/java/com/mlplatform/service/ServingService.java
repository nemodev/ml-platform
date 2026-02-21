package com.mlplatform.service;

import com.mlplatform.dto.DeployModelRequest;
import com.mlplatform.dto.DeploymentDetailDto;
import com.mlplatform.dto.DeploymentInfoDto;
import com.mlplatform.dto.PredictionRequestDto;
import com.mlplatform.dto.PredictionResponseDto;
import com.mlplatform.model.ModelDeployment;
import com.mlplatform.model.ModelDeployment.DeploymentStatus;
import com.mlplatform.model.User;
import com.mlplatform.repository.ModelDeploymentRepository;
import com.mlplatform.service.KServeService.InferenceServiceStatus;
import com.mlplatform.service.KServeService.ProxyInferenceResponse;
import com.mlplatform.service.ModelRegistryService.ModelVersionDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServingService {

    private final ModelDeploymentRepository modelDeploymentRepository;
    private final UserService userService;
    private final ModelRegistryService modelRegistryService;
    private final KServeService kServeService;

    public ServingService(
            ModelDeploymentRepository modelDeploymentRepository,
            UserService userService,
            ModelRegistryService modelRegistryService,
            KServeService kServeService
    ) {
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.userService = userService;
        this.modelRegistryService = modelRegistryService;
        this.kServeService = kServeService;
    }

    @Transactional
    public DeploymentInfoDto deployModel(Jwt jwt, DeployModelRequest request) {
        if (request == null || request.modelName() == null || request.modelName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model name is required");
        }
        if (request.modelVersion() == null || request.modelVersion() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model version must be greater than zero");
        }

        User user = userService.syncFromJwt(jwt);
        String username = resolveUsername(jwt, user);
        String modelName = request.modelName().trim();
        int modelVersion = request.modelVersion();

        ModelVersionDetail versionDetail = modelRegistryService.getModelVersionDetail(username, modelName, modelVersion);
        if (versionDetail.artifactUri() == null || versionDetail.artifactUri().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact URI is missing");
        }

        String storageUri = modelRegistryService.resolveModelStorageUri(username, modelName, modelVersion);

        String endpointName = buildEndpointName(username, modelName, modelVersion);
        if (modelDeploymentRepository.findByEndpointNameAndDeletedAtIsNull(endpointName).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A deployment for this model version already exists");
        }

        ModelDeployment deployment = new ModelDeployment();
        deployment.setUser(user);
        deployment.setModelName(modelName);
        deployment.setModelVersion(modelVersion);
        deployment.setEndpointName(endpointName);
        deployment.setStatus(DeploymentStatus.DEPLOYING);
        deployment.setStorageUri(storageUri);
        deployment = modelDeploymentRepository.save(deployment);

        try {
            kServeService.createInferenceService(endpointName, storageUri);
        } catch (RuntimeException ex) {
            deployment.setStatus(DeploymentStatus.FAILED);
            deployment.setErrorMessage(trimError(ex.getMessage()));
            modelDeploymentRepository.save(deployment);
            throw ex;
        }

        return toInfoDto(deployment);
    }

    @Transactional
    public List<DeploymentInfoDto> listDeployments(Jwt jwt, DeploymentStatus status) {
        User user = userService.syncFromJwt(jwt);
        List<ModelDeployment> deployments = status == null
                ? modelDeploymentRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(user.getId())
                : modelDeploymentRepository.findByUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(user.getId(), status);

        List<ModelDeployment> refreshed = new ArrayList<>();
        for (ModelDeployment deployment : deployments) {
            refreshDeploymentStatus(deployment);
            if (deployment.getDeletedAt() == null) {
                refreshed.add(deployment);
            }
        }

        return refreshed.stream()
                .sorted(Comparator.comparing(ModelDeployment::getCreatedAt).reversed())
                .map(this::toInfoDto)
                .toList();
    }

    @Transactional
    public DeploymentDetailDto getDeployment(Jwt jwt, UUID deploymentId) {
        User user = userService.syncFromJwt(jwt);
        ModelDeployment deployment = findOwnedDeployment(user.getId(), deploymentId);
        refreshDeploymentStatus(deployment);
        return toDetailDto(deployment);
    }

    @Transactional
    public void deleteDeployment(Jwt jwt, UUID deploymentId) {
        User user = userService.syncFromJwt(jwt);
        ModelDeployment deployment = findOwnedDeployment(user.getId(), deploymentId);

        if (deployment.getStatus() == DeploymentStatus.DELETED) {
            return;
        }

        deployment.setStatus(DeploymentStatus.DELETING);
        deployment.setErrorMessage(null);
        modelDeploymentRepository.save(deployment);

        try {
            kServeService.deleteInferenceService(deployment.getEndpointName());
            deployment.setStatus(DeploymentStatus.DELETED);
            deployment.setDeletedAt(Instant.now());
            modelDeploymentRepository.save(deployment);
        } catch (RuntimeException ex) {
            deployment.setErrorMessage(trimError(ex.getMessage()));
            modelDeploymentRepository.save(deployment);
            throw ex;
        }
    }

    @Transactional
    public PredictionResponseDto predict(Jwt jwt, UUID deploymentId, PredictionRequestDto request) {
        if (request == null || request.inputs() == null || request.inputs().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prediction inputs are required");
        }

        User user = userService.syncFromJwt(jwt);
        ModelDeployment deployment = findOwnedDeployment(user.getId(), deploymentId);
        refreshDeploymentStatus(deployment);

        if (deployment.getStatus() != DeploymentStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Deployment is not ready for inference");
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("inputs", request.inputs());
        if (request.parameters() != null && !request.parameters().isEmpty()) {
            payload.put("parameters", request.parameters());
        }
        ProxyInferenceResponse proxyResponse = kServeService.proxyPredict(deployment.getEndpointName(), payload);

        String modelName = proxyResponse.modelName();
        if (modelName == null || modelName.isBlank()) {
            modelName = deployment.getEndpointName();
        }

        return new PredictionResponseDto(
                modelName,
                proxyResponse.modelVersion(),
                proxyResponse.outputs()
        );
    }

    private ModelDeployment findOwnedDeployment(UUID userId, UUID deploymentId) {
        return modelDeploymentRepository.findByIdAndUserIdAndDeletedAtIsNull(deploymentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deployment not found"));
    }

    private void refreshDeploymentStatus(ModelDeployment deployment) {
        if (deployment.getStatus() == DeploymentStatus.DELETED || deployment.getDeletedAt() != null) {
            return;
        }

        if (deployment.getStatus() != DeploymentStatus.DEPLOYING && deployment.getStatus() != DeploymentStatus.DELETING) {
            return;
        }

        InferenceServiceStatus status = kServeService.getInferenceServiceStatus(deployment.getEndpointName());
        String phase = normalizePhase(status.phase());

        if ("DELETED".equals(phase)) {
            deployment.setStatus(DeploymentStatus.DELETED);
            deployment.setDeletedAt(Instant.now());
            modelDeploymentRepository.save(deployment);
            return;
        }

        if ("READY".equals(phase)) {
            deployment.setStatus(DeploymentStatus.READY);
            deployment.setInferenceUrl(nonBlank(status.inferenceUrl(), kServeService.buildClusterInferenceUrl(deployment.getEndpointName())));
            if (deployment.getReadyAt() == null) {
                deployment.setReadyAt(Instant.now());
            }
            deployment.setErrorMessage(null);
            modelDeploymentRepository.save(deployment);
            return;
        }

        if ("FAILED".equals(phase)) {
            deployment.setStatus(DeploymentStatus.FAILED);
            deployment.setErrorMessage(trimError(nonBlank(status.errorMessage(), "Deployment failed")));
            modelDeploymentRepository.save(deployment);
            return;
        }

        deployment.setInferenceUrl(nonBlank(status.inferenceUrl(), deployment.getInferenceUrl()));
        if (status.errorMessage() != null && !status.errorMessage().isBlank()) {
            deployment.setErrorMessage(trimError(status.errorMessage()));
        }
        modelDeploymentRepository.save(deployment);
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

    private String buildEndpointName(String username, String modelName, int version) {
        String usernamePart = toDnsSegment(username, "user");
        String modelPart = toDnsSegment(modelName, "model");
        String suffix = "-v" + version;
        String combined = usernamePart + "-" + modelPart;
        int maxEndpointLength = maxEndpointLengthForRawKServeHost();

        int maxCombinedLength = maxEndpointLength - suffix.length();
        if (maxCombinedLength < 1) {
            maxCombinedLength = 1;
        }
        if (combined.length() > maxCombinedLength) {
            combined = combined.substring(0, maxCombinedLength);
            combined = combined.replaceAll("-+$", "");
            if (combined.isBlank()) {
                combined = "model";
            }
        }

        String endpoint = combined + suffix;
        if (endpoint.length() > maxEndpointLength) {
            endpoint = endpoint.substring(0, maxEndpointLength);
            endpoint = endpoint.replaceAll("-+$", "");
        }
        if (endpoint.isBlank()) {
            endpoint = "model-v" + version;
        }
        return endpoint;
    }

    private int maxEndpointLengthForRawKServeHost() {
        int dnsLabelLimit = 63;
        String namespace = kServeService.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            return dnsLabelLimit;
        }
        int reserved = "-predictor-".length() + namespace.length();
        return Math.max(1, dnsLabelLimit - reserved);
    }

    private String toDnsSegment(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+", "");
        normalized = normalized.replaceAll("-+$", "");
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized;
    }

    private String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "DEPLOYING";
        }
        return phase.toUpperCase(Locale.ROOT);
    }

    private String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String trimError(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private DeploymentInfoDto toInfoDto(ModelDeployment deployment) {
        return new DeploymentInfoDto(
                deployment.getId(),
                deployment.getModelName(),
                deployment.getModelVersion(),
                deployment.getEndpointName(),
                deployment.getInferenceUrl(),
                deployment.getStatus().name(),
                deployment.getCreatedAt(),
                deployment.getReadyAt()
        );
    }

    private DeploymentDetailDto toDetailDto(ModelDeployment deployment) {
        return new DeploymentDetailDto(
                deployment.getId(),
                deployment.getModelName(),
                deployment.getModelVersion(),
                deployment.getEndpointName(),
                deployment.getStatus().name(),
                deployment.getInferenceUrl(),
                deployment.getStorageUri(),
                deployment.getErrorMessage(),
                deployment.getCreatedAt(),
                deployment.getReadyAt()
        );
    }
}
