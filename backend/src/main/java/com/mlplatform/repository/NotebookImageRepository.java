package com.mlplatform.repository;

import com.mlplatform.model.NotebookImage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotebookImageRepository extends JpaRepository<NotebookImage, UUID> {

    List<NotebookImage> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<NotebookImage> findByIdAndUserId(UUID id, UUID userId);

    Optional<NotebookImage> findByUserIdAndName(UUID userId, String name);

    boolean existsByUserIdAndName(UUID userId, String name);
}
