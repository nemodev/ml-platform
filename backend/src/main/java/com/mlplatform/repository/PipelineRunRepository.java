package com.mlplatform.repository;

import com.mlplatform.model.PipelineRun;
import com.mlplatform.model.PipelineRun.PipelineStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID> {

    Optional<PipelineRun> findByIdAndUserId(UUID id, UUID userId);

    Page<PipelineRun> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<PipelineRun> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, PipelineStatus status, Pageable pageable);
}
