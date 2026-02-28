# Quickstart: Custom Notebook Images

**Feature Branch**: `008-custom-notebook-images`
**Date**: 2026-02-27

## Prerequisites

- Existing ML Platform deployed (Features 001-007 complete)
- Kubernetes cluster with sufficient resources for build jobs (1 CPU / 2Gi per build, up to 3 concurrent)
- Container registry accessible from the cluster (built-in deployed by default, or external configured)

## Key Files to Create or Modify

### Backend (Spring Boot 3.5.x, Java 21)

**New files**:

| File | Purpose |
|------|---------|
| `model/NotebookImage.java` | JPA entity for image definitions |
| `model/ImageBuild.java` | JPA entity for build attempts |
| `model/NotebookImageStatus.java` | Enum: PENDING, BUILDING, READY, FAILED |
| `model/ImageBuildStatus.java` | Enum: QUEUED, BUILDING, SUCCEEDED, FAILED, CANCELLED |
| `dto/NotebookImageDto.java` | Response record for image list |
| `dto/NotebookImageDetailDto.java` | Response record for image detail |
| `dto/ImageBuildDto.java` | Response record for build info |
| `dto/ImageBuildDetailDto.java` | Response record with elapsed time |
| `dto/CreateNotebookImageRequest.java` | Request record for image creation |
| `dto/UpdateNotebookImageRequest.java` | Request record for image update |
| `dto/NotificationDto.java` | Response record for notification events |
| `repository/NotebookImageRepository.java` | JPA repository |
| `repository/ImageBuildRepository.java` | JPA repository |
| `controller/NotebookImageController.java` | REST endpoints for /api/v1/notebook-images |
| `controller/NotificationController.java` | REST endpoint for /api/v1/notifications |
| `service/NotebookImageService.java` | Business logic: CRUD, deletion guards, image reference management |
| `service/ImageBuildService.java` | Build orchestration: trigger, poll K8s Jobs, update status |
| `service/ImageBuildScheduler.java` | Scheduled task: promote queued builds, enforce timeouts |
| `service/ContainerRegistryService.java` | Registry abstraction: health check, image deletion |
| `config/NotebookImageConfig.java` | `@ConfigurationProperties` for `services.notebook-images.*` |
| `exception/ImageBuildUnavailableException.java` | Runtime exception for build infrastructure failures |
| `db/migration/V009__create_notebook_images_and_builds.sql` | Flyway migration |

**Modified files**:

| File | Change |
|------|--------|
| `service/JupyterHubService.java` | Add image override parameter to `spawnNamedServer()` |
| `service/WorkspaceService.java` | Accept optional `notebookImageId` in launch, resolve image reference, pass to JupyterHub |
| `controller/WorkspaceController.java` | Add optional `notebookImageId` field to `LaunchWorkspaceRequest` |
| `config/GlobalExceptionHandler.java` | Handle `ImageBuildUnavailableException` → 503 |
| `resources/application.yaml` | Add `services.notebook-images.*` configuration block |

### Frontend (Angular 17, TypeScript 5.4+)

**New files**:

| File | Purpose |
|------|---------|
| `core/services/notebook-image.service.ts` | HTTP service for notebook image API |
| `core/services/notification.service.ts` | Global notification polling service |
| `features/notebook-images/notebook-images.component.ts` | Image list page |
| `features/notebook-images/notebook-images.component.html` | Image list template |
| `features/notebook-images/notebook-images.component.scss` | Image list styles |
| `features/notebook-images/create-image-dialog/` | Dialog for creating/editing image definitions |
| `features/notebook-images/build-detail/` | Build progress and log viewer |
| `shared/notification-banner/notification-banner.component.ts` | Toast/banner for build notifications |

**Modified files**:

| File | Change |
|------|--------|
| `app.routes.ts` | Add `/notebook-images` route with lazy loading |
| `app.component.ts` | Integrate notification polling and banner display |
| `features/notebooks/notebooks.component.ts` | Add image selector to workspace launch flow |
| `features/notebooks/notebooks.component.html` | Add dropdown/select for custom image before launch |

### Infrastructure

**New files**:

| File | Purpose |
|------|---------|
| `helm/registry/values.yaml` | Docker Distribution (Registry v2) Helm values |
| `k8s/platform/base/registry-deployment.yaml` | Registry Deployment manifest |
| `k8s/platform/base/registry-service.yaml` | Registry ClusterIP Service (port 5000) |
| `k8s/platform/base/registry-credentials-secret.yaml` | Template for imagePullSecret |
| `k8s/platform/base/kaniko-rbac.yaml` | ServiceAccount + RBAC for build jobs |

**Modified files**:

| File | Change |
|------|--------|
| `docker/notebook-image/Dockerfile` | Parameterize Python version with `ARG PYTHON_VERSION` |
| `helm/jupyterhub/values.yaml` | Add `imagePullSecrets` to singleuser config, add spawner image override config |
| `installer/config.env` | Add registry configuration variables |
| `installer/install.sh` | Add registry deployment step |
| `installer/build-images.sh` | Add base image building for multiple Python versions |
| `scripts/deploy-full-stack.sh` | Add registry deployment, registry credential creation |

## Development Workflow

1. **Start with infrastructure**: Deploy the built-in registry, verify it accepts push/pull.
2. **Build base images**: Parameterize the notebook Dockerfile, build per-Python-version bases.
3. **Backend entities + migration**: Create DB tables, JPA entities, repositories.
4. **Backend CRUD API**: Image definition endpoints (create, list, get, update, delete).
5. **Backend build orchestration**: Kaniko Job creation, status polling, log retrieval.
6. **Backend scheduler**: Queue processing, timeout enforcement.
7. **Backend workspace integration**: Modify JupyterHubService to pass image override.
8. **Frontend image management**: List, create dialog, build detail page.
9. **Frontend workspace integration**: Image selector in notebook launch.
10. **Frontend notifications**: Global poll + toast banner.
11. **End-to-end testing**: Create image → build → launch workspace → verify packages.

## Verification

```bash
# 1. Verify registry is running
kubectl -n ml-platform get pods -l app=registry

# 2. Verify base images exist in registry
curl -s http://registry.ml-platform.svc:5000/v2/_catalog

# 3. Create an image definition via API
curl -X POST http://localhost:8080/api/v1/notebook-images \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"test-env","pythonVersion":"3.11","packages":"requests\nnumpy"}'

# 4. Trigger a build
curl -X POST http://localhost:8080/api/v1/notebook-images/$IMAGE_ID/builds \
  -H "Authorization: Bearer $TOKEN"

# 5. Poll build status
curl http://localhost:8080/api/v1/notebook-images/$IMAGE_ID/builds/$BUILD_ID \
  -H "Authorization: Bearer $TOKEN"

# 6. Launch workspace with custom image
curl -X POST http://localhost:8080/api/v1/analyses/$ANALYSIS_ID/workspaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"profile":"exploratory","notebookImageId":"$IMAGE_ID"}'
```
