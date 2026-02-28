package com.mlplatform.service;

import com.mlplatform.config.NotebookImageConfig.NotebookImageProperties;
import com.mlplatform.dto.ImageBuildDto;
import com.mlplatform.dto.NotebookImageDetailDto;
import com.mlplatform.dto.NotebookImageDto;
import com.mlplatform.model.ImageBuild;
import com.mlplatform.model.NotebookImage;
import com.mlplatform.model.NotebookImageStatus;
import com.mlplatform.model.User;
import com.mlplatform.model.Workspace;
import com.mlplatform.model.Workspace.WorkspaceStatus;
import com.mlplatform.repository.ImageBuildRepository;
import com.mlplatform.repository.NotebookImageRepository;
import com.mlplatform.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
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
public class NotebookImageService {

    private static final Logger log = LoggerFactory.getLogger(NotebookImageService.class);

    private final NotebookImageRepository notebookImageRepository;
    private final ImageBuildRepository imageBuildRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserService userService;
    private final ContainerRegistryService containerRegistryService;
    private final NotebookImageProperties properties;
    private final Environment environment;

    public NotebookImageService(
            NotebookImageRepository notebookImageRepository,
            ImageBuildRepository imageBuildRepository,
            WorkspaceRepository workspaceRepository,
            UserService userService,
            ContainerRegistryService containerRegistryService,
            NotebookImageProperties properties,
            Environment environment
    ) {
        this.notebookImageRepository = notebookImageRepository;
        this.imageBuildRepository = imageBuildRepository;
        this.workspaceRepository = workspaceRepository;
        this.userService = userService;
        this.containerRegistryService = containerRegistryService;
        this.properties = properties;
        this.environment = environment;
    }

    @Transactional
    public NotebookImageDto createImage(Jwt jwt, String name, String pythonVersion,
                                         String packages, String extraPipIndexUrl) {
        User user = userService.syncFromJwt(jwt);
        UUID userId = user.getId();

        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image name is required");
        }
        if (pythonVersion == null || pythonVersion.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Python version is required");
        }
        if (!properties.getPythonVersions().contains(pythonVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported Python version: " + pythonVersion
                            + ". Supported: " + properties.getPythonVersions());
        }
        if (notebookImageRepository.existsByUserIdAndName(userId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Image with name '" + name + "' already exists");
        }

        NotebookImage image = new NotebookImage();
        image.setUser(user);
        image.setName(name);
        image.setPythonVersion(pythonVersion);
        image.setPackages(packages);
        image.setExtraPipIndexUrl(extraPipIndexUrl);
        image.setStatus(NotebookImageStatus.PENDING);
        image = notebookImageRepository.save(image);

        log.info("Created notebook image definition: id={}, name={}, python={}", image.getId(), name, pythonVersion);
        return toDto(image);
    }

    @Transactional(readOnly = true)
    public List<NotebookImageDto> listImages(Jwt jwt) {
        User user = userService.syncFromJwt(jwt);
        return notebookImageRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public NotebookImageDetailDto getImage(Jwt jwt, UUID imageId) {
        User user = userService.syncFromJwt(jwt);
        NotebookImage image = notebookImageRepository.findByIdAndUserId(imageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        ImageBuild latestBuild = imageBuildRepository.findByNotebookImageIdOrderByCreatedAtDesc(image.getId())
                .stream()
                .findFirst()
                .orElse(null);

        return toDetailDto(image, latestBuild);
    }

    @Transactional
    public NotebookImageDto updateImage(Jwt jwt, UUID imageId, String name, String pythonVersion,
                                         String packages, String extraPipIndexUrl) {
        User user = userService.syncFromJwt(jwt);
        NotebookImage image = notebookImageRepository.findByIdAndUserId(imageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        if (name != null && !name.isBlank() && !name.equals(image.getName())) {
            if (notebookImageRepository.existsByUserIdAndName(user.getId(), name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Image with name '" + name + "' already exists");
            }
            image.setName(name);
        }
        if (pythonVersion != null && !pythonVersion.isBlank()) {
            if (!properties.getPythonVersions().contains(pythonVersion)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported Python version: " + pythonVersion);
            }
            image.setPythonVersion(pythonVersion);
        }
        if (packages != null) {
            image.setPackages(packages);
        }
        if (extraPipIndexUrl != null) {
            image.setExtraPipIndexUrl(extraPipIndexUrl.isBlank() ? null : extraPipIndexUrl);
        }
        image.setUpdatedAt(Instant.now());
        image = notebookImageRepository.save(image);

        log.info("Updated notebook image: id={}, name={}", image.getId(), image.getName());
        return toDto(image);
    }

    @Transactional
    public void deleteImage(Jwt jwt, UUID imageId) {
        User user = userService.syncFromJwt(jwt);
        NotebookImage image = notebookImageRepository.findByIdAndUserId(imageId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        // Check if image is in use by any active workspace
        boolean inUse = workspaceRepository.existsByNotebookImageIdAndStatusIn(
                imageId,
                List.of(WorkspaceStatus.PENDING, WorkspaceStatus.RUNNING, WorkspaceStatus.IDLE)
        );
        if (inUse) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Image '" + image.getName() + "' is in use by an active workspace. "
                            + "Terminate the workspace before deleting this image.");
        }

        // Clean up image from registry if it was built
        if (image.getImageReference() != null) {
            try {
                String ref = image.getImageReference();
                String imageName = ref.substring(ref.indexOf('/') + 1, ref.lastIndexOf(':'));
                String tag = ref.substring(ref.lastIndexOf(':') + 1);
                containerRegistryService.deleteImage(imageName, tag);
            } catch (Exception ex) {
                log.warn("Failed to delete image from registry: {}", ex.getMessage());
            }
        }

        notebookImageRepository.delete(image);
        log.info("Deleted notebook image: id={}, name={}", imageId, image.getName());
    }

    public List<String> getPythonVersions() {
        return properties.getPythonVersions();
    }

    NotebookImage resolveImage(UUID imageId, UUID userId) {
        return notebookImageRepository.findByIdAndUserId(imageId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
    }

    private int countPackages(String packages) {
        if (packages == null || packages.isBlank()) {
            return 0;
        }
        return (int) packages.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .count();
    }

    private NotebookImageDto toDto(NotebookImage image) {
        return new NotebookImageDto(
                image.getId(),
                image.getName(),
                image.getPythonVersion(),
                countPackages(image.getPackages()),
                image.getStatus().name(),
                image.getImageReference(),
                image.getCreatedAt(),
                image.getUpdatedAt()
        );
    }

    private NotebookImageDetailDto toDetailDto(NotebookImage image, ImageBuild latestBuild) {
        return new NotebookImageDetailDto(
                image.getId(),
                image.getName(),
                image.getPythonVersion(),
                countPackages(image.getPackages()),
                image.getStatus().name(),
                image.getImageReference(),
                image.getPackages(),
                image.getExtraPipIndexUrl(),
                image.getErrorMessage(),
                latestBuild != null ? toBuildDto(latestBuild) : null,
                image.getCreatedAt(),
                image.getUpdatedAt()
        );
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

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }

    private NotebookImageDto mockImageDto() {
        return new NotebookImageDto(
                UUID.nameUUIDFromBytes("mock-image".getBytes()),
                "mock-data-science-env",
                "3.11",
                3,
                NotebookImageStatus.READY.name(),
                "registry.mock:5000/custom/mock:v1",
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(1800)
        );
    }

    private NotebookImageDetailDto mockImageDetailDto(UUID imageId) {
        return new NotebookImageDetailDto(
                imageId,
                "mock-data-science-env",
                "3.11",
                3,
                NotebookImageStatus.READY.name(),
                "registry.mock:5000/custom/mock:v1",
                "pandas==2.1.0\nscikit-learn==1.3.0\nmatplotlib==3.8.0",
                null,
                null,
                new ImageBuildDto(
                        UUID.nameUUIDFromBytes("mock-build".getBytes()),
                        "SUCCEEDED",
                        "Complete",
                        "registry.mock:5000/custom/mock:v1",
                        null,
                        Instant.now().minusSeconds(1920),
                        Instant.now().minusSeconds(1800),
                        Instant.now().minusSeconds(1920)
                ),
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(1800)
        );
    }
}
