package com.mlplatform.repository;

import com.mlplatform.model.Workspace;
import com.mlplatform.model.Workspace.WorkspaceStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    List<Workspace> findByUserIdAndStatusIn(UUID userId, List<WorkspaceStatus> statuses);

    Optional<Workspace> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Workspace> findByAnalysisIdAndStatusIn(UUID analysisId, List<WorkspaceStatus> statuses);

    Optional<Workspace> findTopByAnalysisIdOrderByCreatedAtDesc(UUID analysisId);
}
