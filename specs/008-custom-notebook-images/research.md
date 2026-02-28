# Research: Custom Notebook Images

**Feature Branch**: `008-custom-notebook-images`
**Date**: 2026-02-27

## R1: In-Cluster Image Builder (Kaniko)

**Decision**: Use Kaniko (gcr.io/kaniko-project/executor) run as Kubernetes Jobs for in-cluster image builds.

**Rationale**:
- Apache 2.0 license — commercial use permitted without code modification.
- Builds container images from a Dockerfile inside a Kubernetes pod without requiring a Docker daemon or privileged containers.
- Well-established pattern: create a K8s Job with the Kaniko executor container, mount a Dockerfile (via ConfigMap), and configure the destination registry.
- Fits the existing platform pattern — KServeService already creates K8s custom resources via the Kubernetes Java client. Image build jobs follow the same model but use the Batch API (`batch/v1` Jobs) instead of CustomObjectsApi.

**Alternatives considered**:
- **Buildah**: Requires privileged or `--device /dev/fuse` mounts; more complex rootless setup on K8s.
- **BuildKit (moby/buildkit)**: Needs a long-running daemon pod; adds operational overhead. Better suited for CI/CD clusters.
- **Docker-in-Docker**: Requires privileged containers — security concern in multi-tenant clusters.

**Key implementation details**:
- Kaniko executor image: `gcr.io/kaniko-project/executor:latest` (or pin a specific version; Chainguard fork `cgr.dev/chainguard/kaniko` is an alternative).
- Build context: A dynamically generated Dockerfile passed as a ConfigMap volume mount.
- Destination: `--destination=<registry>/<namespace>/<image>:<tag>` flag on the Kaniko container.
- Registry auth: Mounted as a Docker config.json Secret at `/kaniko/.docker/config.json`.
- Cache: `--cache=true --cache-repo=<registry>/cache` for layer caching (optional, improves rebuild speed).
- Timeout enforcement: K8s Job `activeDeadlineSeconds: 3600` (60 minutes per spec).
- Resource limits: CPU 1, Memory 2Gi (configurable; pip installs with compilation can be memory-intensive).

**Build Job monitoring**:
- Poll K8s Job status via `batchV1Api.readNamespacedJobStatus()` — check `.status.conditions` for `Complete` or `Failed`.
- Retrieve build logs via `coreV1Api.readNamespacedPodLog()` for the Job's pod.
- Progress detection: Parse Kaniko stdout for stage markers (`STEP N/M`, `RUN pip install`, `PUSH`).

---

## R2: Container Registry Deployment

**Decision**: Deploy Docker Distribution (CNCF Distribution, formerly Docker Registry v2) as the built-in in-cluster registry. Support external registries via backend configuration.

**Rationale**:
- Lightweight (~50MB image, minimal resource usage).
- CNCF project, Apache 2.0 licensed, widely deployed.
- No database dependency — stores image layers on local PVC or S3-compatible storage.
- The platform already has MinIO; the registry can use MinIO as its storage backend, avoiding a dedicated PVC.
- Helm chart available: `docker.io/library/registry:2` with simple deployment.

**Alternatives considered**:
- **Harbor**: Full-featured (RBAC, vulnerability scanning, replication). Rejected per user request — too heavy for this use case. Requires its own PostgreSQL, Redis, and multiple pods.
- **Zot**: OCI-native, lightweight. Viable alternative but less ecosystem support. Could be a future option.
- **ttl.sh**: Already used for r1 ephemeral images. Not suitable for persistent custom images.

**Built-in registry architecture**:
- Deployment: Single-pod deployment in `ml-platform` namespace.
- Service: ClusterIP on port 5000 (`registry.ml-platform.svc:5000`).
- Storage: MinIO backend (`s3://ml-platform/registry/`) — leverages existing MinIO deployment, no additional PVC needed.
- No authentication for in-cluster access (pods within the cluster can push/pull freely).
- For node-level image pulls (JupyterHub singleuser pods), an imagePullSecret with the registry endpoint must be configured.

**External registry support**:
- Backend `application.yaml` configuration under `services.notebook-images.registry.*`.
- When `type: external`, all image operations (Kaniko push, JupyterHub pull) target the external endpoint.
- Credentials stored in K8s Secret, referenced by both Kaniko Jobs and JupyterHub imagePullSecrets.
- Validation on startup: Backend checks registry reachability via Docker Registry v2 API (`GET /v2/`). Falls back to built-in on failure with warning log.

---

## R3: JupyterHub Custom Image Override

**Decision**: Use JupyterHub's REST API `POST /users/{name}/servers/{server_name}` with a body containing `image` field to override the singleuser image per named server.

**Rationale**:
- The JupyterHub REST API (v1) supports passing spawn options in the POST body when starting a named server. The `kubespawner` respects `profile` and `image` overrides when `allow_override` is configured.
- The existing `JupyterHubService.spawnNamedServer()` already calls this endpoint — it just needs to pass an optional `image` field in the request body.
- No Helm values changes needed at spawn time; the override is per-server.

**Helm configuration required**:
- Add `imagePullSecrets` to JupyterHub singleuser configuration to allow pulling from the in-cluster or external registry.
- Enable `KubeSpawner.image` override in hub config: `c.KubeSpawner.image = ''` with `allow_override` for the image field.

**Alternative considered**:
- `profileList` with multiple profiles: More complex to manage dynamically. Profiles are defined in Helm values at deploy time, not suitable for user-created images that change at runtime.

---

## R4: Build Progress and Notifications

**Decision**: Use polling (consistent with existing platform patterns) for build progress. Add a lightweight notification polling endpoint for build completion events.

**Rationale**:
- The platform currently has no WebSocket or SSE infrastructure. All real-time updates (workspace status, deployment status, pipeline runs) use timer-based HTTP polling.
- Adding WebSocket/SSE infrastructure for one feature would be inconsistent and over-engineered.
- Polling intervals: 5 seconds for active build detail page, 30 seconds for global notification check.

**Progress detection approach**:
- The backend periodically polls the Kaniko build Job status and pod logs.
- Parse Kaniko output for recognizable stages: "Unpacking base image", "RUN pip install", "Pushing image".
- Store the current stage and a log tail in the ImageBuild entity.
- Frontend polls `GET /api/v1/notebook-images/{id}/builds/{buildId}` for status, stage, and elapsed time.

**Notification approach**:
- New endpoint: `GET /api/v1/notifications?since={timestamp}` returns recent events (build complete, build failed).
- The app shell (app.component) polls this every 30 seconds.
- Frontend renders notifications as a toast/banner component.
- Notifications are ephemeral (not persisted beyond the response) — they are derived from build status changes since the last poll timestamp.

**Alternative considered**:
- Server-Sent Events (SSE): More responsive, but requires new infrastructure pattern. Deferred to a future platform-wide notification feature.

---

## R5: Base Image Strategy for Multiple Python Versions

**Decision**: Build per-Python-version base images from the existing notebook Dockerfile, parameterized by Python version. Store them in the registry alongside custom images.

**Rationale**:
- The current Dockerfile uses `jupyter/scipy-notebook:python-3.11` as its base. Jupyter Docker Stacks publishes tags for Python 3.10, 3.11, and 3.12.
- The existing notebook Dockerfile can be parameterized with a `--build-arg PYTHON_VERSION=3.xx` to produce `ml-platform-notebook-base:python-3.10`, `python-3.11`, `python-3.12`.
- These base images include all platform tooling (Spark, MLflow, Delta Lake, MinIO jars) and are built by the platform team, not users.
- Custom images use `FROM ml-platform-notebook-base:python-X.XX` and only add `pip install` commands.

**Build pipeline update**:
- `build-images.sh` gains a `--python-version` flag (or builds all 3 versions).
- Base images are tagged: `<registry>/ml-platform/notebook-base:python-3.10`, etc.
- The default platform notebook image remains `ml-platform-notebook:latest` (Python 3.11) for backward compatibility.

---

## R6: Concurrency Control

**Decision**: Use database-level locking (SELECT FOR UPDATE) for per-user build limits and a cluster-wide counter for the 3-build cap.

**Rationale**:
- The backend is the single orchestrator for build jobs. All build requests go through the backend API.
- Per-user limit (1 active build): Query `ImageBuild` where `userId = X AND status IN (QUEUED, BUILDING)`. If count >= 1, reject or queue.
- Cluster-wide limit (3 active builds): Query `ImageBuild` where `status IN (QUEUED, BUILDING)`. If count >= 3, queue with position info.
- Use `@Transactional` with `SELECT ... FOR UPDATE` to prevent race conditions on concurrent submissions.

**Queue implementation**:
- QUEUED builds are processed in FIFO order by `createdAt`.
- A scheduled task (`@Scheduled(fixedDelay = 10000)`) checks for QUEUED builds and promotes them to BUILDING when slots are available.
- The scheduler also checks for timed-out builds (startedAt + 60 min < now) and cancels them.

---

## R7: Registry Image Pull Secrets for JupyterHub

**Decision**: Create a Kubernetes Secret of type `kubernetes.io/dockerconfigjson` in the JupyterHub namespace, referenced by JupyterHub's `singleuser.imagePullSecrets` Helm value.

**Rationale**:
- When JupyterHub spawns a singleuser pod with a custom image from the in-cluster or external registry, kubelet needs credentials to pull the image.
- For the built-in registry (no auth within cluster): The imagePullSecret can contain a dummy credential or the registry can be configured as an insecure registry on cluster nodes.
- For external registries: Real credentials are required, sourced from the backend configuration.
- The Secret is created/updated by the deployment script or the backend on startup.

**Implementation**:
- Deployment script creates a `registry-credentials` Secret in the `ml-platform` namespace.
- JupyterHub values patched: `singleuser.imagePullSecrets: [{name: "registry-credentials"}]`.
- Kaniko Jobs mount the same Secret at `/kaniko/.docker/config.json` for push access.
