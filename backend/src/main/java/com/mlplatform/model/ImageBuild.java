package com.mlplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "image_builds")
public class ImageBuild {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "notebook_image_id", nullable = false, insertable = false, updatable = false)
    private UUID notebookImageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notebook_image_id", nullable = false)
    private NotebookImage notebookImage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImageBuildStatus status = ImageBuildStatus.QUEUED;

    @Column(name = "progress_stage")
    private String progressStage;

    @Column(name = "build_logs", columnDefinition = "TEXT")
    private String buildLogs;

    @Column(name = "image_reference")
    private String imageReference;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "k8s_job_name")
    private String k8sJobName;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotebookImageId() {
        return notebookImageId;
    }

    public NotebookImage getNotebookImage() {
        return notebookImage;
    }

    public void setNotebookImage(NotebookImage notebookImage) {
        this.notebookImage = notebookImage;
    }

    public ImageBuildStatus getStatus() {
        return status;
    }

    public void setStatus(ImageBuildStatus status) {
        this.status = status;
    }

    public String getProgressStage() {
        return progressStage;
    }

    public void setProgressStage(String progressStage) {
        this.progressStage = progressStage;
    }

    public String getBuildLogs() {
        return buildLogs;
    }

    public void setBuildLogs(String buildLogs) {
        this.buildLogs = buildLogs;
    }

    public String getImageReference() {
        return imageReference;
    }

    public void setImageReference(String imageReference) {
        this.imageReference = imageReference;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getK8sJobName() {
        return k8sJobName;
    }

    public void setK8sJobName(String k8sJobName) {
        this.k8sJobName = k8sJobName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
