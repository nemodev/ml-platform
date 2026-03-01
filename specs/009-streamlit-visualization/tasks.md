# Tasks: Streamlit Visualization

**Input**: Design documents from `/specs/009-streamlit-visualization/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Not explicitly requested in feature specification. Tests are omitted.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup (Notebook Image Dependencies)

**Purpose**: Add Streamlit, jupyter-server-proxy, and platform package protection to the notebook image build

- [X] T001 Add `streamlit>=1.33.0` and `jupyter-server-proxy>=4.0.0` to `infrastructure/docker/notebook-image/requirements.txt`
- [X] T002 [P] Create `infrastructure/docker/notebook-image/platform-constraints.txt` pinning all platform packages from requirements.txt (jupyterlab, jupyterhub, notebook, mlflow, pyspark, streamlit, jupyter-server-proxy, jupyter-iframe-commands, etc.) to prevent custom image builds from overriding them
- [X] T003 Update `infrastructure/docker/notebook-image/Dockerfile` to: (1) copy `platform-constraints.txt` to `/opt/ml-platform/platform-constraints.txt`, (2) install `ml_platform_streamlit` extension package, (3) ensure jupyter-server-proxy is enabled

---

## Phase 2: Foundational (Jupyter Extension + Backend API + Frontend Service)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented — the Jupyter server extension running in the notebook pod, the backend proxy layer, and the frontend HTTP service

**Warning**: No user story work can begin until this phase is complete

- [X] T004 [P] Create Jupyter server extension entry point at `infrastructure/docker/notebook-image/ml_platform_streamlit/__init__.py` that registers the extension with Jupyter Server and mounts handler routes under `/api/streamlit/`
- [X] T005 [P] Create Jupyter server extension handlers at `infrastructure/docker/notebook-image/ml_platform_streamlit/handlers.py` implementing: `GET /api/streamlit/files` (scan `visualize/` for `.py` files containing `import streamlit` or `from streamlit`), `POST /api/streamlit/start` (start Streamlit subprocess on specified file with headless/CORS/XSRF config per research.md), `POST /api/streamlit/stop` (kill running Streamlit process), `GET /api/streamlit/status` (return process state, port, active file). Use port 8501 with increment if occupied. Track at most one process at a time per data-model.md lifecycle.
- [X] T006 [P] Create `backend/src/main/java/com/mlplatform/dto/StreamlitFileDto.java` as a Java record with fields: `String name`, `String path`, `String lastModified` (per api.yaml StreamlitFile schema)
- [X] T007 [P] Create `backend/src/main/java/com/mlplatform/dto/StreamlitFileListDto.java` as a Java record with field: `List<StreamlitFileDto> files` (per api.yaml StreamlitFileList schema)
- [X] T008 [P] Create `backend/src/main/java/com/mlplatform/dto/StreamlitStatusDto.java` as a Java record with fields: `String status`, `String filePath`, `String url`, `String errorMessage` (per api.yaml StreamlitStatus schema)
- [X] T009 [P] Create `backend/src/main/java/com/mlplatform/dto/StartStreamlitRequestDto.java` as a Java record with field: `String filePath` (per api.yaml StartStreamlitRequest schema)
- [X] T010 Create `backend/src/main/java/com/mlplatform/service/StreamlitService.java` implementing proxy calls to the notebook pod's Jupyter server extension via JupyterHub. Follow the existing pattern in `JupyterHubService.java` for constructing the proxy URL (`/user/{username}/{serverName}/api/streamlit/...`). Methods: `listFiles(analysisId)`, `startApp(analysisId, filePath)`, `stopApp(analysisId)`, `getStatus(analysisId)`. Return 409 if workspace is not running (check via `WorkspaceService`).
- [X] T011 Create `backend/src/main/java/com/mlplatform/controller/VisualizationController.java` with 4 REST endpoints under `/api/v1/analyses/{analysisId}/visualizations/` per api.yaml: `GET /files`, `POST /start`, `POST /stop`, `GET /status`. Delegate to `StreamlitService`. Follow existing controller patterns (e.g., `ExperimentController.java`).
- [X] T012 [P] Create `frontend/src/app/core/services/visualization.service.ts` with HttpClient methods: `getFiles(analysisId)`, `startApp(analysisId, filePath)`, `stopApp(analysisId)`, `getStatus(analysisId)`. Follow existing service patterns (e.g., `experiment.service.ts`).

**Checkpoint**: Foundation ready — Jupyter extension handles process management, backend proxies requests, frontend service provides HTTP layer. User story implementation can now begin.

---

## Phase 3: User Story 1 — View Streamlit Visualization in Analysis (Priority: P1) MVP

**Goal**: A user opens the Visualization tab and sees their Streamlit app running inline with full interactivity.

**Independent Test**: Create a `.py` file with Streamlit imports in the workspace's `visualize/` folder, navigate to the Visualization tab, verify the app renders interactively within the iframe.

### Implementation for User Story 1

- [ ] T013 [P] [US1] Create `frontend/src/app/features/visualization/visualization.component.ts` as a standalone Angular component. On init: call `getFiles()` → if files exist, call `startApp()` with the first file → poll `getStatus()` until running → set iframe URL using the proxy URL pattern `/user/{username}/{serverName}/proxy/{port}/`. Handle states: loading (spinner), running (iframe visible), errored (show error message), workspace-not-running (show message with link to Notebooks tab). Accept `analysisId`, `username`, `serverName` as inputs from parent. Keep component alive in DOM (not destroyed on tab switch).
- [ ] T014 [P] [US1] Create `frontend/src/app/features/visualization/visualization.component.html` with: loading indicator (`<div class="loading-card">` with spinner), iframe shell (`<div class="iframe-shell"><iframe [src]="streamlitUrl" ...></div>`), error display (`<div class="error-card">`), workspace-not-running message with navigation button to Notebooks tab. Include timeout message after 60 seconds with retry button per spec edge case.
- [ ] T015 [P] [US1] Create `frontend/src/app/features/visualization/visualization.component.scss` with styles matching existing tab patterns: `display: grid; gap: 1rem` layout, iframe with `width: 100%; border: 1px solid var(--border-color); border-radius: 8px; height: calc(100vh - 220px)`, loading/error card styles consistent with experiments.component.scss.
- [ ] T016 [US1] Modify `frontend/src/app/features/analyses/analysis-layout.component.ts` (inline template) to: (1) add "Visualization" tab after "Experiments" in the tab navigation, (2) add `<app-visualization>` component with `[style.display]="activeTab === 'visualization' ? 'block' : 'none'"` (same DOM preservation pattern as Notebooks component), (3) pass `analysisId`, `username`, `serverName` inputs, (4) add route handling for `visualization` tab value, (5) import VisualizationComponent in the component imports array.

**Checkpoint**: At this point, User Story 1 should be fully functional — a single Streamlit file in `visualize/` auto-loads in the Visualization tab.

---

## Phase 4: User Story 2 — Guided Onboarding When No Streamlit Files Exist (Priority: P2)

**Goal**: When no Streamlit files are found in `visualize/`, users see a helpful guide with instructions and a code snippet.

**Independent Test**: Open the Visualization tab in an analysis with no Streamlit files in `visualize/`, verify the guide content is displayed with explanation, folder convention, and code example.

### Implementation for User Story 2

- [ ] T017 [US2] Add onboarding empty state to `frontend/src/app/features/visualization/visualization.component.html`. When `files.length === 0` and workspace is running, display a guide card with: (1) heading explaining Streamlit visualization support, (2) explanation of the `visualize/` folder convention, (3) quick-start code snippet (`import streamlit as st` example) in a `<pre><code>` block, (4) step-by-step instructions to create a file and return to the tab. Follow the existing empty-state patterns in the codebase.
- [ ] T018 [US2] Add onboarding guide styles to `frontend/src/app/features/visualization/visualization.component.scss` — styled card with code block formatting, consistent with platform design patterns.

**Checkpoint**: At this point, User Stories 1 AND 2 should both work — files found shows the app, no files shows the guide.

---

## Phase 5: User Story 3 — Switch Between Multiple Streamlit Apps (Priority: P2)

**Goal**: When multiple Streamlit files exist in `visualize/`, users can switch between them via a dropdown selector.

**Independent Test**: Create two or more Streamlit files in `visualize/`, open the Visualization tab, verify the dropdown lists all files, and switching files replaces the running app.

### Implementation for User Story 3

- [ ] T019 [US3] Add dropdown file selector to `frontend/src/app/features/visualization/visualization.component.html`. Show a `<select>` or Material dropdown in the header area when `files.length > 1`, hidden when `files.length === 1`. Display file names from the files list. Bind to `selectedFile` model.
- [ ] T020 [US3] Implement file switching logic in `frontend/src/app/features/visualization/visualization.component.ts`. On dropdown selection change: set state to loading, call `stopApp()` then `startApp(newFilePath)`, poll `getStatus()` until running, update iframe URL. Implement FR-008a (one at a time — stop previous before starting new). Show loading indicator during transition.
- [ ] T021 [US3] Add dropdown selector styles to `frontend/src/app/features/visualization/visualization.component.scss` — position dropdown in top-right corner of the visualization header area, consistent with platform styling.

**Checkpoint**: At this point, User Stories 1, 2, AND 3 should all work independently — single file auto-loads, no files shows guide, multiple files shows dropdown.

---

## Phase 6: User Story 4 — Sample Streamlit Visualization in Default Notebook (Priority: P3)

**Goal**: New analyses include a sample Streamlit dashboard in `visualize/` that demonstrates realistic ML data visualization using the California Housing dataset.

**Independent Test**: Create a new analysis, open the workspace, verify `visualize/sample_dashboard.py` exists, load it in the Visualization tab, and interact with charts.

### Implementation for User Story 4

- [ ] T022 [US4] Create `infrastructure/docker/notebook-image/visualize/sample_dashboard.py` — a realistic Streamlit dashboard using the California Housing dataset (loaded from the platform's MinIO/Delta Lake sample data). Include: title and description, feature distribution histograms with selectable features, correlation heatmap, scatter plot of actual vs predicted values, interactive sliders for filtering data ranges. Use `st.sidebar` or `st.columns` for layout. Keep under 150 lines.
- [ ] T023 [US4] Add `visualize/sample_dashboard.py` to the workspace initialization mechanism so new analyses include the file. Follow the existing pattern used by `infrastructure/k8s/sample-data/sample-notebook-configmap.yaml` — either add to the existing ConfigMap or create a new ConfigMap for sample visualization files, and update the JupyterHub singleuser profile config to mount the `visualize/` directory into new workspaces.

**Checkpoint**: All user stories should now be independently functional — new analyses get the sample dashboard out of the box.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Platform package protection for custom images and end-to-end validation

- [ ] T024 Modify `backend/src/main/java/com/mlplatform/service/ImageBuildService.java` to add `--constraint /opt/ml-platform/platform-constraints.txt` to the `pip install` command in the generated Kaniko Dockerfile. This protects all platform packages (including Streamlit and jupyter-server-proxy) from being overridden by user packages in custom image builds per FR-014.
- [ ] T025 Build and deploy the updated notebook image with Streamlit, jupyter-server-proxy, and ml_platform_streamlit extension to the target cluster. Verify the image builds successfully and the extension is accessible.
- [ ] T026 Run all quickstart.md validation scenarios on deployed platform: (1) sample dashboard loads, (2) onboarding guide displays when no files, (3) custom app creation works, (4) file switching via dropdown, (5) workspace-not-running message, (6) tab preservation with state.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - US1 (Phase 3): Can start after Phase 2 — no dependencies on other stories
  - US2 (Phase 4): Can start after Phase 2 — modifies files created in US1, so best done after US1
  - US3 (Phase 5): Can start after Phase 2 — modifies files created in US1, so best done after US1
  - US4 (Phase 6): Can start after Phase 2 — independent of frontend (infrastructure only)
- **Polish (Phase 7)**: T024 can run any time after Phase 1; T025-T026 depend on all phases complete

### User Story Dependencies

- **User Story 1 (P1)**: Creates the VisualizationComponent files — all subsequent stories modify these files
- **User Story 2 (P2)**: Adds empty state to component created in US1 — depends on US1
- **User Story 3 (P2)**: Adds dropdown to component created in US1 — depends on US1
- **User Story 4 (P3)**: Infrastructure only (sample file + ConfigMap) — independent of US1-US3 but best verified after US1

### Within Each User Story

- Models/DTOs before services
- Services before controllers
- Controllers before frontend service
- Frontend service before components
- Core implementation before integration

### Parallel Opportunities

- T001 and T002 can run in parallel (different files)
- T004, T005, T006, T007, T008, T009, T012 can all run in parallel (different files, no dependencies)
- T013, T014, T015 can run in parallel (different component files)
- T022 can run in parallel with US1-US3 frontend work (infrastructure vs frontend)
- T024 can run in parallel with US2-US4 (different codebase area)

---

## Parallel Example: Phase 2 (Foundational)

```bash
# Launch all independent foundational tasks together:
Task: "Create __init__.py in ml_platform_streamlit/"           # T004
Task: "Create handlers.py in ml_platform_streamlit/"           # T005
Task: "Create StreamlitFileDto.java"                           # T006
Task: "Create StreamlitFileListDto.java"                       # T007
Task: "Create StreamlitStatusDto.java"                         # T008
Task: "Create StartStreamlitRequestDto.java"                   # T009
Task: "Create visualization.service.ts"                        # T012

# Then sequentially (depends on DTOs):
Task: "Create StreamlitService.java"                           # T010
Task: "Create VisualizationController.java"                    # T011
```

## Parallel Example: User Story 1

```bash
# Launch all component files together:
Task: "Create visualization.component.ts"                      # T013
Task: "Create visualization.component.html"                    # T014
Task: "Create visualization.component.scss"                    # T015

# Then sequentially (depends on component existing):
Task: "Modify analysis-layout.component.ts to add tab"         # T016
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (notebook image deps + constraints)
2. Complete Phase 2: Foundational (extension + backend + frontend service)
3. Complete Phase 3: User Story 1 (component + tab integration)
4. **STOP and VALIDATE**: Deploy and test — a single Streamlit file in `visualize/` loads in the Visualization tab
5. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 2 → Empty state guide works → Deploy/Demo
4. Add User Story 3 → Dropdown switching works → Deploy/Demo
5. Add User Story 4 → Sample dashboard in new analyses → Deploy/Demo
6. Polish → Custom image protection + full validation

### Recommended Execution Order (Single Developer)

1. T001, T002 (parallel) → T003
2. T004, T005, T006, T007, T008, T009, T012 (parallel) → T010 → T011
3. T013, T014, T015 (parallel) → T016
4. **Validate MVP** (US1)
5. T017 → T018
6. T019 → T020 → T021
7. T022 → T023
8. T024
9. T025 → T026

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- The Jupyter extension (T004-T005) and backend (T006-T011) can be developed in parallel since they communicate via HTTP
- The analysis-layout.component.ts has an INLINE template (no separate HTML/SCSS files) — T016 modifies the TypeScript file directly
- Platform-constraints.txt (T002) protects ALL platform packages, not just Streamlit — this is a cross-feature improvement
