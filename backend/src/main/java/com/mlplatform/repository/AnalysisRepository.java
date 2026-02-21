package com.mlplatform.repository;

import com.mlplatform.model.Analysis;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {
    List<Analysis> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Analysis> findByUserIdAndName(UUID userId, String name);

    Optional<Analysis> findByIdAndUserId(UUID id, UUID userId);
}
