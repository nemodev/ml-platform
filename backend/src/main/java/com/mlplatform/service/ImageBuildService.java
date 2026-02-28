package com.mlplatform.service;

import com.mlplatform.config.NotebookImageConfig.NotebookImageProperties;
import com.mlplatform.dto.ImageBuildDetailDto;
import com.mlplatform.dto.ImageBuildDto;
import com.mlplatform.exception.ImageBuildUnavailableException;
import com.mlplatform.model.ImageBuild;
import com.mlplatform.model.ImageBuildStatus;
import com.mlplatform.model.NotebookImage;
import com.mlplatform.model.NotebookImageStatus;
import com.mlplatform.model.User;
import com.mlplatform.repository.ImageBuildRepository;
import com.mlplatform.repository.NotebookImageRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapVolumeSource;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1SecretVolumeSource;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImageBuildService {

    private static final Logger log = LoggerFactory.getLogger(ImageBuildService.class);

    private final ImageBuildRepository imageBuildRepository;
    private final NotebookImageRepository notebookImageRepository;
    private final UserService userService;
    private final ContainerRegistryService containerRegistryService;
    private final NotebookImageProperties properties;
    private final ApiClient apiClient;
    private final Environment environment;

    public ImageBuildService(
            ImageBuildRepository imageBuildRepository,
            NotebookImageRepository notebookImageRepository,
            UserService userService,
            ContainerRegistryService containerRegistryService,
            NotebookImageProperties properties,
            ApiClient apiClient,
            Environment environment
    ) {
        this.imageBuildRepository = imageBuildRepository;
        this.notebookImageRepository = notebookImageRepository;
        this.userService = userService;
        this.containerRegistryService = containerRegistryService;
        this.properties = properties;
        this.apiClient = apiClient;
        this.environment = environment;
    }

    @Transactional
    public ImageBuildDto triggerBuild(Jwt jwt, UUID imageId) {
        if (isDevProfile()) {
            return mockTriggerBuild(jwt, imageId);
        }

        User user = userService.syncFromJwt(jwt);
        UUID userId = user.getId();

        NotebookImage image = notebookImageRepository.findByIdAndUserId(imageId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        // Check per-user active build limit (1)
        List<ImageBuildStatus> activeStatuses = List.of(ImageBuildStatus.QUEUED, ImageBuildStatus.BUILDING);
        long userActiveBuilds = imageBuildRepository.countByNotebookImage_UserIdAndStatusIn(userId, activeStatuses);
        if (userActiveBuilds > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have an active build. Wait for it to complete before starting another.");
        }

        // Check cluster-wide build limit
        long clusterActiveBuilds = imageBuildRepository.countByStatusIn(activeStatuses);
        if (clusterActiveBuilds >= properties.getBuild().getMaxConcurrentBuilds()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Build capacity reached (" + properties.getBuild().getMaxConcurrentBuilds()
                            + " concurrent builds). Please try again later.");
        }

        ImageBuild build = new ImageBuild();
        build.setNotebookImage(image);
        build.setStatus(ImageBuildStatus.QUEUED);
        build = imageBuildRepository.save(build);

        image.setStatus(NotebookImageStatus.BUILDING);
        image.setUpdatedAt(Instant.now());
        notebookImageRepository.save(image);

        log.info("Build queued: buildId={}, imageId={}, imageName={}", build.getId(), imageId, image.getName());
        return toBuildDto(build);
    }

    @Transactional(readOnly = true)
    public ImageBuildDetailDto getBuild(Jwt jwt, UUID imageId, UUID buildId) {
        if (isDevProfile()) {
            return mockBuildDetailDto(imageId);
        }

        User user = userService.syncFromJwt(jwt);
        NotebookImage image = notebookImageRepository.findByIdAndUserId(imageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        ImageBuild build = imageBuildRepository.findByIdAndNotebookImageId(buildId, image.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Build not found"));

        return toDetailDto(build, image);
    }

    @Transactional(readOnly = true)
    public List<ImageBuildDto> listBuilds(Jwt jwt, UUID imageId) {
        if (isDevProfile()) {
            return List.of(mockBuildDto());
        }

        User user = userService.syncFromJwt(jwt);
        NotebookImage image = notebookImageRepository.findByIdAndUserId(imageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        return imageBuildRepository.findByNotebookImageIdOrderByCreatedAtDesc(image.getId())
                .stream()
                .map(this::toBuildDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public String getBuildLogs(Jwt jwt, UUID imageId, UUID buildId) {
        if (isDevProfile()) {
            return "=== Dev Profile Mock Build Logs ===\nStep 1: FROM base image\nStep 2: RUN pip install packages\nStep 3: Build complete\n";
        }

        User user = userService.syncFromJwt(jwt);
        NotebookImage image = notebookImageRepository.findByIdAndUserId(imageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        ImageBuild build = imageBuildRepository.findByIdAndNotebookImageId(buildId, image.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Build not found"));

        // If we have stored logs, return them
        if (build.getBuildLogs() != null) {
            return build.getBuildLogs();
        }

        // Otherwise try to get live logs from K8s pod
        if (build.getK8sJobName() != null) {
            try {
                return fetchPodLogs(build.getK8sJobName());
            } catch (Exception e) {
                log.warn("Failed to fetch live logs for build {}: {}", buildId, e.getMessage());
            }
        }

        return "No logs available yet.";
    }

    void createKanikoJob(ImageBuild build) {
        NotebookImage image = build.getNotebookImage();
        String namespace = properties.getBuild().getNamespace();
        String jobName = "kaniko-build-" + build.getId().toString().substring(0, 8);
        String configMapName = "kaniko-dockerfile-" + build.getId().toString().substring(0, 8);

        String baseImage = containerRegistryService.getBaseImageReference(image.getPythonVersion());
        String imageName = "custom/" + image.getUserId().toString().substring(0, 8)
                + "-" + image.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String tag = "v" + build.getId().toString().substring(0, 8);
        String destination = containerRegistryService.getImageReference(imageName, tag);

        // Generate Dockerfile content
        StringBuilder dockerfile = new StringBuilder();
        dockerfile.append("FROM ").append(baseImage).append("\n");
        dockerfile.append("USER root\n");
        if (image.getPackages() != null && !image.getPackages().isBlank()) {
            dockerfile.append("COPY requirements.txt /tmp/custom-requirements.txt\n");
            String pipCmd = "RUN pip install --no-cache-dir";
            if (image.getExtraPipIndexUrl() != null && !image.getExtraPipIndexUrl().isBlank()) {
                pipCmd += " --extra-index-url " + image.getExtraPipIndexUrl();
            }
            pipCmd += " -r /tmp/custom-requirements.txt";
            dockerfile.append(pipCmd).append("\n");
        }
        dockerfile.append("USER jovyan\n");

        try {
            BatchV1Api batchApi = new BatchV1Api(apiClient);
            CoreV1Api coreApi = new CoreV1Api(apiClient);

            // Create ConfigMap with Dockerfile and requirements.txt
            V1ConfigMap configMap = new V1ConfigMap()
                    .metadata(new V1ObjectMeta().name(configMapName).namespace(namespace))
                    .data(Map.of(
                            "Dockerfile", dockerfile.toString(),
                            "requirements.txt", image.getPackages() != null ? image.getPackages() : ""
                    ));
            coreApi.createNamespacedConfigMap(namespace, configMap).execute();

            // Build Kaniko args
            List<String> args = new java.util.ArrayList<>(List.of(
                    "--dockerfile=/workspace/Dockerfile",
                    "--context=/workspace",
                    "--destination=" + destination,
                    "--cache=true",
                    "--cache-repo=" + containerRegistryService.getRegistryEndpoint() + "/cache",
                    "--verbosity=info"
            ));
            if (containerRegistryService.isInsecure()) {
                args.add("--insecure");
                args.add("--insecure-pull");
                args.add("--skip-tls-verify");
                args.add("--skip-tls-verify-pull");
            }

            // Create Kaniko Job with initContainer to resolve ConfigMap symlinks
            V1Job job = new V1Job()
                    .metadata(new V1ObjectMeta().name(jobName).namespace(namespace)
                            .labels(Map.of("app", "kaniko-build", "build-id", build.getId().toString())))
                    .spec(new V1JobSpec()
                            .backoffLimit(0)
                            .activeDeadlineSeconds((long) properties.getBuild().getTimeoutMinutes() * 60)
                            .template(new V1PodTemplateSpec()
                                    .metadata(new V1ObjectMeta()
                                            .labels(Map.of("app", "kaniko-build", "build-id", build.getId().toString())))
                                    .spec(new V1PodSpec()
                                            .serviceAccountName("kaniko-builder")
                                            .restartPolicy("Never")
                                            .initContainers(List.of(new V1Container()
                                                    .name("prepare-workspace")
                                                    .image("busybox:1.36")
                                                    .command(List.of("sh", "-c", "cp /source/* /workspace/"))
                                                    .volumeMounts(List.of(
                                                            new V1VolumeMount().name("source").mountPath("/source"),
                                                            new V1VolumeMount().name("workspace").mountPath("/workspace")
                                                    ))))
                                            .containers(List.of(new V1Container()
                                                    .name("kaniko")
                                                    .image(properties.getBuild().getKanikoImage())
                                                    .args(args)
                                                    .resources(new V1ResourceRequirements()
                                                            .requests(Map.of(
                                                                    "cpu", new io.kubernetes.client.custom.Quantity(properties.getBuild().getBuildCpuRequest()),
                                                                    "memory", new io.kubernetes.client.custom.Quantity(properties.getBuild().getBuildMemoryRequest())
                                                            ))
                                                            .limits(Map.of(
                                                                    "cpu", new io.kubernetes.client.custom.Quantity(properties.getBuild().getBuildCpuLimit()),
                                                                    "memory", new io.kubernetes.client.custom.Quantity(properties.getBuild().getBuildMemoryLimit())
                                                            )))
                                                    .volumeMounts(List.of(
                                                            new V1VolumeMount().name("workspace").mountPath("/workspace"),
                                                            new V1VolumeMount().name("docker-config").mountPath("/kaniko/.docker")
                                                    ))))
                                            .volumes(List.of(
                                                    new V1Volume().name("source")
                                                            .configMap(new V1ConfigMapVolumeSource().name(configMapName)),
                                                    new V1Volume().name("workspace")
                                                            .emptyDir(new io.kubernetes.client.openapi.models.V1EmptyDirVolumeSource()),
                                                    new V1Volume().name("docker-config")
                                                            .secret(new V1SecretVolumeSource().secretName("registry-credentials")
                                                                    .items(List.of(
                                                                            new io.kubernetes.client.openapi.models.V1KeyToPath()
                                                                                    .key(".dockerconfigjson").path("config.json")
                                                                    )))
                                            )))));

            batchApi.createNamespacedJob(namespace, job).execute();

            build.setK8sJobName(jobName);
            build.setStatus(ImageBuildStatus.BUILDING);
            build.setStartedAt(Instant.now());
            build.setProgressStage("Starting build");
            build.setImageReference(destination);
            imageBuildRepository.save(build);

            log.info("Created Kaniko job: jobName={}, buildId={}, destination={}", jobName, build.getId(), destination);

        } catch (ApiException e) {
            log.error("Failed to create Kaniko job for build {}: {} (body: {})",
                    build.getId(), e.getMessage(), e.getResponseBody());
            build.setStatus(ImageBuildStatus.FAILED);
            build.setErrorMessage("Failed to create build job: " + e.getMessage());
            build.setCompletedAt(Instant.now());
            imageBuildRepository.save(build);

            NotebookImage image2 = build.getNotebookImage();
            image2.setStatus(NotebookImageStatus.FAILED);
            image2.setErrorMessage(build.getErrorMessage());
            image2.setUpdatedAt(Instant.now());
            notebookImageRepository.save(image2);

            throw new ImageBuildUnavailableException("Build infrastructure unavailable: " + e.getMessage(), e);
        }
    }

    void refreshBuildStatus(ImageBuild build) {
        if (build.getK8sJobName() == null) {
            return;
        }

        try {
            BatchV1Api batchApi = new BatchV1Api(apiClient);
            String namespace = properties.getBuild().getNamespace();

            V1Job job = batchApi.readNamespacedJob(build.getK8sJobName(), namespace).execute();
            var jobStatus = job.getStatus();

            if (jobStatus == null) {
                return;
            }

            // Update progress stage from pod logs
            try {
                String logs = fetchPodLogs(build.getK8sJobName());
                build.setBuildLogs(logs);
                build.setProgressStage(parseProgressStage(logs));
            } catch (Exception e) {
                log.debug("Could not fetch logs for build {}: {}", build.getId(), e.getMessage());
            }

            if (jobStatus.getSucceeded() != null && jobStatus.getSucceeded() > 0) {
                build.setStatus(ImageBuildStatus.SUCCEEDED);
                build.setCompletedAt(Instant.now());
                build.setProgressStage("Complete");
                imageBuildRepository.save(build);

                NotebookImage image = build.getNotebookImage();
                image.setStatus(NotebookImageStatus.READY);
                image.setImageReference(build.getImageReference());
                image.setErrorMessage(null);
                image.setUpdatedAt(Instant.now());
                notebookImageRepository.save(image);

                log.info("Build succeeded: buildId={}, image={}", build.getId(), build.getImageReference());
                cleanupBuildResources(build);
                return;
            }

            if (jobStatus.getFailed() != null && jobStatus.getFailed() > 0) {
                String errorMsg = "Build failed";
                if (build.getBuildLogs() != null) {
                    String[] lines = build.getBuildLogs().split("\n");
                    if (lines.length > 0) {
                        String lastLine = lines[lines.length - 1];
                        errorMsg = lastLine.length() > 1000 ? lastLine.substring(0, 1000) : lastLine;
                    }
                }

                build.setStatus(ImageBuildStatus.FAILED);
                build.setErrorMessage(errorMsg);
                build.setCompletedAt(Instant.now());
                imageBuildRepository.save(build);

                NotebookImage image = build.getNotebookImage();
                image.setStatus(NotebookImageStatus.FAILED);
                image.setErrorMessage(errorMsg);
                image.setUpdatedAt(Instant.now());
                notebookImageRepository.save(image);

                log.info("Build failed: buildId={}, error={}", build.getId(), errorMsg);
                cleanupBuildResources(build);
                return;
            }

            // Still running — just save progress update
            imageBuildRepository.save(build);

        } catch (ApiException e) {
            log.warn("Failed to check build status for {}: {}", build.getId(), e.getMessage());
        }
    }

    void cancelBuild(ImageBuild build, String reason) {
        build.setStatus(ImageBuildStatus.CANCELLED);
        build.setErrorMessage(reason);
        build.setCompletedAt(Instant.now());
        imageBuildRepository.save(build);

        NotebookImage image = build.getNotebookImage();
        image.setStatus(NotebookImageStatus.FAILED);
        image.setErrorMessage(reason);
        image.setUpdatedAt(Instant.now());
        notebookImageRepository.save(image);

        // Delete K8s Job
        if (build.getK8sJobName() != null) {
            try {
                BatchV1Api batchApi = new BatchV1Api(apiClient);
                batchApi.deleteNamespacedJob(build.getK8sJobName(),
                        properties.getBuild().getNamespace())
                        .propagationPolicy("Background")
                        .execute();
            } catch (ApiException e) {
                log.warn("Failed to delete K8s job {}: {}", build.getK8sJobName(), e.getMessage());
            }
            cleanupBuildResources(build);
        }

        log.info("Build cancelled: buildId={}, reason={}", build.getId(), reason);
    }

    private String fetchPodLogs(String jobName) throws ApiException {
        CoreV1Api coreApi = new CoreV1Api(apiClient);
        String namespace = properties.getBuild().getNamespace();

        V1PodList pods = coreApi.listNamespacedPod(namespace)
                .labelSelector("job-name=" + jobName)
                .execute();

        if (pods.getItems().isEmpty()) {
            return "No pod found for job.";
        }

        V1Pod pod = pods.getItems().get(0);
        String podName = pod.getMetadata().getName();

        return coreApi.readNamespacedPodLog(podName, namespace)
                .container("kaniko")
                .execute();
    }

    private String parseProgressStage(String logs) {
        if (logs == null || logs.isBlank()) {
            return "Starting build";
        }
        String[] lines = logs.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.contains("Pushing image")) return "Pushing image";
            if (line.contains("RUN pip install")) return "Installing packages";
            if (line.contains("COPY")) return "Copying files";
            if (line.contains("FROM")) return "Building base layer";
            if (line.contains("Unpacking")) return "Unpacking layers";
        }
        return "Building";
    }

    private void cleanupBuildResources(ImageBuild build) {
        if (build.getK8sJobName() == null) return;
        try {
            CoreV1Api coreApi = new CoreV1Api(apiClient);
            String namespace = properties.getBuild().getNamespace();
            String configMapName = "kaniko-dockerfile-" + build.getId().toString().substring(0, 8);
            coreApi.deleteNamespacedConfigMap(configMapName, namespace).execute();
        } catch (ApiException e) {
            log.debug("Could not clean up ConfigMap for build {}: {}", build.getId(), e.getMessage());
        }
    }

    private ImageBuildDto toBuildDto(ImageBuild build) {
        return new ImageBuildDto(
                build.getId(),
                build.getStatus().name(),
                build.getProgressStage(),
                build.getImageReference(),
                build.getErrorMessage(),
                build.getStartedAt(),
                build.getCompletedAt(),
                build.getCreatedAt()
        );
    }

    private ImageBuildDetailDto toDetailDto(ImageBuild build, NotebookImage image) {
        long elapsed = 0;
        if (build.getStartedAt() != null) {
            Instant end = build.getCompletedAt() != null ? build.getCompletedAt() : Instant.now();
            elapsed = Duration.between(build.getStartedAt(), end).getSeconds();
        }
        return new ImageBuildDetailDto(
                build.getId(),
                build.getStatus().name(),
                build.getProgressStage(),
                build.getImageReference(),
                build.getErrorMessage(),
                build.getStartedAt(),
                build.getCompletedAt(),
                build.getCreatedAt(),
                elapsed,
                image.getId(),
                image.getName()
        );
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }

    private ImageBuildDto mockTriggerBuild(Jwt jwt, UUID imageId) {
        User user = userService.syncFromJwt(jwt);
        NotebookImage image = notebookImageRepository.findByIdAndUserId(imageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        // Create a real build record
        ImageBuild build = new ImageBuild();
        build.setNotebookImage(image);
        build.setStatus(ImageBuildStatus.SUCCEEDED);
        build.setProgressStage("Complete");
        String mockRef = containerRegistryService.getRegistryEndpoint()
                + "/custom/" + image.getName() + ":build-" + System.currentTimeMillis();
        build.setImageReference(mockRef);
        build.setStartedAt(Instant.now().minusSeconds(120));
        build.setCompletedAt(Instant.now());
        build = imageBuildRepository.save(build);

        // Update image to READY with the mock reference
        image.setStatus(NotebookImageStatus.READY);
        image.setImageReference(mockRef);
        image.setUpdatedAt(Instant.now());
        notebookImageRepository.save(image);

        log.info("Dev mock build completed: buildId={}, imageId={}, ref={}", build.getId(), imageId, mockRef);
        return toBuildDto(build);
    }

    private ImageBuildDto mockBuildDto() {
        return new ImageBuildDto(
                UUID.nameUUIDFromBytes("mock-build".getBytes()),
                ImageBuildStatus.SUCCEEDED.name(),
                "Complete",
                "registry.mock:5000/custom/mock:v1",
                null,
                Instant.now().minusSeconds(120),
                Instant.now(),
                Instant.now().minusSeconds(130)
        );
    }

    private ImageBuildDetailDto mockBuildDetailDto(UUID imageId) {
        return new ImageBuildDetailDto(
                UUID.nameUUIDFromBytes("mock-build".getBytes()),
                ImageBuildStatus.SUCCEEDED.name(),
                "Complete",
                "registry.mock:5000/custom/mock:v1",
                null,
                Instant.now().minusSeconds(120),
                Instant.now(),
                Instant.now().minusSeconds(130),
                120,
                imageId,
                "mock-image"
        );
    }
}
