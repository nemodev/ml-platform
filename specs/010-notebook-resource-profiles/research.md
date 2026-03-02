# Research: Notebook Resource Profiles

## R1: How to pass resource limits from backend to JupyterHub spawner

**Decision**: Extend the JupyterHub spawn request body (user_options) to include resource fields alongside the existing `image` field.

**Rationale**: The existing pattern for custom images (Feature 008) already passes data via user_options in the POST body to `/hub/api/users/{username}/servers/{serverName}`. The pre_spawn_hook reads these options and applies them to the spawner. Extending this to include `cpu_guarantee`, `cpu_limit`, `mem_guarantee`, `mem_limit` follows the established pattern with zero new infrastructure.

**Alternatives considered**:
- *JupyterHub profileList with multiple entries*: Would work but requires Helm chart changes for every profile change. The backend-as-orchestrator pattern is already established and provides a single source of truth.
- *KubeSpawner profile_list API*: JupyterHub's built-in profile selection UI is bypassed since the portal manages spawn via REST API. Relying on it would create a split configuration between backend and Helm values.

## R2: Profile configuration format and location

**Decision**: Define profiles under `workspace.profiles` in `application.yaml`, loaded via a `@ConfigurationProperties` class.

**Rationale**: Spring Boot's `@ConfigurationProperties` provides type-safe binding, validation at startup, and immutability. Profiles are static platform-level configuration — they don't need database storage. YAML format is consistent with all other backend configuration in this project.

**Configuration structure**:
```yaml
workspace:
  profiles:
    - id: exploratory
      name: Exploratory
      description: Interactive data exploration and small experiments
      default: true
      cpu-request: "1"
      cpu-limit: "2"
      memory-request: "2G"
      memory-limit: "4G"
    - id: standard
      name: Standard
      description: Typical ML workflows and medium-sized datasets
      cpu-request: "2"
      cpu-limit: "4"
      memory-request: "4G"
      memory-limit: "8G"
    - id: compute-intensive
      name: Compute-Intensive
      description: Large dataset processing and model training
      cpu-request: "4"
      cpu-limit: "8"
      memory-request: "8G"
      memory-limit: "16G"
```

**Alternatives considered**:
- *Database table for profiles*: Over-engineered — profiles change rarely (admin operations), don't need CRUD UI, and are better as declarative config that's version-controlled alongside Helm values.
- *Helm values only (no backend config)*: Would require the backend to query JupyterHub's config to discover profiles, creating a circular dependency. Backend should be the source of truth.

## R3: Profile switching mechanism

**Decision**: Use the existing terminate-then-relaunch pattern, matching how image switching already works in the frontend.

**Rationale**: The frontend already implements a confirmed terminate → relaunch flow for image switching (`onImageChange()` in `notebooks.component.ts`). The same flow applies to profile switching: confirm → terminate → relaunch with new profile. No new backend endpoint is needed — the existing `DELETE /workspaces` + `POST /workspaces` flow handles this. Combining profile and image selection in the launch request is already supported by `LaunchWorkspaceRequest(profile, notebookImageId)`.

**Alternatives considered**:
- *Dedicated `PUT /workspaces/profile` endpoint*: More atomic but adds API surface for no functional benefit. The two-step flow is already proven safe (image switching), and the frontend handles the state machine correctly.

## R4: Resource utilization metrics (P3)

**Decision**: Query Kubernetes Metrics API (`metrics.k8s.io/v1beta1`) for pod-level CPU and memory usage via the Kubernetes Java Client's `CustomObjectsApi`.

**Rationale**: The Kubernetes Java Client is already a project dependency (Feature 008 uses it for Kaniko build jobs). The Metrics API is the standard way to get pod resource usage and is available on any cluster with metrics-server installed (both local Rancher Desktop and r1 cluster). Polling is frontend-driven (on-demand, not streaming), keeping the backend stateless.

**Alternatives considered**:
- *Prometheus/Grafana stack*: Much heavier dependency for a simple usage display. Out of proportion for the feature.
- *kubectl top proxy*: Non-standard, requires shell access from backend.
- *JupyterHub API for resource usage*: JupyterHub doesn't expose pod resource metrics.

## R5: JupyterHub profileList synchronization

**Decision**: Keep a single "Exploratory" entry in the JupyterHub Helm `profileList` as the fallback default. The pre_spawn_hook overrides resources from user_options when the backend provides them.

**Rationale**: If the backend passes resource values via user_options, the hook applies them and they override the profileList defaults. If no resource values are passed (e.g., direct JupyterHub access for debugging), the profileList fallback ensures a safe default. This is the same pattern used for images: the hook overrides `spawner.image` when user_options contains an image reference.

## R6: Default profile resource values

**Decision**: Three profiles with the following allocations:

| Profile | CPU Request | CPU Limit | Memory Request | Memory Limit |
|---------|------------|-----------|---------------|-------------|
| Exploratory (default) | 1 | 2 | 2G | 4G |
| Standard | 2 | 4 | 4G | 8G |
| Compute-Intensive | 4 | 8 | 8G | 16G |

**Rationale**: Exploratory matches the current single-profile allocation (backward compatible). Standard doubles resources for typical ML workloads. Compute-Intensive provides 4x the default for training and large dataset processing. These are reasonable starting points that can be tuned per-cluster via the configuration file.
