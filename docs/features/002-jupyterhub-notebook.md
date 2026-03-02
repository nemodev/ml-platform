# Feature 002: JupyterHub Notebook Embedding

> Embeds isolated JupyterHub notebook servers into the Angular portal with SSO passthrough, named server isolation, and a five-state workspace lifecycle.

## What & Why

Data scientists need interactive notebooks integrated into the platform — not a separate application with its own login. We embed JupyterHub via iframe so users get a seamless experience: click a tab, get a notebook environment pre-configured with all ML libraries. Each analysis gets its own named JupyterHub server, providing file isolation without per-user overhead. JupyterHub's GenericOAuthenticator delegates to the same Keycloak realm from Feature 001, so there's no secondary login. This feature establishes two patterns that repeat across later features: the **workspace lifecycle state machine** (PENDING→RUNNING→IDLE→STOPPED/FAILED) and the **iframe embedding approach**.

## Architecture

```
Angular Portal
    ↓ (iframe: /user/{username}/{serverName}/lab)
JupyterHub Proxy (Z2JH Helm)
    ↓
KubeSpawner → Named Server Pod (per analysis)
    ↓
ml-platform-notebook:latest image
    (Python 3.11, ML libs, Java 17, Spark 4.0.1)
```

**Key decisions:**

- **Z2JH Helm chart** — The official Zero to JupyterHub Helm chart bundles KubeSpawner, an idle culler, and a configurable proxy. We chose it over custom K8s manifests because it handles the complexity of multi-user notebook management.
- **Named servers per analysis** — Each analysis UUID becomes a JupyterHub server name (`/user/{username}/{analysisId}`). Users can have multiple analyses running concurrently, each with independent file systems and kernel state.
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
| Infra | `helm/jupyterhub/values.yaml` | Z2JH config: OAuth, CSP, idle culler, named servers |
| Infra | `docker/notebook-image/Dockerfile` | scipy-notebook + Java + Spark + ML libs + JupyterLab customization |

**State reconciliation at launch:** Before creating a new workspace, `WorkspaceService.launchWorkspace()` queries JupyterHub for each "active" DB record. If JupyterHub reports the server as stopped (e.g., after a Helm upgrade that killed all user pods), the DB record is marked STOPPED. This prevents CONFLICT errors from stale state and makes the system self-healing across cluster restarts.

**SSO re-auth detection:** When the iframe loads, `notebooks.component.ts` inspects the content for "403 Forbidden" text. If detected (stale session for a different user), it redirects through `/hub/logout?next={workspace-url}` which triggers a fresh Keycloak OAuth flow. A `reauthAttempted` flag prevents infinite loops.

**Idle culling:** The Z2JH idle culler terminates servers after 30 minutes of inactivity (checked every 5 minutes). Named servers are automatically removed, keeping cluster resources available.

## Challenges & Solutions

- **Stale workspace records after cluster restart** — JupyterHub pods die but DB still says RUNNING. Solution: reconcile DB with JupyterHub API on every launch attempt; auto-mark orphaned records as STOPPED.
- **CSP for iframe embedding** — JupyterHub's default CSP blocks framing. Solution: configure `frame-ancestors` in Helm values to allow the portal's origin (localhost:4200, production domain, etc.).
- **Hardcoded Keycloak authorize URL** — JupyterHub's `authorize_url` must be browser-accessible (not in-cluster DNS). Currently hardcoded to the r1 cluster IP `172.16.100.10:30080`. Token/userdata URLs correctly use in-cluster DNS since they're server-to-server.
- **Thread.sleep in JupyterHubService** — `fetchContents()` retries with `Thread.sleep(200)` on transient failures. This blocks the executor thread; a reactive retry would be better.

## Limitations

- **No hot-resize of resources** — Changing the compute profile requires a workspace restart. JupyterHub's KubeSpawner doesn't support live resource changes.
- **10Gi storage per user** — Dynamic PVC provisioning allocates fixed 10Gi. No per-analysis storage quotas.
- **Hardcoded Keycloak URL in Helm values** — The authorize URL uses a specific IP, breaking portability across deployment contexts.
- **No disk usage warnings** — If the PVC fills up, the user gets no proactive warning from the platform.
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
- **Storage quotas and alerts** — Monitor PVC usage and warn users approaching their 10Gi limit.
- **Warm pool of pre-spawned servers** — Reduce cold-start time by maintaining a pool of idle notebook pods ready for assignment.
- **Graceful session handoff** — Instead of 403 detection + redirect, use JupyterHub's session management to handle user switches cleanly.
