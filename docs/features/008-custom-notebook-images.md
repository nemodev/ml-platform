# Feature 008: Custom Notebook Images

> Lets users define custom Python environments, builds them in-cluster via Kaniko, and stores them in a Docker Distribution registry backed by MinIO.

## What & Why

The default notebook image includes a comprehensive ML stack, but users often need additional libraries (domain-specific packages, newer versions, custom internal tools). Feature 008 allows users to define a package list and Python version, then triggers an in-cluster Docker build without requiring Docker daemon access. We chose Kaniko because it runs as an unprivileged container — no Docker-in-Docker, no privileged pods. Built images are pushed to an in-cluster Docker Distribution registry that uses MinIO for storage (reusing existing infrastructure), and users can select their custom image when launching a workspace.

## Architecture

```
Portal (Create & Build)
    ↓ POST /api/v1/notebook-images → POST .../builds
Backend (NotebookImageService + ImageBuildService)
    ├── Create NotebookImage entity (PENDING)
    ├── Create ImageBuild entity (QUEUED)
    └── ImageBuildScheduler (10s interval)
        ↓ promote QUEUED → BUILDING if slot available
        ├── Generate Dockerfile (base image + pip install)
        ├── Create ConfigMap with Dockerfile + context
        └── Create K8s Job with Kaniko container
            ↓
Kaniko (gcr.io/kaniko-project/executor)
    ├── Build from generated Dockerfile
    └── Push to registry.ml-platform.svc:5000
        ↓
Docker Distribution (registry:2, MinIO-backed storage)
    ↓
JupyterHub (custom image selected at workspace launch)
    ↓ pre_spawn_hook overrides spawner.image
Notebook Pod (custom image with user's packages)
```

**Key decisions:**

- **Kaniko for in-cluster builds** — Apache 2.0 licensed, no Docker daemon needed, no privileged containers. Runs as a standard K8s Job. Alternative was Docker-in-Docker which requires privileged mode (security risk).
- **Docker Distribution as built-in registry** — CNCF project (`registry:2`), lightweight, stores layers in MinIO via S3 backend. No separate storage provisioning needed.
- **Database-level concurrency control** — Per-user limit (1 active build) and cluster-wide limit (3 concurrent builds) enforced via `SELECT FOR UPDATE` queries. Simple, no distributed locking.
- **FIFO build queue** — QUEUED builds are processed by `createdAt` order. `ImageBuildScheduler` runs every 10 seconds to promote the next queued build.
- **Platform constraints file** — `/opt/ml-platform/platform-constraints.txt` in the base image lists pinned platform packages. Custom builds use `pip install --constraint` to prevent users from breaking core dependencies (streamlit, jupyter-server-proxy, mlflow, etc.).
- **Scheduler-based polling** — `ImageBuildScheduler` has three jobs: process queue (10s), refresh active builds from K8s API (30s), and enforce timeouts (60s). No WebSocket complexity.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `service/NotebookImageService.java` | Image CRUD, deletion guards, Python version listing |
| Backend | `service/ImageBuildService.java` | Build trigger, Kaniko Job creation, log/progress parsing |
| Backend | `service/ImageBuildScheduler.java` | Queue processing, status refresh, timeout enforcement |
| Backend | `service/ContainerRegistryService.java` | Registry health check, image deletion, base image resolution |
| Backend | `controller/NotebookImageController.java` | REST endpoints for images, builds, logs |
| Backend | `config/NotebookImageConfig.java` | Registry, build, and base image configuration |
| Frontend | `features/notebook-images/notebook-images.component.ts` | Image list with 5s auto-refresh during builds |
| Frontend | `features/notebook-images/create-image-dialog.component.ts` | Python version, packages, extra pip index |
| Frontend | `features/notebook-images/build-detail.component.ts` | Progress stages, elapsed time, log viewer |
| Infra | `k8s/kserve/kaniko-rbac.yaml` | ServiceAccount + Role for Job/ConfigMap/Secret operations |
| Infra | `k8s/kserve/registry-deployment.yaml` | Docker Distribution with MinIO S3 storage backend |
| DB | `V009__create_notebook_images_and_builds.sql` | notebook_images + image_builds tables |
| DB | `V010__add_notebook_image_id_to_workspaces.sql` | FK from workspaces to custom images |

**Build job creation:** `ImageBuildService.createKanikoJob()` generates a Dockerfile on-the-fly: `FROM {base-image-for-python-version}`, `COPY platform-constraints.txt`, `COPY custom-requirements.txt`, `RUN pip install --constraint ... -r ...`. The Dockerfile and requirements are stored in a ConfigMap, mounted into the Kaniko container. An `initContainer` (busybox) resolves ConfigMap symlinks so Kaniko can read them. Image naming follows `custom/{userId-prefix}-{imageName}:{buildId-prefix}`.

**Progress stage detection:** `ImageBuildService.parseProgressStage()` heuristically parses Kaniko log output to determine the current stage: building base, installing packages, pushing image. The frontend's `build-detail.component.ts` mirrors this with a 5-step progress indicator (Queued → Building Base → Installing Packages → Pushing Image → Complete).

**Concurrency enforcement:** `ImageBuildService.triggerBuild()` counts QUEUED+BUILDING builds for the user (max 1) and globally (max 3). If limits are exceeded, the build stays QUEUED until a slot opens. `ImageBuildScheduler.processQueue()` picks the oldest QUEUED build every 10 seconds.

**Timeout enforcement:** `ImageBuildScheduler.enforceTimeouts()` checks for builds that have been BUILDING longer than `timeoutMinutes` (default 60) and cancels them. The K8s Job also has `activeDeadlineSeconds` as a hard limit.

## Challenges & Solutions

- **ConfigMap symlinks** — Kubernetes mounts ConfigMap files as symlinks. Kaniko can't follow them. Solution: a busybox `initContainer` copies files from the ConfigMap mount to a regular `emptyDir` volume before Kaniko starts.
- **Registry credentials for push** — Kaniko needs Docker auth to push. Solution: mount a K8s Secret with `.dockerconfigjson` at `/kaniko/.docker/config.json`. The deploy script generates the appropriate auth for the in-cluster registry.
- **Base image per Python version** — Different Python versions need different base images. `ContainerRegistryService.getBaseImageReference()` resolves the appropriate base image from configuration, supporting 3.10, 3.11, and 3.12.
- **In-use deletion guard** — `NotebookImageService.deleteImage()` checks if any workspace currently references the image and blocks deletion with a clear error message.

## Limitations

- **No package syntax pre-validation** — Invalid pip specs are only caught at build time (potentially after minutes of building). Frontend accepts any text.
- **Build logs stored in database TEXT column** — Kaniko logs can be 10-500KB. No truncation or external log storage. Large logs bloat the `image_builds` table.
- **Progress stage parsing is heuristic** — Relies on specific strings in Kaniko's log output. Changes in Kaniko's output format would break stage detection.
- **No shared/team images** — Images are user-scoped only. No mechanism for administrators to publish organization-wide custom images.
- **Image reference parsing is fragile** — `ImageBuildService.deleteImage()` uses string splitting to extract image name and tag, which fails for registries with paths containing `/`.
- **Silent failure on build trigger after image creation** — The create dialog's build trigger failure is swallowed; user sees success but may not realize the build wasn't queued.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| Docker-in-Docker (DinD) | Requires privileged containers — major security risk in shared clusters. |
| Buildah | Less Kubernetes-native than Kaniko. Kaniko is purpose-built for in-cluster builds. |
| BuildKit | More complex setup. Kaniko is simpler for the single-stage builds we need. |
| Pre-built image catalog | Doesn't allow user-defined packages. Users need custom environments for domain-specific libraries. |
| External registry (Docker Hub, Harbor) | Adds external dependency. In-cluster registry with MinIO storage is self-contained. |
| conda environments (no Docker build) | JupyterHub KubeSpawner expects Docker images. conda doesn't change the container image. |

## Potential Improvements

- **Package pre-validation** — Validate requirements.txt syntax before submitting the build job to fail fast on obvious errors.
- **Build log streaming** — Stream Kaniko logs to the frontend in real-time instead of polling the database.
- **External log storage** — Move build logs from PostgreSQL to MinIO/S3 to reduce database bloat.
- **Shared image library** — Allow admins to publish curated images available to all users.
- **Image layer caching** — Configure Kaniko's layer cache to speed up rebuilds when only a few packages change.
- **Notification on build completion** — The notification endpoint is referenced in the API contract but not fully implemented. Complete it for toast notifications.
