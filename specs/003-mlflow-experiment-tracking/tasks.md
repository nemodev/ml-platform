# Tasks: MLflow Experiment Tracking

**Input**: Design documents from `/specs/003-mlflow-experiment-tracking/`
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

## Phase 1: Setup (Infrastructure — MinIO, MLflow, Database)

**Purpose**: Deploy MinIO for artifact storage, MLflow tracking server, create MLflow database, and update Keycloak/JupyterHub configuration

- [X] T001 Create MLflow Helm chart metadata in infrastructure/helm/mlflow/Chart.yaml with chart name `mlflow`, version `0.1.0`, description, and appVersion `3.10.0`
- [X] T002 Create MLflow Helm deployment template in infrastructure/helm/mlflow/templates/deployment.yaml using image `ghcr.io/mlflow/mlflow:3.10.0`, init container installing `psycopg2-binary` and `boto3`, main container running `mlflow server --host 0.0.0.0 --port 5000 --backend-store-uri postgresql://... --default-artifact-root s3://ml-platform-mlflow/artifacts --serve-artifacts`, environment variables for MinIO credentials (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `MLFLOW_S3_ENDPOINT_URL`, `MLFLOW_S3_IGNORE_TLS`), readiness/liveness probes on `/health`, resources (500m/512Mi request, 2/2Gi limit)
- [X] T003 [P] Create MLflow Helm service template in infrastructure/helm/mlflow/templates/service.yaml as ClusterIP on port 5000
- [X] T004 [P] Create MLflow Helm configmap template in infrastructure/helm/mlflow/templates/configmap.yaml with environment variables: `MLFLOW_S3_ENDPOINT_URL`, database connection string, `MLFLOW_S3_IGNORE_TLS=true`
- [X] T005 Create MLflow production values in infrastructure/helm/mlflow/values.yaml with image tag, PostgreSQL connection (`postgresql.ml-platform.svc:5432/mlflow`), MinIO endpoint (`http://minio.ml-platform.svc:9000`), artifact root `s3://ml-platform-mlflow/artifacts`, gunicorn workers (2), resource limits, MinIO secret reference
- [X] T006 [P] Create MLflow local dev values in infrastructure/helm/mlflow/local-values.yaml overriding: PostgreSQL to `localhost:5432`, MinIO to `localhost:9000`, credentials to `minioadmin/minioadmin`, database password to `localdevpassword`
- [X] T007 [P] Create MinIO local values in infrastructure/helm/minio/local-values.yaml for Bitnami MinIO Helm chart with `auth.rootUser=minioadmin`, `auth.rootPassword=minioadmin`, `defaultBuckets=ml-platform-mlflow`, single-node mode, 10Gi persistence
- [X] T008 Update port-forward script in infrastructure/scripts/port-forward.sh adding MLflow port-forward (5000:5000) and MinIO port-forward (9000:9000, 9001:9001 for console)

**Checkpoint**: Infrastructure ready — MinIO deployable for artifact storage, MLflow Helm chart ready, database creation scripted. MLflow deployable to K8s.

---

## Phase 2: Foundational (Backend Prerequisites)

**Purpose**: MLflow service client, experiment proxy with user-prefix logic, DTOs, and configuration — MUST be complete before ANY user story

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T009 Create MlflowConfig in backend/src/main/java/com/mlplatform/config/MlflowConfig.java — configuration properties class reading `services.mlflow.url` and `services.mlflow.tracking-url` from application.yaml, with RestTemplate bean for MLflow API calls using admin headers
- [X] T010 Create MlflowService in backend/src/main/java/com/mlplatform/service/MlflowService.java — implements MLflow REST API v2.0 proxy methods: `createExperiment(String prefixedName)` (POST /api/2.0/mlflow/experiments/create), `searchExperiments(String nameFilter)` (GET /api/2.0/mlflow/experiments/search), `getExperiment(String experimentId)` (GET /api/2.0/mlflow/experiments/get), `searchRuns(String experimentId)` (POST /api/2.0/mlflow/runs/search). All calls use RestTemplate with MLflow base URL
- [X] T011 Add user-prefix logic to MlflowService — implement `getUserPrefix(Jwt jwt)` extracting `preferred_username` from JWT, `prefixExperimentName(String username, String name)` returning `username/name`, `stripPrefix(String prefixedName, String username)` returning the user-facing name, `filterByUserPrefix(List<Experiment> experiments, String username)` returning only experiments matching the prefix
- [X] T012 [P] Create CreateExperimentRequest DTO in backend/src/main/java/com/mlplatform/dto/CreateExperimentRequest.java with `name` field per contracts/api.yaml
- [X] T013 [P] Create ExperimentInfoDto in backend/src/main/java/com/mlplatform/dto/ExperimentInfoDto.java matching contracts/api.yaml ExperimentInfo schema (experimentId, name, artifactLocation, lifecycleStage, creationTime, lastUpdateTime)
- [X] T014 [P] Create ExperimentDetailDto in backend/src/main/java/com/mlplatform/dto/ExperimentDetailDto.java matching contracts/api.yaml ExperimentDetail schema (extends ExperimentInfo with runs list)
- [X] T015 [P] Create RunInfoDto in backend/src/main/java/com/mlplatform/dto/RunInfoDto.java matching contracts/api.yaml RunInfo schema (runId, experimentId, status, startTime, endTime, parameters map, metrics map, artifactUri)
- [X] T016 [P] Create TrackingUrlDto in backend/src/main/java/com/mlplatform/dto/TrackingUrlDto.java matching contracts/api.yaml TrackingUrl schema (url)
- [X] T017 Update application.yaml in backend/src/main/resources/ adding `services.mlflow.url` (`http://mlflow.ml-platform.svc:5000`) and `services.mlflow.tracking-url` (`http://mlflow.ml-platform.svc:5000`)
- [X] T018 [P] Update application-local.yaml in backend/src/main/resources/ overriding MLflow URL to `http://localhost:5000` and tracking-url to `http://localhost:5000`
- [X] T019 [P] Update application-dev.yaml in backend/src/main/resources/ adding mock MLflow configuration (URL placeholder) for dev profile without real MLflow

**Checkpoint**: Foundation ready — MlflowService can create/list/get experiments with user-prefix isolation, all DTOs defined. User story implementation can begin.

---

## Phase 3: User Story 1 - Log Experiment from Notebook (Priority: P1) 🎯 MVP

**Goal**: User runs a training script in a notebook that logs parameters, metrics, and a model artifact to the tracking server via the backend proxy

**Independent Test**: Run a notebook cell that calls `mlflow.log_param()`, `mlflow.log_metric()`, and `mlflow.sklearn.log_model()`, then query the tracking server to confirm the run exists

### Implementation for User Story 1

- [X] T020 [US1] Create ExperimentController in backend/src/main/java/com/mlplatform/controller/ExperimentController.java — implement `POST /api/v1/experiments` (creates experiment with user-prefixed name via MlflowService, returns ExperimentInfoDto with prefix stripped), `GET /api/v1/experiments` (lists experiments filtered by user prefix, strips prefix from names), `GET /api/v1/experiments/{experimentId}` (gets experiment detail, verifies ownership by checking prefix), `GET /api/v1/experiments/{experimentId}/runs` (lists runs, verifies experiment ownership). All endpoints accept `@AuthenticationPrincipal Jwt jwt`
- [X] T021 [US1] Add MLflow proxy pass-through endpoint in ExperimentController — implement `ALL /api/v1/mlflow-proxy/**` that forwards requests to the MLflow tracking server, injecting the authenticated user's prefix for experiment creation. This endpoint is used by the mlflow Python client in notebooks (via `MLFLOW_TRACKING_URI`)
- [X] T022 [US1] Update JupyterHub Helm values in infrastructure/helm/jupyterhub/values.yaml and local-values.yaml — add `singleuser.extraEnv.MLFLOW_TRACKING_URI` pointing to `http://backend.ml-platform.svc:8080/api/v1/mlflow-proxy` (production) and `http://host.docker.internal:8080/api/v1/mlflow-proxy` or equivalent for local dev, so that `import mlflow` in notebooks connects to the backend proxy automatically
- [X] T023 [US1] Deploy MinIO, MLflow, and verify experiment logging end-to-end: deploy MinIO via `helm install minio oci://registry-1.docker.io/bitnamicharts/minio -n ml-platform -f infrastructure/helm/minio/local-values.yaml`, create `mlflow` database in PostgreSQL, deploy MLflow via `helm install mlflow infrastructure/helm/mlflow/ -n ml-platform -f infrastructure/helm/mlflow/local-values.yaml`, port-forward MLflow (5000:5000), run backend with `local` profile, upgrade JupyterHub with tracking URI config
- [ ] T024 [US1] Verify experiment logging from notebook: log in as scientist1, open notebook, run training script that calls `mlflow.set_experiment("iris-classification")`, `mlflow.log_param("n_estimators", 100)`, `mlflow.log_metric("accuracy", 0.95)`, `mlflow.sklearn.log_model(model, "model")`. Confirm the run is logged successfully
- [X] T025 [US1] Verify experiment retrieval via API: call `GET /api/v1/experiments` with scientist1's token, confirm "iris-classification" experiment is listed. Call `GET /api/v1/experiments/{id}/runs`, confirm the run with parameters and metrics is returned
- [ ] T026 [US1] Verify multiple runs comparison: run the training script 3 more times with different hyperparameters, call `GET /api/v1/experiments/{id}/runs`, confirm all 4 runs are listed with their respective metrics

**Checkpoint**: User can log experiments from notebooks and retrieve them via API. US1 acceptance scenarios 1-4 verifiable.

---

## Phase 4: User Story 2 - View Experiments in Embedded UI (Priority: P2)

**Goal**: User navigates to "Experiments" in the portal and sees the MLflow UI embedded in an iframe, showing their experiments and runs

**Independent Test**: Log in, click "Experiments", confirm MLflow UI loads in iframe showing the previously logged experiment

### Implementation for User Story 2

- [X] T027 [US2] Add `GET /api/v1/experiments/tracking-url` endpoint to ExperimentController — returns TrackingUrlDto with the MLflow UI URL (from `services.mlflow.tracking-url` config), used by the frontend to set the iframe src
- [X] T028 [US2] Create experiment.service.ts in frontend/src/app/core/services/experiment.service.ts — HTTP client wrapping all experiment API calls: `listExperiments()`, `getExperiment(id)`, `getRuns(experimentId)`, `getTrackingUrl()`, `createExperiment(name)`
- [X] T029 [US2] Replace experiments placeholder component in frontend/src/app/features/experiments/experiments.component.ts — implement MLflow UI iframe embedding: on init, call `experiment.service.getTrackingUrl()`, sanitize URL via `DomSanitizer.bypassSecurityTrustResourceUrl()`, display in full-height iframe. Show loading state while URL is being fetched, error state if MLflow is unreachable
- [X] T030 [US2] Create experiments.component.html in frontend/src/app/features/experiments/experiments.component.html — template with MLflow iframe (`allow="clipboard-read; clipboard-write"`), loading spinner, and error state with retry button
- [X] T031 [US2] Create experiments.component.scss in frontend/src/app/features/experiments/experiments.component.scss — styles for iframe (width: 100%, height: calc(100vh - 180px), border styling), matching the notebooks iframe pattern from feature 002
- [X] T032 [US2] Verify MLflow UI embedding end-to-end: log in as scientist1, click "Experiments" in sidebar, confirm MLflow UI loads in iframe showing the "iris-classification" experiment, click on the experiment to see runs, select 2+ runs to compare metrics side-by-side, click a run to view artifact details

**Checkpoint**: User Story 2 complete. MLflow UI embedded in portal, experiments and runs viewable. US2 acceptance scenarios 1-4 verifiable.

---

## Phase 5: User Story 3 - SSO Passthrough to Experiment Tracking UI (Priority: P3)

**Goal**: User navigates to Experiments without any secondary login, and sees only their own experiments

**Independent Test**: Log in once, click "Experiments", confirm MLflow UI loads without login prompt; verify a second user sees only their own experiments

### Implementation for User Story 3

- [X] T033 [US3] Verify no-login passthrough: log in to portal as scientist1, navigate to Experiments, confirm the MLflow UI iframe loads without any login prompt (MLflow has no auth layer, so it loads directly via ClusterIP/port-forward)
- [ ] T034 [US3] Verify user isolation (SC-003): log in as scientist1 in Chrome and scientist2 in Firefox, each creates experiments from notebooks with different names, navigate to Experiments section in each browser — confirm via the backend API (`GET /api/v1/experiments`) that each user sees only their own experiments (user-prefix filtering). Note: the iframe shows the full MLflow UI which does NOT filter by user — the backend API endpoints provide the filtered view
- [X] T035 [US3] Add user-filtered experiment list view to experiments.component.ts — alongside the MLflow iframe, add a sidebar or header section that calls `experiment.service.listExperiments()` to show only the current user's experiments. Clicking an experiment in this list navigates the iframe to that experiment's detail page (by appending `#/experiments/{id}` to the MLflow URL)

**Checkpoint**: User Story 3 complete. No secondary login, user sees filtered experiment list. US3 acceptance scenarios 1-2 verifiable.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Performance verification, error handling, dev profile support, and deployment readiness

- [ ] T036 [P] Verify SC-001 performance: log a training run from a notebook (with model artifact), immediately navigate to Experiments, confirm the run appears in the UI within 30 seconds of the cell completing
- [ ] T037 [P] Verify artifact storage: log a model artifact from a notebook, navigate to the run in the MLflow UI, confirm the model artifact is listed and downloadable. Verify artifacts are stored in MinIO bucket `ml-platform-mlflow` via MinIO console (port 9001)
- [X] T038 Add dev profile mock experiment support: add conditional logic in MlflowService or a DevMlflowService that returns mock experiment/run data when `dev` profile is active — mock experiments list, mock runs with sample parameters/metrics, placeholder tracking URL. This allows frontend development without MLflow/MinIO deployed
- [X] T039 Add error handling for MLflow unreachable scenario in ExperimentController — catch connection errors from MlflowService, return user-friendly ErrorResponse with 503 status and message "Experiment tracking server is unavailable"
- [ ] T040 Run quickstart.md validation — execute all steps from specs/003-mlflow-experiment-tracking/quickstart.md on the cluster and confirm every step succeeds

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies on other phases — can start immediately (requires features 001+002 deployed)
- **Foundational (Phase 2)**: Depends on Phase 1 (Helm charts, MinIO, MLflow deployed) — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - US2 depends on US1 (needs logged experiments to display in UI)
  - US3 depends on US2 (needs embedded UI to verify SSO)
  - US2 and US3 must be sequential after US1
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — no other story dependencies
- **User Story 2 (P2)**: Depends on US1 complete (needs logged experiments to display)
- **User Story 3 (P3)**: Depends on US2 complete (needs embedded UI to verify passthrough and add filtered view)

### Within Each User Story

- Backend services before controllers
- Controllers before frontend components
- Core implementation before integration verification
- Story complete before moving to next priority

### Parallel Opportunities

- T003, T004 can run in parallel with T002 (service + configmap vs deployment template)
- T006, T007 can run in parallel (independent local values files)
- T012, T013, T014, T015, T016 can run in parallel (independent DTOs)
- T018, T019 can run in parallel (independent profile configs)
- T036, T037 can run in parallel (independent verification tasks)

---

## Parallel Example: Phase 1 Setup

```bash
# After T002 (deployment template):
Task: T003 "Create MLflow service template"      # parallel with T004
Task: T004 "Create MLflow configmap template"     # parallel with T003

# After T005 (production values):
Task: T006 "Create MLflow local values"           # parallel with T007
Task: T007 "Create MinIO local values"            # parallel with T006
```

## Parallel Example: Phase 2 Foundational

```bash
# After T010/T011 (MlflowService):
Task: T012 "Create CreateExperimentRequest DTO"
Task: T013 "Create ExperimentInfoDto"
Task: T014 "Create ExperimentDetailDto"
Task: T015 "Create RunInfoDto"
Task: T016 "Create TrackingUrlDto"

# After T017 (application.yaml):
Task: T018 "Update application-local.yaml"
Task: T019 "Update application-dev.yaml"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T008)
2. Complete Phase 2: Foundational (T009-T019)
3. Complete Phase 3: User Story 1 (T020-T026)
4. **STOP and VALIDATE**: Verify experiment logging from notebook end-to-end
5. Deploy/demo if ready — user can log and retrieve experiments

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test experiment logging → Deploy/Demo (MVP!)
3. Add User Story 2 → Test embedded MLflow UI → Deploy/Demo
4. Add User Story 3 → Test SSO passthrough + isolation → Deploy/Demo
5. Polish → Performance, error handling verification

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Features 001 + 002 (Keycloak Auth & Portal + JupyterHub) MUST be deployed before starting any Phase 1 tasks
- Reference prior project patterns at `~/projects/data-intelligence/` for implementation details (MlflowService, ExperimentController, MinIO config, MLflow Helm chart)
- The MLflow UI iframe shows ALL experiments (no auth). User isolation is enforced by the backend API (`/api/v1/experiments`), which filters by user prefix. The frontend adds a filtered sidebar alongside the iframe.
