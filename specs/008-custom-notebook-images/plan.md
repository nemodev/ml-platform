# Implementation Plan: Custom Notebook Images

**Branch**: `008-custom-notebook-images` | **Date**: 2026-02-27 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/008-custom-notebook-images/spec.md`

## Summary

Enable users to define custom notebook environments by selecting a Python version and a package list, which the platform builds into a container image using in-cluster Kaniko Jobs. A lightweight Docker Distribution registry is deployed by default; external registries are supported via backend configuration. Users monitor build progress through polling-based status updates and receive in-app notifications on completion. Custom images integrate with the existing workspace launch flow via JupyterHub spawner image override.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17, Kubernetes Java Client (io.kubernetes.client), Kaniko (gcr.io/kaniko-project/executor), Docker Distribution (registry:2)
**Storage**: PostgreSQL (entities: notebook_images, image_builds), Container Registry (built images), MinIO (registry storage backend)
**Testing**: Spring Boot integration tests (JUnit 5 + MockMvc), Angular component tests (Jasmine/Karma), end-to-end K8s verification
**Target Platform**: Kubernetes (Rancher Desktop ARM local, amd64 remote r1 cluster)
**Project Type**: Web (backend + frontend + infrastructure)
**Performance Goals**: Image build completes within 60 minutes; build status polling every 5s on detail page; notification polling every 30s globally
**Constraints**: Max 1 concurrent build per user, 3 cluster-wide; no WebSocket/SSE (polling only, consistent with existing platform patterns)
**Scale/Scope**: 2 test users, tens of custom images, 3 concurrent builds max

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. MVP-First Incremental Delivery | PASS | Feature is independent increment. All 7 prior features are complete. Feature 002 (JupyterHub) is modified with backward-compatible changes only. |
| II. Infrastructure as Code | PASS | Registry deployment, Kaniko RBAC, and imagePullSecrets are all Kubernetes manifests under infrastructure/. No manual kubectl operations. |
| III. Unified Authentication | PASS | All API endpoints protected by Keycloak JWT. Image ownership enforced by userId from JWT claims. No separate auth mechanism. |
| IV. Environment Parity | PASS (with scope note) | Custom images are for notebooks only (per spec). Airflow/Spark executor images remain the default platform image. This is an explicit scope boundary, not a parity violation. |
| V. Testing at System Boundaries | PASS | Plan includes end-to-end verification: API → Kaniko Job → Registry → JupyterHub spawn. Integration tests for K8s Job creation and registry push. |
| VI. Production-Quality Within Scope | PASS | Follows existing patterns: JPA entities with @PrePersist, service layer orchestration, K8s client for Job management (same pattern as KServeService), proper exception hierarchy. |

**Post-Phase 1 Re-check**: All principles remain satisfied. The data model follows existing entity patterns (UUID PKs, status enums, timestamp fields). API contracts follow existing REST conventions. No new frameworks or libraries introduced beyond those already in use.

## Project Structure

### Documentation (this feature)

```text
specs/008-custom-notebook-images/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0: technology decisions
├── data-model.md        # Phase 1: entity definitions and migration
├── quickstart.md        # Phase 1: development guide
├── contracts/
│   └── api.yaml         # Phase 1: OpenAPI 3.1 contract
├── checklists/
│   └── requirements.md  # Spec quality validation
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/mlplatform/
│   ├── model/
│   │   ├── NotebookImage.java          # NEW: Image definition entity
│   │   ├── NotebookImageStatus.java    # NEW: PENDING/BUILDING/READY/FAILED
│   │   ├── ImageBuild.java             # NEW: Build attempt entity
│   │   └── ImageBuildStatus.java       # NEW: QUEUED/BUILDING/SUCCEEDED/FAILED/CANCELLED
│   ├── dto/
│   │   ├── NotebookImageDto.java       # NEW: List response record
│   │   ├── NotebookImageDetailDto.java # NEW: Detail response record
│   │   ├── ImageBuildDto.java          # NEW: Build response record
│   │   ├── ImageBuildDetailDto.java    # NEW: Build detail with elapsed time
│   │   ├── CreateNotebookImageRequest.java  # NEW
│   │   ├── UpdateNotebookImageRequest.java  # NEW
│   │   └── NotificationDto.java        # NEW: Notification event record
│   ├── repository/
│   │   ├── NotebookImageRepository.java # NEW
│   │   └── ImageBuildRepository.java    # NEW
│   ├── controller/
│   │   ├── NotebookImageController.java # NEW: /api/v1/notebook-images
│   │   ├── NotificationController.java  # NEW: /api/v1/notifications
│   │   └── WorkspaceController.java     # MODIFIED: add notebookImageId to launch request
│   ├── service/
│   │   ├── NotebookImageService.java    # NEW: CRUD + deletion guards
│   │   ├── ImageBuildService.java       # NEW: Build orchestration via K8s Jobs
│   │   ├── ImageBuildScheduler.java     # NEW: Queue processing + timeout enforcement
│   │   ├── ContainerRegistryService.java # NEW: Registry health + image deletion
│   │   ├── WorkspaceService.java        # MODIFIED: resolve image reference for launch
│   │   └── JupyterHubService.java       # MODIFIED: accept image override in spawn
│   ├── config/
│   │   ├── NotebookImageConfig.java     # NEW: @ConfigurationProperties
│   │   └── GlobalExceptionHandler.java  # MODIFIED: handle ImageBuildUnavailableException
│   └── exception/
│       └── ImageBuildUnavailableException.java # NEW
├── src/main/resources/
│   ├── application.yaml                 # MODIFIED: add services.notebook-images.*
│   └── db/migration/
│       └── V009__create_notebook_images_and_builds.sql # NEW

frontend/
├── src/app/
│   ├── core/services/
│   │   ├── notebook-image.service.ts    # NEW: HTTP client for image API
│   │   └── notification.service.ts      # NEW: Global notification polling
│   ├── features/
│   │   ├── notebook-images/
│   │   │   ├── notebook-images.component.ts    # NEW: Image list page
│   │   │   ├── notebook-images.component.html  # NEW
│   │   │   ├── notebook-images.component.scss  # NEW
│   │   │   ├── create-image-dialog/            # NEW: Create/edit dialog
│   │   │   └── build-detail/                   # NEW: Build progress viewer
│   │   └── notebooks/
│   │       ├── notebooks.component.ts           # MODIFIED: image selector
│   │       └── notebooks.component.html         # MODIFIED: dropdown for image
│   ├── shared/
│   │   └── notification-banner/                 # NEW: Toast component
│   ├── app.routes.ts                            # MODIFIED: add /notebook-images route
│   └── app.component.ts                         # MODIFIED: notification polling

infrastructure/
├── k8s/platform/base/
│   ├── registry-deployment.yaml         # NEW: Docker Distribution pod
│   ├── registry-service.yaml            # NEW: ClusterIP on port 5000
│   ├── registry-credentials-secret.yaml # NEW: imagePullSecret template
│   ├── kaniko-rbac.yaml                 # NEW: SA + Role for build jobs
│   └── kustomization.yaml              # MODIFIED: add new resources
├── helm/jupyterhub/
│   └── values.yaml                      # MODIFIED: imagePullSecrets, spawner config
├── docker/notebook-image/
│   └── Dockerfile                       # MODIFIED: ARG PYTHON_VERSION parameterization
├── installer/
│   ├── config.env                       # MODIFIED: registry config vars
│   ├── install.sh                       # MODIFIED: registry deployment step
│   └── build-images.sh                  # MODIFIED: multi-Python base image builds
└── scripts/
    └── deploy-full-stack.sh             # MODIFIED: registry deployment + credentials
```

**Structure Decision**: Follows the existing web application structure with backend/, frontend/, infrastructure/ directories. All new backend code is organized into the existing package hierarchy (model, dto, repository, controller, service, config, exception). No new top-level directories needed.

## Complexity Tracking

No constitution violations to justify. All patterns follow existing codebase conventions:
- K8s Job management mirrors KServeService's CustomObjectsApi pattern (using BatchV1Api instead).
- Scheduled task for build queue follows standard Spring `@Scheduled` pattern.
- Polling-based notifications are consistent with existing workspace/deployment status patterns.
- No new frameworks, libraries, or architectural patterns introduced.
