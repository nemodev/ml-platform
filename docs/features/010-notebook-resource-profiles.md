# Feature 010: Notebook Resource Profiles

> Lets users select CPU/memory resource profiles for workspaces, applies them via JupyterHub's KubeSpawner, and displays live usage metrics from the Kubernetes Metrics API.

## What & Why

Not all ML work needs the same resources. Data exploration on a small CSV needs far less CPU and memory than training a gradient-boosted model on a large dataset. Without profiles, every workspace gets the same fixed allocation — either wasteful for light work or insufficient for heavy computation. Feature 010 introduces configuration-based resource profiles that users select at launch time. Profiles are defined in `application.yaml` (not in the database), keeping them version-controlled and simple. Live resource metrics let users see whether they've chosen the right profile. This is the final feature in the workspace lifecycle chain, extending the spawn flow from Features 002, 008, and 007.

## Architecture

```
Portal (profile selector in notebooks toolbar)
    ↓ GET /api/v1/analyses/{id}/workspaces/profiles
    ↓ POST /api/v1/analyses/{id}/workspaces {profile: "standard"}
Backend (WorkspaceService)
    ├── Resolve profile from WorkspaceProfileProperties
    ├── Build spawn options: cpu_guarantee, cpu_limit, mem_guarantee, mem_limit
    └── Pass spawn options to JupyterHubService.spawnNamedServer()
        ↓
JupyterHub (pre_spawn_hook in values.yaml)
    ├── Read user_options: cpu_guarantee, cpu_limit, mem_guarantee, mem_limit
    └── Override spawner.cpu_guarantee, cpu_limit, mem_guarantee, mem_limit
        ↓
KubeSpawner → Notebook Pod (with resource requests/limits applied)

Portal (metrics display)
    ↓ GET /api/v1/analyses/{id}/workspaces/metrics (every 15s)
Backend (WorkspaceService)
    ↓ Kubernetes Metrics API (metrics.k8s.io/v1beta1)
    ↓ Parse CPU nanocores + memory bytes
Portal (usage bars: CPU 0.5/2.0, Memory 1.2G/4G)
```

**Key decisions:**

- **Configuration-based profiles, not database** — Profiles are defined in `application.yaml` under `workspace.profiles`. Immutable at runtime, version-controlled, no schema migrations. The trade-off is that adding profiles requires a backend restart — acceptable for a PoC where profile definitions change rarely.
- **Spawn options through existing user_options** — Reuses the same `user_options` mechanism that Feature 008 uses for custom images. The `pre_spawn_hook` reads `cpu_guarantee`, `cpu_limit`, `mem_guarantee`, and `mem_limit` from user_options and applies them to the spawner. No new JupyterHub extension needed.
- **Terminate-then-relaunch for profile switching** — Kubernetes doesn't support in-place resource changes for pods. Switching profiles requires terminating the workspace and relaunching with new resource limits. The frontend handles this as a single user action with a confirmation dialog.
- **Kubernetes Metrics API, not Prometheus** — The `metrics.k8s.io/v1beta1` API provides instant CPU/memory usage from `metrics-server`, which is already deployed on any standard cluster. No new infrastructure dependency. Sufficient for point-in-time display, though not for historical trends.
- **No database migration** — The `workspace.profile` column already existed from earlier schema design. No new migration needed, which is unusual for a feature addition.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `config/WorkspaceProfileProperties.java` | `@ConfigurationProperties` with startup validation |
| Backend | `service/WorkspaceService.java` | Profile resolution, spawn options building, metrics querying |
| Backend | `controller/WorkspaceController.java` | `/profiles` and `/metrics` endpoints |
| Backend | `application.yaml` | Three profile definitions with resource limits |
| Frontend | `features/notebooks/notebooks.component.ts` | Profile selector, switch flow, metrics polling |
| Frontend | `core/services/workspace.service.ts` | HTTP client for profiles, launch, metrics |
| Infra | `helm/jupyterhub/values.yaml` | `pre_spawn_hook` resource override logic |

**Three predefined profiles:** Exploratory (1-2 CPU, 2-4G RAM, default), Standard (2-4 CPU, 4-8G RAM), and Compute-Intensive (4-8 CPU, 8-16G RAM). Each defines request (guaranteed) and limit (burstable) values separately, following Kubernetes resource model conventions.

**Startup validation:** `WorkspaceProfileProperties` validates at `@PostConstruct`: at least one profile exists, no duplicate IDs (case-insensitive), each has a non-blank ID, and exactly one is marked as default. If validation fails, the application won't start — fail-fast on misconfiguration.

**Profile resolution flow:** `WorkspaceService.launchWorkspace()` normalizes the profile ID to lowercase, looks it up in `WorkspaceProfileProperties`, and throws 400 BAD_REQUEST with the list of available profiles if not found. The resolved profile's resource values are packed into a spawn options map with snake_case keys (`cpu_guarantee`, `cpu_limit`, `mem_guarantee`, `mem_limit`) matching JupyterHub conventions, then passed to `JupyterHubService.spawnNamedServer()`.

**JupyterHub pre_spawn_hook:** The hook in `values.yaml` reads resource values from `spawner.user_options` and applies them with type conversion — CPU values are cast to `float` (KubeSpawner expects floats), memory stays as strings (KubeSpawner accepts Kubernetes format like "4G"). If no user_options are provided (e.g., older workspace records), JupyterHub's `singleuser` defaults apply (1 CPU, 2G RAM — matching the Exploratory profile).

**Profile switching in the frontend:** When a user selects a different profile while a workspace is RUNNING or IDLE, `onProfileChange()` shows a confirmation dialog warning that kernels will be interrupted. On confirmation, it sets `switchingProfile = true`, terminates the workspace (DELETE), waits for termination, relaunches with the new profile (POST), polls until RUNNING, then clears the flag. If the workspace is STOPPED or FAILED, the selection just updates `selectedProfileId` for the next launch.

**Metrics integration:** The frontend polls `/workspaces/metrics` every 15 seconds when a workspace is running. The backend queries `metrics.k8s.io/v1beta1` via `CustomObjectsApi.getNamespacedCustomObject()`, parsing the pod's container metrics. CPU values come in nanocores (`500000000n` → `0.5`) or millicores (`500m` → `0.5`). Memory comes in Ki/Mi/Gi. The response includes both usage and limits so the frontend can render usage bars. If metrics-server is unavailable, the endpoint returns `metricsAvailable: false` with null usage values — graceful degradation.

## Challenges & Solutions

- **CPU type conversion at JupyterHub boundary** — KubeSpawner expects `cpu_guarantee` as a Python float, but user_options arrive as strings from the JupyterHub API. The `pre_spawn_hook` explicitly casts with `float()`. Memory stays as strings because KubeSpawner passes them directly to Kubernetes.
- **Concurrent profile switch race** — The frontend's `switchingProfile` flag disables the profile selector during a switch, and the backend's 409 CONFLICT check for active workspaces prevents duplicate launches. Together, these prevent race conditions from rapid profile changes.
- **Metrics API format parsing** — Kubernetes returns CPU as nanocores and memory as binary suffixes (Ki, Mi, Gi). `WorkspaceService` includes format-specific parsers for both. Unknown formats fall through to raw string representation.
- **Profile removal by admin** — If an admin removes a profile that running workspaces are using, the workspace continues running (Kubernetes doesn't reclaim resources). On next relaunch, the user must select from current profiles. No migration mechanism exists.

## Limitations

- **No hot resizing** — Profile switches require a full workspace restart. In-place pod resource changes aren't supported by Kubernetes (without VPA). Users lose kernel state.
- **GPU profiles out of scope** — The `gpuLimit` field exists in the DTO but is hardcoded to 0. GPU node selectors and resource requests would need additional Kubernetes configuration.
- **No per-user profile restrictions** — All profiles are visible to all users. There's no mechanism to restrict Compute-Intensive profiles to specific users or teams.
- **Metrics-server dependency** — If `metrics-server` isn't deployed or RBAC doesn't permit the backend to query it, metrics silently degrade to unavailable. No explicit health check or warning.
- **Fixed 15-second metrics polling** — No adaptive back-off. Under load, many concurrent workspaces polling metrics could add pressure to the metrics API.
- **Profile definitions require restart** — Since profiles are in `application.yaml`, adding or modifying profiles requires a backend restart. No runtime configuration API.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| Database-stored profiles with admin UI | Adds complexity (migrations, admin endpoints, CRUD UI). YAML config is simpler for a PoC with infrequent changes. |
| Prometheus for metrics | Requires deploying Prometheus stack. Kubernetes Metrics API is already available and sufficient for point-in-time display. |
| In-place pod resizing (VPA) | Kubernetes VPA is alpha/beta, and KubeSpawner doesn't support it. Terminate-relaunch is reliable and well-understood. |
| Slider-based custom resources | Too much freedom leads to over-provisioning. Named profiles with curated limits guide users toward appropriate choices. |
| Auto-scaling profiles | Adds significant complexity (monitoring, decision logic, pod disruption). Fixed profiles with metrics display lets users self-serve. |

## Potential Improvements

- **GPU profiles** — Add GPU resource requests with node affinity rules for clusters with GPU nodes.
- **Profile usage quotas** — Limit concurrent Compute-Intensive workspaces per team or user to prevent resource exhaustion.
- **Historical metrics** — Store metrics snapshots for trend display, or integrate with Prometheus for time-series data.
- **Dynamic profile management** — Admin API for creating/modifying profiles without backend restart.
- **Resource recommendations** — Analyze metrics history to suggest optimal profiles for specific workloads.
