# Tasks: JupyterHub Notebook Embedding

**Input**: Design documents from `/specs/002-jupyterhub-notebook/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Not explicitly requested in spec. Integration verification is included as part of implementation tasks.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Web app**: `backend/src/`, `frontend/src/`
- Backend: `backend/src/main/java/com/mlplatform/`
- Frontend: `frontend/src/app/`
- Infrastructure: `infrastructure/`

## Phase 1: Setup (Infrastructure & Image)

**Purpose**: JupyterHub Helm chart, custom notebook Docker image, Keycloak client registration, and JupyterHub database

- [X] T001 Create custom notebook Dockerfile in infrastructure/docker/notebook-image/Dockerfile based on `jupyter/scipy-notebook` with Python 3.11, adding system-level dependencies (Java 17 JRE for Spark compatibility)
- [X] T002 [P] Create Python requirements file in infrastructure/docker/notebook-image/requirements.txt listing all ML libraries: numpy, pandas, scikit-learn, matplotlib, mlflow, scipy, seaborn, plotly, torch, tensorflow, jupyterlab, notebook, jupyterhub, ipywidgets, ipykernel, pyarrow
- [X] T003 Create JupyterHub Helm chart in infrastructure/helm/jupyterhub/Chart.yaml declaring dependency on `jupyterhub/jupyterhub` chart v3.3.x from `https://hub.jupyter.org/helm-chart/`
- [X] T004 Create JupyterHub production values in infrastructure/helm/jupyterhub/values.yaml configuring: GenericOAuthenticator with Keycloak (`ml-platform` realm, client `ml-platform-jupyterhub`), single "Exploratory" KubeSpawner profile (1 CPU req / 2 CPU limit, 2Gi RAM req / 4Gi RAM limit), custom notebook image, dynamic PVC storage (10Gi, `/home/jovyan`), idle culler (1800s timeout, 300s check interval), PostgreSQL database connection, CSP `frame-ancestors` for portal domain, platform API service token
- [X] T005 [P] Create JupyterHub local dev values in infrastructure/helm/jupyterhub/local-values.yaml overriding: Keycloak URLs to `http://localhost:8180`, OAuth callback to `http://localhost:8181/hub/oauth_callback`, CSP frame-ancestors to include `http://localhost:4200`, PostgreSQL to `localhost:5432`, SQLite fallback option for simpler local setup
- [X] T006 Update Keycloak realm ConfigMap in infrastructure/k8s/keycloak/configmap.yaml adding confidential OIDC client `ml-platform-jupyterhub` with client secret, redirect URI `http://localhost:8181/hub/oauth_callback` (local) and production callback URI, standard flow enabled, scopes `openid profile email`
- [X] T007 [P] Update port-forward script in infrastructure/scripts/port-forward.sh adding JupyterHub proxy port-forward (8181:80)

**Checkpoint**: Infrastructure ready — custom image buildable, Helm chart configured, Keycloak client registered. JupyterHub deployable to K8s.

---

## Phase 2: Foundational (Backend Prerequisites)

**Purpose**: Database migration, JPA entity, repository, configuration, and JupyterHub service client — MUST be complete before ANY user story

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T008 Create Flyway migration V2__create_workspaces.sql in backend/src/main/resources/db/migration/ with `workspaces` table schema (id UUID PK, user_id UUID FK→users, profile VARCHAR DEFAULT 'EXPLORATORY', status VARCHAR DEFAULT 'PENDING', pod_name VARCHAR, jupyterhub_username VARCHAR NOT NULL, started_at TIMESTAMP, last_activity TIMESTAMP, created_at TIMESTAMP DEFAULT NOW) and indexes on user_id and status
- [X] T009 Create Workspace JPA entity in backend/src/main/java/com/mlplatform/model/Workspace.java mapping to the `workspaces` table with all fields from data-model.md, status enum (PENDING, RUNNING, IDLE, STOPPED, FAILED), and ManyToOne relationship to User
- [X] T010 Create WorkspaceRepository interface in backend/src/main/java/com/mlplatform/repository/WorkspaceRepository.java extending JpaRepository with `findByUserIdAndStatusIn(UUID userId, List<String> statuses)` and `findTopByUserIdOrderByCreatedAtDesc(UUID userId)` methods
- [X] T011 Create JupyterHubConfig in backend/src/main/java/com/mlplatform/config/JupyterHubConfig.java — configuration properties class reading `services.jupyterhub.url` and `services.jupyterhub.api-token` from application.yaml, with RestTemplate/WebClient bean for JupyterHub API calls
- [X] T012 Create JupyterHubService in backend/src/main/java/com/mlplatform/service/JupyterHubService.java — implements JupyterHub REST API client methods: `createUser(String username)` (POST /hub/api/users/{username}), `spawnServer(String username)` (POST /hub/api/users/{username}/server), `getServerStatus(String username)` (GET /hub/api/users/{username}), `stopServer(String username)` (DELETE /hub/api/users/{username}/server), `getLabUrl(String username)` returning `{jupyterhubUrl}/user/{username}/lab`. All calls use admin API token Bearer auth.
- [X] T013 [P] Create ComputeProfileDto in backend/src/main/java/com/mlplatform/dto/ComputeProfileDto.java matching contracts/api.yaml ComputeProfile schema (id, name, description, cpuRequest, cpuLimit, memoryRequest, memoryLimit, gpuLimit)
- [X] T014 [P] Create WorkspaceStatusDto in backend/src/main/java/com/mlplatform/dto/WorkspaceStatusDto.java matching contracts/api.yaml WorkspaceStatus schema (id, status, profile, startedAt, lastActivity, message)
- [X] T015 [P] Create WorkspaceUrlDto in backend/src/main/java/com/mlplatform/dto/WorkspaceUrlDto.java matching contracts/api.yaml WorkspaceUrl schema (url)
- [X] T016 Update application.yaml in backend/src/main/resources/ adding `services.jupyterhub.url` (`http://jupyterhub-hub.ml-platform.svc:8081`) and `services.jupyterhub.api-token` (`${JUPYTERHUB_API_TOKEN}`)
- [X] T017 [P] Update application-local.yaml in backend/src/main/resources/ overriding JupyterHub URL to `http://localhost:8181` and api-token to a local dev token
- [X] T018 [P] Update application-dev.yaml in backend/src/main/resources/ adding mock JupyterHub configuration (URL placeholder, dev token) for use without real JupyterHub
- [X] T019 Update build.gradle.kts in backend/ adding `spring-boot-starter-webflux` dependency (for WebClient to call JupyterHub API)

**Checkpoint**: Foundation ready — Workspace entity persisted, JupyterHub API client operational, all DTOs defined. User story implementation can begin.

---

## Phase 3: User Story 1 - Access Embedded Notebook (Priority: P1) 🎯 MVP

**Goal**: Authenticated user clicks "Notebooks" in portal, launches a notebook workspace, and sees JupyterLab embedded in an iframe

**Independent Test**: Log in to portal, click "Notebooks", confirm workspace launches and JupyterLab loads within the portal page with user's personal workspace

### Implementation for User Story 1

- [X] T020 [US1] Create WorkspaceService in backend/src/main/java/com/mlplatform/service/WorkspaceService.java — implement `launchWorkspace(Jwt jwt, String profile)` that: extracts username from JWT, calls UserService.syncFromJwt(), checks for existing active workspace (return 409 if exists), creates Workspace record (PENDING), calls JupyterHubService.createUser() then spawnServer(), returns WorkspaceStatusDto
- [X] T021 [US1] Add `getWorkspaceStatus(Jwt jwt)` method to WorkspaceService — queries JupyterHubService.getServerStatus() for current user, updates Workspace record status, returns WorkspaceStatusDto (returns STOPPED status if no active workspace)
- [X] T022 [US1] Add `terminateWorkspace(Jwt jwt)` method to WorkspaceService — calls JupyterHubService.stopServer(), updates Workspace record to STOPPED status
- [X] T023 [US1] Add `getWorkspaceUrl(Jwt jwt)` method to WorkspaceService — verifies workspace is RUNNING, calls JupyterHubService.getLabUrl(), returns WorkspaceUrlDto (404 if no running workspace)
- [X] T024 [US1] Add `getProfiles()` method to WorkspaceService — returns hardcoded list with single "Exploratory" ComputeProfileDto (1 CPU / 2Gi RAM, per research D3)
- [X] T025 [US1] Create WorkspaceController in backend/src/main/java/com/mlplatform/controller/WorkspaceController.java — implement all 5 endpoints per contracts/api.yaml: `GET /api/v1/workspaces/profiles`, `POST /api/v1/workspaces`, `GET /api/v1/workspaces`, `DELETE /api/v1/workspaces`, `GET /api/v1/workspaces/url`. All endpoints accept `@AuthenticationPrincipal Jwt jwt` and delegate to WorkspaceService
- [X] T026 [US1] Create workspace.service.ts in frontend/src/app/core/services/workspace.service.ts — HTTP client wrapping all workspace API calls: `getProfiles()`, `launchWorkspace(profile?)`, `getStatus()`, `terminateWorkspace()`, `getWorkspaceUrl()`, with polling support for status checks (3-second interval while PENDING)
- [X] T027 [US1] Replace notebooks placeholder component in frontend/src/app/features/notebooks/notebooks.component.ts — implement workspace launcher and iframe embedding: show "Launch Workspace" button when status is STOPPED, show loading spinner with status text when PENDING, show JupyterLab iframe when RUNNING (using DomSanitizer.bypassSecurityTrustResourceUrl), show error message with retry button when FAILED
- [X] T028 [US1] Create notebooks.component.html in frontend/src/app/features/notebooks/notebooks.component.html — template with conditional rendering: launcher view (profile info + launch button), pending view (spinner + "Starting notebook server..."), running view (full-height iframe with `allow="clipboard-read; clipboard-write"`), failed view (error message + retry)
- [X] T029 [US1] Create notebooks.component.scss in frontend/src/app/features/notebooks/notebooks.component.scss — styles for iframe (width: 100%, height: calc(100vh - 180px), border styling), launcher card, loading spinner, error state
- [ ] T030 [US1] Deploy JupyterHub and verify workspace launch end-to-end: build custom image, helm install jupyterhub with local-values, port-forward JupyterHub (8181:80), run backend with `local` profile, run frontend with `ng serve`, log in as `scientist1`, click Notebooks, confirm workspace launches and JupyterLab loads in iframe
- [ ] T031 [US1] Verify user isolation (SC-003): log in as `scientist1` in Chrome and `scientist2` in Firefox, confirm each gets separate workspace, create a notebook in scientist1's workspace, confirm it is NOT visible in scientist2's workspace

**Checkpoint**: User can launch a notebook workspace from the portal and see JupyterLab embedded in an iframe. US1 acceptance scenarios 1-3 verifiable.

---

## Phase 4: User Story 2 - Execute Python Code in Notebook (Priority: P2)

**Goal**: User can execute Python code in the embedded notebook with all pre-installed ML libraries available

**Independent Test**: Open a notebook in the embedded JupyterLab, run `import pandas, numpy, scipy, sklearn, matplotlib, seaborn, plotly, torch, tensorflow, mlflow` and confirm all imports succeed

### Implementation for User Story 2

- [ ] T032 [US2] Verify and finalize infrastructure/docker/notebook-image/requirements.txt — ensure all libraries from FR-004 are included with pinned versions: numpy, pandas, scikit-learn, matplotlib, mlflow, scipy, seaborn, plotly, torch (CPU build for smaller image), tensorflow, jupyterlab, notebook, ipywidgets, ipykernel, pyarrow. Build the image and verify it starts successfully
- [ ] T033 [US2] Verify code execution end-to-end: launch workspace, create new notebook, execute `import pandas as pd; pd.DataFrame({'a': [1,2,3]})`, confirm dataframe renders. Execute `import numpy, scipy, sklearn, matplotlib, seaborn, plotly, torch, tensorflow, mlflow; print("All imports successful!")`, confirm all imports succeed
- [ ] T034 [US2] Verify matplotlib inline rendering: execute `import matplotlib.pyplot as plt; plt.plot([1,2,3],[4,5,6]); plt.show()`, confirm plot renders inline in cell output
- [ ] T035 [US2] Verify error handling: execute code that raises an exception (e.g., `1/0`), confirm traceback is displayed in cell output

**Checkpoint**: User Story 2 complete. All ML libraries importable, code execution and plotting verified. US2 acceptance scenarios 1-4 verifiable.

---

## Phase 5: User Story 3 - SSO Passthrough to Notebook (Priority: P3)

**Goal**: Authenticated portal user navigates to Notebooks without any secondary login — Keycloak SSO session is shared with JupyterHub

**Independent Test**: Log in to portal once, navigate to Notebooks, confirm JupyterLab loads without any login prompt appearing

### Implementation for User Story 3

- [ ] T036 [US3] Verify GenericOAuthenticator SSO flow: confirm JupyterHub Helm values have correct Keycloak authorize_url, token_url, userdata_url, client_id, and client_secret. Verify `username_claim: preferred_username` and `allow_all: true` are set
- [ ] T037 [US3] Verify iframe SSO passthrough: log in to portal as scientist1, navigate to Notebooks, observe browser DevTools Network tab — confirm the iframe does NOT show a JupyterHub login page. The Keycloak SSO session cookie should be recognized by JupyterHub's OAuth flow, completing authentication silently
- [ ] T038 [US3] Verify page refresh preserves session: while JupyterLab is loaded in the iframe, refresh the portal page (F5), confirm the notebook reloads without re-authentication
- [ ] T039 [US3] Verify session expiry redirect: configure Keycloak access token lifespan to 1 minute (temporarily), let the token expire without silent refresh, navigate to Notebooks, confirm the user is redirected to the portal login page (not a JupyterHub login page). Restore normal token lifespan after test

**Checkpoint**: User Story 3 complete. SSO passthrough verified — no secondary login required. US3 acceptance scenarios 1-3 verifiable.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Persistence verification, error handling, dev profile support, and deployment readiness

- [ ] T040 [P] Verify persistent storage (SC-005): launch workspace as scientist1, create and save a notebook file, terminate the workspace (or wait for 30-min idle culler), relaunch workspace, confirm the previously saved file is still present
- [ ] T041 [P] Verify cold start performance (SC-001): stop the workspace, navigate to Notebooks to trigger a cold start, measure time from click to JupyterLab ready — confirm under 120 seconds. Then verify warm start (server already running, navigate away and back) is under 60 seconds
- [X] T042 Add dev profile mock workspace support: create DevWorkspaceService (or conditional logic in WorkspaceService) that returns mock responses when `dev` profile is active — `getStatus()` returns RUNNING, `getWorkspaceUrl()` returns a placeholder URL or static JupyterLab demo page. This allows frontend development without JupyterHub deployed
- [X] T043 Add error handling for JupyterHub unreachable scenario in WorkspaceController — catch connection errors from JupyterHubService, return user-friendly ErrorResponse with 503 status
- [ ] T044 Run quickstart.md validation — execute all steps from specs/002-jupyterhub-notebook/quickstart.md on the cluster and confirm every step succeeds

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies on other phases — can start immediately (requires feature 001 deployed)
- **Foundational (Phase 2)**: Depends on Phase 1 completion (Helm chart, image, Keycloak client) — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - US2 depends on US1 (needs workspace running to test code execution)
  - US3 depends on US1 (needs workspace to test SSO passthrough)
  - US2 and US3 can run in parallel after US1
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — no other story dependencies
- **User Story 2 (P2)**: Depends on US1 complete (needs embedded notebook running to verify libraries)
- **User Story 3 (P3)**: Depends on US1 complete (needs embedded notebook to verify SSO). Can run in parallel with US2.

### Within Each User Story

- Backend services before controllers
- Controllers before frontend components
- Core implementation before integration verification
- Story complete before moving to next priority

### Parallel Opportunities

- T002 can run in parallel with T001 (requirements.txt vs Dockerfile)
- T005 can run in parallel with T004 (local-values vs production values)
- T006, T007 can run in parallel (Keycloak config vs port-forward script)
- T013, T014, T015 can run in parallel (independent DTOs)
- T017, T018 can run in parallel (independent profile configs)
- T040, T041 can run in parallel (independent verification tasks)

---

## Parallel Example: Phase 1 Setup

```bash
# After T001 (Dockerfile) and T003 (Chart.yaml):
Task: T002 "Create requirements.txt"       # parallel with T001
Task: T005 "Create local-values.yaml"      # parallel with T004
Task: T006 "Update Keycloak configmap"      # parallel with T007
Task: T007 "Update port-forward script"     # parallel with T006
```

## Parallel Example: Phase 2 Foundational

```bash
# After T011 (JupyterHubConfig) and T012 (JupyterHubService):
Task: T013 "Create ComputeProfileDto"
Task: T014 "Create WorkspaceStatusDto"
Task: T015 "Create WorkspaceUrlDto"

# After T016 (application.yaml):
Task: T017 "Update application-local.yaml"
Task: T018 "Update application-dev.yaml"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T007)
2. Complete Phase 2: Foundational (T008-T019)
3. Complete Phase 3: User Story 1 (T020-T031)
4. **STOP and VALIDATE**: Verify workspace launch and iframe embedding end-to-end
5. Deploy/demo if ready — user can launch notebook and see JupyterLab

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test workspace launch → Deploy/Demo (MVP!)
3. Add User Story 2 → Test code execution → Deploy/Demo
4. Add User Story 3 → Test SSO passthrough → Deploy/Demo
5. Polish → Persistence, performance, error handling verification

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Feature 001 (Keycloak Auth & Portal Shell) MUST be deployed before starting any Phase 1 tasks
- Reference prior project patterns at `~/projects/data-intelligence/` for implementation details (JupyterHubService, WorkspaceController, GenericOAuthenticator config, notebook Dockerfile)
