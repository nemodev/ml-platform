# Feature 002: JupyterHub Notebook Embedding

> Embeds isolated JupyterHub notebook servers into the Angular portal with SSO passthrough, named server isolation, and a five-state workspace lifecycle.

## What & Why

Data scientists need interactive notebooks integrated into the platform — not a separate application with its own login. We embed JupyterHub via iframe so users get a seamless experience: click a tab, get a notebook environment pre-configured with all ML libraries. Each analysis gets its own named JupyterHub server, providing file isolation without per-user overhead. JupyterHub's GenericOAuthenticator delegates to the same Keycloak realm from Feature 001, so there's no secondary login. This feature establishes two patterns that repeat across later features: the **workspace lifecycle state machine** (PENDING→RUNNING→IDLE→STOPPED/FAILED) and the **iframe embedding approach**.

## Architecture

```
Angular Portal
    ↓ (iframe: /user/{username}/{serverName}/lab)
nginx reverse proxy
    ↓ (proxy_hide_header CSP + frame-ancestors 'self')
JupyterHub Proxy (Z2JH Helm)
    ↓
KubeSpawner → Named Server Pod (per analysis)
    ├── main: ml-platform-notebook:latest (Python 3.11, ML libs, Java 17, Spark 4.0.1)
    └── sidecar: s3fs-fuse (mounts analysis S3 prefix → /home/jovyan/work)
```

**Key decisions:**

- **Z2JH Helm chart** — The official Zero to JupyterHub Helm chart bundles KubeSpawner, an idle culler, and a configurable proxy. We chose it over custom K8s manifests because it handles the complexity of multi-user notebook management.
- **Named servers per analysis** — Each analysis UUID becomes a JupyterHub server name (`/user/{username}/{analysisId}`). Users can have multiple analyses running concurrently, each with independent file systems and kernel state.
- **S3-backed workspace storage via s3fs-fuse sidecar** — Each analysis gets its own S3 prefix (`analysis/{username}/{analysisId}/`) mounted as a POSIX filesystem at `/home/jovyan/work` via an s3fs-fuse sidecar container. This replaces the original PVC-based storage, providing per-analysis file isolation and persistence independent of pod lifecycle. The sidecar uses `emptyDir` with mount propagation (`Bidirectional` on sidecar, `HostToContainer` on main container) and creates a ready sentinel (`/mnt/ready/.done`) before Jupyter starts. Default notebooks are seeded into S3 on analysis creation by `WorkspaceContentSeeder`.
- **Workspace lifecycle state machine** — Backend maintains `Workspace` entity status that reconciles with JupyterHub API on each request. States: PENDING (spawning), RUNNING (ready, active), IDLE (ready, inactive), STOPPED (terminated), FAILED (error). On launch, stale DB records from cluster restarts are detected and marked STOPPED before spawning.
- **GenericOAuthenticator + auto_login** — JupyterHub authenticates via Keycloak OIDC. `auto_login: true` means users with an active Keycloak session skip the JupyterHub login page entirely.
- **Single notebook image** — One Docker image (`ml-platform-notebook:latest`) serves as JupyterHub user servers, Airflow pipeline workers, Spark executors, and the data provisioning job. This ensures environment parity across the platform.
- **Relative iframe URLs** — `JupyterHubService` returns relative paths (`/user/{username}/{server}/lab`) so the iframe works across localhost, NodePort, and production domains without URL rewriting.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `service/WorkspaceService.java` | Lifecycle management, status reconciliation, spawn options |
| Backend | `service/JupyterHubService.java` | JupyterHub REST API client (two WebClients: hub + proxy) |
| Backend | `controller/WorkspaceController.java` | Analysis-scoped REST endpoints |
| Backend | `model/Workspace.java` | JPA entity with WorkspaceStatus enum |
| Frontend | `features/notebooks/notebooks.component.ts` | Iframe loading, bridge init, polling, profile/image switching |
| Frontend | `core/services/workspace.service.ts` | HTTP client with `watchStatusUntilStable()` polling |
| Backend | `service/WorkspaceContentSeeder.java` | Seeds default notebooks into S3 on analysis creation |
| Infra | `helm/jupyterhub/values.yaml` | Z2JH config: OAuth, idle culler, named servers, s3fs sidecar |
| Infra | `docker/notebook-image/Dockerfile` | scipy-notebook + Java + Spark + ML libs + JupyterLab customization |
| Infra | `docker/s3fs-sidecar/Dockerfile` | Alpine-based s3fs-fuse sidecar image |
| Infra | `nginx/nginx.conf.template` | CSP override for iframe embedding (`frame-ancestors 'self'`) |

**State reconciliation at launch:** Before creating a new workspace, `WorkspaceService.launchWorkspace()` queries JupyterHub for each "active" DB record. If JupyterHub reports the server as stopped (e.g., after a Helm upgrade that killed all user pods), the DB record is marked STOPPED. This prevents CONFLICT errors from stale state and makes the system self-healing across cluster restarts.

**SSO re-auth detection:** When the iframe loads, `notebooks.component.ts` inspects the content for "403 Forbidden" text. If detected (stale session for a different user), it redirects through `/hub/logout?next={workspace-url}` which triggers a fresh Keycloak OAuth flow. A `reauthAttempted` flag prevents infinite loops.

**Idle culling:** The Z2JH idle culler terminates servers after 30 minutes of inactivity (checked every 5 minutes). Named servers are automatically removed, keeping cluster resources available.

## Challenges & Solutions

- **Stale workspace records after cluster restart** — JupyterHub pods die but DB still says RUNNING. Solution: reconcile DB with JupyterHub API on every launch attempt; auto-mark orphaned records as STOPPED.
- **CSP for iframe embedding** — JupyterHub's singleuser OAuth proxy sends `Content-Security-Policy: frame-ancestors 'none'` on redirect responses, blocking iframe loading even when the hub itself has correct CSP. Solution: nginx-level override with `proxy_hide_header Content-Security-Policy` + `add_header "frame-ancestors 'self'" always` on the `/user/` proxy location. This is applied in three files: `frontend/nginx/nginx.conf.template`, the k8s base ConfigMap, and the installer template.
- **s3fs-fuse sidecar mount timing** — The FUSE mount must complete before Jupyter starts, otherwise the file browser shows an empty directory. Solution: the sidecar creates a sentinel file (`/mnt/ready/.done`) after successful mount, and the main container's `spawner.cmd` waits for this sentinel before launching `jupyterhub-singleuser`.
- **s3fs-fuse on Alpine with MinIO** — Several s3fs options are required for MinIO compatibility: `use_path_request_style` (path-style S3 access), `compat_dir` (virtual S3 prefixes don't exist as objects), `uid=1000,gid=100,umask=0022` (FUSE mounts default to root; notebooks run as jovyan uid 1000). The `use_http` and `nocheckbucket` options are NOT supported on Alpine s3fs v1.94.
- **Hardcoded Keycloak authorize URL** — JupyterHub's `authorize_url` must be browser-accessible (not in-cluster DNS). Currently hardcoded to the r1 cluster IP `172.16.100.10:30080`. Token/userdata URLs correctly use in-cluster DNS since they're server-to-server.
- **Thread.sleep in JupyterHubService** — `fetchContents()` retries with `Thread.sleep(200)` on transient failures. This blocks the executor thread; a reactive retry would be better.

## Limitations

- **No hot-resize of resources** — Changing the compute profile requires a workspace restart. JupyterHub's KubeSpawner doesn't support live resource changes.
- **s3fs latency** — S3 FUSE mounts have higher latency than local disk for small random reads. Acceptable for notebook workflows but not ideal for heavy I/O workloads.
- **Privileged sidecar** — The s3fs sidecar requires `securityContext: {privileged: true}` for FUSE. This is a security trade-off mitigated by the sidecar running a minimal Alpine image with no shell access from the main container.
- **Hardcoded Keycloak URL in Helm values** — The authorize URL uses a specific IP, breaking portability across deployment contexts.
- **Kernel status is polled, not streamed** — Frontend polls every 5 seconds. WebSocket-based kernel status would be more responsive but adds complexity.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| JupyterHub as standalone (no iframe) | Breaks unified portal experience. Users would manage two separate apps. |
| ContainDS Dashboards | Limited community support. Z2JH is the standard Kubernetes deployment for JupyterHub. |
| BinderHub | Designed for ephemeral environments, not persistent user workspaces. |
| Direct KubeSpawner (no Helm) | Requires reimplementing idle culling, proxy management, and OAuth setup. Z2JH handles all of this. |
| Per-user servers (not named) | Each user gets one server. Named servers allow per-analysis isolation — critical for Feature 007. |

## Potential Improvements

- **WebSocket kernel status** — Replace 5-second polling with JupyterHub's WebSocket API for real-time kernel state changes.
- **Parameterize Keycloak URLs** — Use Helm values or environment variables for the authorize URL to support multi-environment deployments.
- **S3 storage quotas** — Monitor per-analysis S3 prefix size and enforce storage limits per user or analysis.
- **Warm pool of pre-spawned servers** — Reduce cold-start time by maintaining a pool of idle notebook pods ready for assignment.
- **Graceful session handoff** — Instead of 403 detection + redirect, use JupyterHub's session management to handle user switches cleanly.
- **CSI driver for S3** — Replace the privileged s3fs sidecar with an S3 CSI driver (e.g., `mountpoint-s3-csi-driver`) for a Kubernetes-native, non-privileged mount approach.
