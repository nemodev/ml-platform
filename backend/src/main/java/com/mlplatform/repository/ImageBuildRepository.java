package com.mlplatform.repository;

import com.mlplatform.model.ImageBuild;
import com.mlplatform.model.ImageBuildStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageBuildRepository extends JpaRepository<ImageBuild, UUID> {

    List<ImageBuild> findByNotebookImageIdOrderByCreatedAtDesc(UUID notebookImageId);

    Optional<ImageBuild> findByIdAndNotebookImageId(UUID id, UUID notebookImageId);

    List<ImageBuild> findByStatusIn(List<ImageBuildStatus> statuses);

    long countByNotebookImage_UserIdAndStatusIn(UUID userId, List<ImageBuildStatus> statuses);

    long countByStatusIn(List<ImageBuildStatus> statuses);

    Optional<ImageBuild> findFirstByStatusOrderByCreatedAtAsc(ImageBuildStatus status);

    List<ImageBuild> findByNotebookImage_UserIdAndStatusInAndCompletedAtAfter(
            UUID userId, List<ImageBuildStatus> statuses, Instant since);
}
