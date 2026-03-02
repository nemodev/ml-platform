# Implementation Plan: Notebook Resource Profiles

**Branch**: `010-notebook-resource-profiles` | **Date**: 2026-03-01 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/010-notebook-resource-profiles/spec.md`

## Summary

Allow users to select from predefined resource profiles (CPU/memory tiers) when launching a notebook workspace, and switch profiles on running workspaces. Profiles are defined in `application.yaml` and passed to JupyterHub via spawn options. The frontend adds a profile selector to the launcher card and toolbar, following the same terminate-relaunch pattern used for image switching. A resource utilization endpoint (P3) queries Kubernetes metrics API for live pod usage.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend), Python 3.11 (JupyterHub hook)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17, Kubernetes Java Client (`io.kubernetes:client-java`)
**Storage**: PostgreSQL (existing `workspaces.profile` column — no migration needed), application.yaml (profile definitions)
**Testing**: Integration tests (backend REST + JupyterHub mock), Angular component tests
**Target Platform**: Kubernetes (Rancher Desktop local, r1 remote cluster)
**Project Type**: Web application (backend + frontend + infrastructure)
**Performance Goals**: Profile switch completes within 120s (SC-002), metrics response < 5s (SC-004)
**Constraints**: No hot-resize — profile switch requires server restart. GPU profiles out of scope.
**Scale/Scope**: 3 predefined profiles, ~10 files modified, 1 new endpoint, 1 new config class

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. MVP-First Incremental Delivery | PASS | Feature is independently deployable and verifiable. Builds on completed Features 002 and 008. |
| II. Infrastructure as Code | PASS | JupyterHub hook changes in version-controlled Helm values. Profile config in application.yaml. |
| III. Unified Authentication | PASS | No auth changes. Uses existing JWT validation. |
| IV. Environment Parity | PASS | Profiles defined in config, injected per-environment. Same mechanism for local and r1. |
| V. Testing at System Boundaries | PASS | Integration test planned for profile → JupyterHub spawn option propagation. |
| VI. Production-Quality Within Scope | PASS | Config properties class with validation, proper error handling, follows existing patterns. No over-engineering (config file, not admin UI). |

**Post-Phase 1 Re-check**: No violations. Design uses existing patterns (user_options for spawn, terminate+relaunch for switching), adds one config class and one DTO. No new abstractions beyond what's warranted.

## Project Structure

### Documentation (this feature)

```text
specs/010-notebook-resource-profiles/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: Technical decisions
├── data-model.md        # Phase 1: Entity model
├── quickstart.md        # Phase 1: Getting started guide
├── contracts/
│   └── api.yaml         # Phase 1: OpenAPI contract
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/mlplatform/
│   ├── config/
│   │   └── WorkspaceProfileProperties.java    # NEW: @ConfigurationProperties for profiles
│   ├── controller/
│   │   └── WorkspaceController.java           # MODIFIED: add metrics endpoint (P3)
│   ├── dto/
│   │   ├── ComputeProfileDto.java             # MODIFIED: add default field
│   │   └── WorkspaceMetricsDto.java           # NEW: resource utilization DTO (P3)
│   └── service/
│       ├── WorkspaceService.java              # MODIFIED: config-based profiles, resource passing
│       └── JupyterHubService.java             # MODIFIED: accept spawn options map
├── src/main/resources/
│   └── application.yaml                       # MODIFIED: add workspace.profiles config
└── src/test/java/com/mlplatform/
    └── service/
        └── WorkspaceServiceTest.java          # NEW/MODIFIED: profile tests

frontend/
├── src/app/
│   ├── features/notebooks/
│   │   ├── notebooks.component.ts             # MODIFIED: profile selection + switching
│   │   ├── notebooks.component.html           # MODIFIED: profile selector UI
│   │   └── notebooks.component.scss           # MODIFIED: profile selector styling
│   └── core/services/
│       └── workspace.service.ts               # MODIFIED: add getMetrics() (P3)

infrastructure/
└── helm/jupyterhub/
    └── values.yaml                            # MODIFIED: pre_spawn_hook resource overrides
```

**Structure Decision**: Web application (backend + frontend + infrastructure). All changes are modifications to existing files following established patterns. One new config class (`WorkspaceProfileProperties`) and one new DTO (`WorkspaceMetricsDto`). No new components, modules, or services.

## Design Details

### Backend: Profile Configuration

**New file**: `WorkspaceProfileProperties.java`

```java
@ConfigurationProperties(prefix = "workspace")
public class WorkspaceProfileProperties {
    private List<ProfileConfig> profiles = List.of();

    public record ProfileConfig(
        String id,
        String name,
        String description,
        boolean isDefault,
        String cpuRequest,
        String cpuLimit,
        String memoryRequest,
        String memoryLimit
    ) {}

    // Accessor: getDefault(), getById(id), toDto()
}
```

Registered via `@EnableConfigurationProperties` on the main application class or a `@Configuration` class.

### Backend: Spawn Options Refactor

**JupyterHubService.spawnNamedServer()** — Refactor from `(username, serverName, imageReference)` to accept a spawn options map:

```java
public void spawnNamedServer(String username, String serverName, Map<String, Object> spawnOptions) {
    // Build JSON body from non-null entries in spawnOptions
    // Keys: "image", "cpu_guarantee", "cpu_limit", "mem_guarantee", "mem_limit"
}
```

**WorkspaceService.launchWorkspace()** — Look up profile config, build spawn options:

```java
ProfileConfig profileConfig = profileProperties.getById(normalizedProfile);
Map<String, Object> spawnOptions = new LinkedHashMap<>();
if (imageReference != null) spawnOptions.put("image", imageReference);
spawnOptions.put("cpu_guarantee", profileConfig.cpuRequest());
spawnOptions.put("cpu_limit", profileConfig.cpuLimit());
spawnOptions.put("mem_guarantee", profileConfig.memoryRequest());
spawnOptions.put("mem_limit", profileConfig.memoryLimit());
jupyterHubService.spawnNamedServer(username, serverName, spawnOptions);
```

### Backend: Profile Validation

When `launchWorkspace()` receives a profile ID:
1. Normalize to lowercase
2. Look up in `WorkspaceProfileProperties`
3. If not found → `400 Bad Request: Unknown profile '{id}'. Available: [exploratory, standard, compute-intensive]`
4. If null/blank → use the default profile

### Backend: Metrics Endpoint (P3)

**New endpoint**: `GET /api/v1/analyses/{analysisId}/workspaces/metrics`

1. Get active workspace for analysis → get `podName`
2. Query Kubernetes Metrics API: `GET /apis/metrics.k8s.io/v1beta1/namespaces/{ns}/pods/{podName}`
3. Parse CPU usage (nanocores → cores) and memory usage (bytes)
4. Look up profile config for allocated limits
5. Return `WorkspaceMetricsDto`
6. If metrics-server unavailable → return `metricsAvailable: false` with null usage values

### Frontend: Profile Selector

**Launcher card (STOPPED state)**: Add a profile selector dropdown above the image selector:

```html
<div class="profile-selector">
  <label>
    <span>Resource Profile</span>
    <select [ngModel]="selectedProfileId" (ngModelChange)="onProfileChange($event)">
      <option *ngFor="let p of profiles" [value]="p.id">
        {{ p.name }} — {{ p.cpuLimit }} CPU, {{ p.memoryLimit }} RAM
      </option>
    </select>
  </label>
  <p class="profile-description">{{ selectedProfile?.description }}</p>
</div>
```

**Running state toolbar**: Display active profile name. Add a profile selector that triggers the switch flow (same confirm-terminate-relaunch pattern as image switching).

### Frontend: Profile Switching

Follows the identical pattern to `onImageChange()`:

```typescript
onProfileChange(newProfileId: string): void {
  if (this.status === 'STOPPED' || this.status === 'FAILED') {
    this.selectedProfileId = newProfileId;
    return;
  }
  // Running state: confirm, terminate, relaunch
  if (newProfileId === this.activeProfileId) return;
  const confirmed = confirm('Switching resource profile requires restarting...');
  if (!confirmed) { /* revert selection */ return; }
  this.switchingProfile = true;
  // terminate → relaunch with new profile
}
```

### Infrastructure: Pre-spawn Hook

Extend the existing hook in `values.yaml` to read resource fields from `user_options`:

```python
# Feature 010: Resource profile override
cpu_guarantee = user_options.get('cpu_guarantee')
cpu_limit = user_options.get('cpu_limit')
mem_guarantee = user_options.get('mem_guarantee')
mem_limit = user_options.get('mem_limit')
if cpu_guarantee is not None:
    spawner.cpu_guarantee = float(cpu_guarantee)
if cpu_limit is not None:
    spawner.cpu_limit = float(cpu_limit)
if mem_guarantee is not None:
    spawner.mem_guarantee = mem_guarantee
if mem_limit is not None:
    spawner.mem_limit = mem_limit
```

## Complexity Tracking

> No constitution violations. No complexity justifications needed.
