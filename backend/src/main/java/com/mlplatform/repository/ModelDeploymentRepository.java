package com.mlplatform.repository;

import com.mlplatform.model.ModelDeployment;
import com.mlplatform.model.ModelDeployment.DeploymentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelDeploymentRepository extends JpaRepository<ModelDeployment, UUID> {

    List<ModelDeployment> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId);

    List<ModelDeployment> findByUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID userId,
            DeploymentStatus status
    );

    Optional<ModelDeployment> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    Optional<ModelDeployment> findByEndpointName(String endpointName);

    Optional<ModelDeployment> findByEndpointNameAndDeletedAtIsNull(String endpointName);
}
