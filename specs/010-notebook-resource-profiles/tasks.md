# Tasks: Notebook Resource Profiles

**Input**: Design documents from `/specs/010-notebook-resource-profiles/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/api.yaml

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: Backend configuration infrastructure and JupyterHub spawn refactoring that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T001 [P] Create WorkspaceProfileProperties configuration class with ProfileConfig record (id, name, description, isDefault, cpuRequest, cpuLimit, memoryRequest, memoryLimit), getById() lookup, and getDefault() accessor in backend/src/main/java/com/mlplatform/config/WorkspaceProfileProperties.java. Register via @EnableConfigurationProperties. Validate at startup: at least one profile defined, exactly one default, no duplicate IDs.

- [x] T002 [P] Add workspace.profiles configuration with three profiles (exploratory: 1/2 CPU, 2G/4G RAM, default=true; standard: 2/4 CPU, 4G/8G RAM; compute-intensive: 4/8 CPU, 8G/16G RAM) in backend/src/main/resources/application.yaml under the existing `workspace:` key.

- [x] T003 [P] Add `default` boolean field to ComputeProfileDto record in backend/src/main/java/com/mlplatform/dto/ComputeProfileDto.java. Update constructor to accept the new field.

- [x] T004 [P] Refactor JupyterHubService.spawnNamedServer() to accept `Map<String, Object> spawnOptions` instead of `String imageReference`. Build JSON body from all non-null map entries. Maintain backward compatibility: if spawnOptions is null or empty, use executePost() as before. Update the no-args overload to pass empty map. In backend/src/main/java/com/mlplatform/service/JupyterHubService.java.

- [x] T005 [P] Extend pre_spawn_hook in infrastructure/helm/jupyterhub/values.yaml to read cpu_guarantee, cpu_limit, mem_guarantee, mem_limit from user_options and apply to spawner (cpu_guarantee as float, cpu_limit as float, mem_guarantee and mem_limit as strings). Add after the existing custom image override block.

**Checkpoint**: Profile configuration loads at startup, JupyterHub accepts resource overrides via spawn options

---

## Phase 2: User Story 1 — Select Resource Profile at Launch (Priority: P1) 🎯 MVP

**Goal**: Users see a profile selector when launching a workspace, select from predefined profiles, and the notebook server starts with the corresponding resource allocation.

**Independent Test**: Open workspace launch flow → verify profile selector shows 3 profiles with names and resource info → select "Standard" → launch → verify pod has 2/4 CPU and 4G/8G memory limits.

### Implementation for User Story 1

- [x] T006 [US1] Update WorkspaceService.getProfiles() to read profiles from WorkspaceProfileProperties (injected) and return as List<ComputeProfileDto> with the new default field. Remove the hardcoded single-profile list. Update WorkspaceService.launchWorkspace() to: (a) normalize profile ID to lowercase, (b) look up profile in WorkspaceProfileProperties — throw 400 with available profile IDs if not found, (c) build spawn options map with image (if provided), cpu_guarantee, cpu_limit, mem_guarantee, mem_limit from the profile config, (d) pass spawn options map to jupyterHubService.spawnNamedServer(). In backend/src/main/java/com/mlplatform/service/WorkspaceService.java.

- [x] T007 [P] [US1] Add `default` field to ComputeProfile interface and update profile selector in launcher card: replace static `<h3>{{ profile?.name }}</h3>` heading with a `<select>` dropdown showing all profiles with format "Name — X CPU, YG RAM", add a profile description paragraph below the selector, pre-select the default profile on load. In frontend/src/app/features/notebooks/notebooks.component.html and frontend/src/app/features/notebooks/notebooks.component.scss (add .profile-selector styling matching existing .image-selector pattern).

- [x] T008 [US1] Add profile selection state to component: selectedProfileId (string, initialized to default profile ID on profile load), selectedProfile getter (computed from profiles array), profiles array (loaded from getProfiles()). Update launchWorkspace() to pass selectedProfileId instead of hardcoded profile.id. Ensure selectedProfileId syncs from activeProfile on status load (same as selectedImageId syncs from activeImageId). In frontend/src/app/features/notebooks/notebooks.component.ts.

**Checkpoint**: User Story 1 fully functional — users can select a profile and launch with correct resources

---

## Phase 3: User Story 2 — Switch Profile on Active Workspace (Priority: P2)

**Goal**: Users can change the resource profile of a running workspace. The system warns about restart, terminates, and relaunches with the new profile. Files are preserved.

**Independent Test**: Launch with "Exploratory" → create and save a notebook → switch to "Compute-Intensive" → confirm restart → verify restarted pod has 4/8 CPU and 8G/16G memory → verify saved notebook still exists.

**Depends on**: User Story 1 (profiles must be selectable at launch)

### Implementation for User Story 2

- [x] T009 [P] [US2] Add profile selector to running-state toolbar: insert a .toolbar-profile-selector in the toolbar-right section (before the image selector) showing the active profile name and a dropdown to switch. Disable dropdown while switchingProfile is true. Style to match existing .toolbar-image-selector pattern. In frontend/src/app/features/notebooks/notebooks.component.html and frontend/src/app/features/notebooks/notebooks.component.scss.

- [x] T010 [US2] Implement onProfileChange(newProfileId: string) in component following the onImageChange() pattern: if workspace is STOPPED/FAILED, just update selectedProfileId; if RUNNING/IDLE and newProfileId equals activeProfileId, do nothing; otherwise show confirmation dialog warning about restart and kernel state loss, on confirm set switchingProfile=true then terminateWorkspace() → clear iframe/bridge → set status=PENDING → launchWorkspace() with new profile (preserving current image selection) → startPolling(); on cancel revert selectedProfileId to activeProfileId and force change detection. Add switchingProfile boolean and activeProfileId string state properties. Update applyStatus() to track activeProfileId from workspace status. In frontend/src/app/features/notebooks/notebooks.component.ts.

**Checkpoint**: User Stories 1 AND 2 both work — users can launch with any profile and switch between profiles on running workspaces

---

## Phase 4: User Story 3 — View Current Resource Profile and Usage (Priority: P3)

**Goal**: The portal displays the active profile name and live CPU/memory utilization for running workspaces. Gracefully degrades when metrics are unavailable.

**Independent Test**: Launch workspace → verify profile name shown in toolbar → run a memory-intensive cell → verify utilization metrics update → stop metrics-server → verify "Usage unavailable" fallback.

**Depends on**: User Story 1 (profile must be tracked per workspace)

### Implementation for User Story 3

- [x] T011 [P] [US3] Create WorkspaceMetricsDto record with fields: profileId (String), profileName (String), cpuUsage (String, nullable), cpuLimit (String), memoryUsageBytes (Long, nullable), memoryLimit (String), metricsAvailable (boolean). In backend/src/main/java/com/mlplatform/dto/WorkspaceMetricsDto.java.

- [x] T012 [US3] Implement getWorkspaceMetrics(Jwt jwt, UUID analysisId) in WorkspaceService: get active workspace and podName, look up profile config by workspace.getProfile(), query Kubernetes Metrics API (GET /apis/metrics.k8s.io/v1beta1/namespaces/{namespace}/pods/{podName}) using the existing Kubernetes ApiClient, parse CPU usage from nanocores to cores (divide by 1e9, format to 1 decimal) and memory usage in bytes, return WorkspaceMetricsDto. If metrics-server unavailable or pod not found, return metricsAvailable=false with null usage values. Handle dev profile with mock data. In backend/src/main/java/com/mlplatform/service/WorkspaceService.java.

- [x] T013 [US3] Add GET /metrics endpoint to WorkspaceController: @GetMapping("metrics") returning WorkspaceMetricsDto, delegates to workspaceService.getWorkspaceMetrics(). In backend/src/main/java/com/mlplatform/controller/WorkspaceController.java.

- [x] T014 [P] [US3] Add getMetrics(analysisId: string): Observable<WorkspaceMetrics> method and WorkspaceMetrics interface (profileId, profileName, cpuUsage, cpuLimit, memoryUsageBytes, memoryLimit, metricsAvailable) to frontend workspace service. In frontend/src/app/core/services/workspace.service.ts.

- [x] T015 [US3] Display active profile name and resource utilization in the running toolbar: show profile badge with name, add utilization summary (e.g., "1.2 / 2 CPU, 2.1 / 4 GB RAM") next to kernel status. Poll metrics every 15 seconds while workspace is running. Show "Usage unavailable" when metricsAvailable is false. Stop polling on workspace stop/destroy. In frontend/src/app/features/notebooks/notebooks.component.ts, .html, and .scss.

**Checkpoint**: All three user stories independently functional

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Backward compatibility validation and documentation

- [x] T016 Update CLAUDE.md feature table: add Feature 010 row (Notebook Resource Profiles, key backend: WorkspaceProfileProperties/WorkspaceService, key frontend: notebooks.component/workspace.service). In CLAUDE.md.

- [x] T017 Verify backward compatibility: launch workspace without explicitly selecting a profile — confirm default "Exploratory" is applied with the same 1/2 CPU, 2G/4G memory as before this feature. Verify existing workspaces with profile=EXPLORATORY continue to work.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 1)**: No dependencies — can start immediately. BLOCKS all user stories.
- **User Story 1 (Phase 2)**: Depends on Foundational completion.
- **User Story 2 (Phase 3)**: Depends on User Story 1 (profile selector must exist).
- **User Story 3 (Phase 4)**: Depends on Foundational completion (can run in parallel with US1/US2 for backend tasks, but frontend display depends on US1 for profile name).
- **Polish (Phase 5)**: Depends on all user stories being complete.

### User Story Dependencies

- **User Story 1 (P1)**: Depends on Foundational only. MVP — delivers core value.
- **User Story 2 (P2)**: Depends on US1 (switching builds on the profile selector).
- **User Story 3 (P3)**: Backend tasks (T011-T013) can start after Foundational. Frontend task (T015) should follow US1 for the toolbar integration.

### Within Each User Story

- Backend changes before frontend integration
- Configuration/DTOs before service logic
- Service logic before controller/UI

### Parallel Opportunities

**Foundational phase**: All 5 tasks (T001-T005) are independent and can run in parallel.

**User Story 1**: T007 (frontend UI) can run in parallel with T006 (backend logic).

**User Story 2**: T009 (HTML/SCSS) can run in parallel with backend work if any.

**User Story 3**: T011 (DTO) and T014 (frontend service) can run in parallel with T012 (backend service logic).

---

## Parallel Example: Foundational Phase

```
# All foundational tasks touch different files — launch together:
T001: WorkspaceProfileProperties.java (NEW)
T002: application.yaml (MODIFY)
T003: ComputeProfileDto.java (MODIFY)
T004: JupyterHubService.java (MODIFY)
T005: values.yaml (MODIFY)
```

## Parallel Example: User Story 1

```
# Backend and frontend can progress in parallel:
T006: WorkspaceService.java (backend profile logic)
T007: notebooks.component.html + .scss (frontend selector UI)

# Then integrate:
T008: notebooks.component.ts (wire frontend to backend)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Foundational (T001-T005)
2. Complete Phase 2: User Story 1 (T006-T008)
3. **STOP and VALIDATE**: Launch workspace with "Standard" profile, verify pod resource limits
4. Deploy and verify backward compatibility

### Incremental Delivery

1. Foundational → Foundation ready
2. Add User Story 1 → Profile selection at launch (MVP!)
3. Add User Story 2 → Profile switching on running workspaces
4. Add User Story 3 → Resource utilization display
5. Each story adds value without breaking previous stories

---

## Notes

- No database migration needed — `workspaces.profile` column already exists
- No new Angular components or modules — all changes are to existing notebooks component
- JupyterHub Helm values change (T005) must be deployed before testing backend resource passing
- Kubernetes Java Client already a project dependency (Feature 008)
- Profile switching reuses the identical terminate → relaunch pattern from image switching (Feature 008)
- [P] tasks = different files, no dependencies
- Commit after each task or logical group
