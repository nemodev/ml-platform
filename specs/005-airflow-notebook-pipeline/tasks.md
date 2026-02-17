# Tasks: Airflow Notebook Pipeline

**Input**: Design documents from `/specs/005-airflow-notebook-pipeline/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/api.yaml

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Project structure, Airflow infrastructure, and shared configuration

- [X] T001 Create directory structure for Airflow infrastructure at infrastructure/helm/airflow/ and infrastructure/k8s/airflow/
- [X] T002 [P] Create Airflow Helm local-values.yaml with KubernetesExecutor, PostgreSQL metadata DB connection, notebook image as default pod image, and MinIO remote logging in infrastructure/helm/airflow/local-values.yaml
- [X] T003 [P] Create Airflow pod template for KubernetesExecutor task pods — sets notebook image, S3 env vars, MLFLOW_TRACKING_URI, ServiceAccount in infrastructure/helm/airflow/pod-template.yaml
- [X] T004 [P] Create Spark RBAC manifest (ServiceAccount `airflow-spark-sa`, Role with pod create/get/list/watch/delete, RoleBinding) in infrastructure/k8s/airflow/spark-rbac.yaml
- [X] T005 [P] Create notebook_runner DAG template as ConfigMap — single KubernetesPodOperator task, accepts dag_run.conf for notebook_path, output_path, parameters, enable_spark in infrastructure/k8s/airflow/dag-configmap.yaml
- [X] T006 [P] Create pipelines MinIO bucket `ml-platform-pipelines` in MinIO configuration (update MinIO Helm values or add to provisioning)
- [X] T007 [P] Add `papermill>=2.6.0` and `boto3` to notebook image requirements in infrastructure/docker/notebook-image/requirements.txt
- [X] T008 Update port-forward script to include Airflow webserver on port 8280 in infrastructure/scripts/port-forward.sh

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Backend services, database migration, and Airflow config that MUST be complete before user stories

**CRITICAL**: No user story work can begin until this phase is complete

- [X] T009 Create Flyway migration V005__create_pipeline_runs.sql with pipeline_runs table (id, user_id FK, notebook_name, input_path, output_path, status, airflow_dag_run_id, parameters JSONB, enable_spark, started_at, completed_at, error_message, created_at) and indexes in backend/src/main/resources/db/migration/V005__create_pipeline_runs.sql
- [X] T010 [P] Create PipelineRun JPA entity with status enum (PENDING, RUNNING, SUCCEEDED, FAILED), JSONB parameters field, relationships to User in backend/src/main/java/com/mlplatform/model/PipelineRun.java
- [X] T011 [P] Create PipelineRunRepository (Spring Data JPA) with findByUserIdOrderByCreatedAtDesc, findByUserIdAndStatus methods in backend/src/main/java/com/mlplatform/repository/PipelineRunRepository.java
- [X] T012 [P] Create AirflowConfig configuration class with airflow URL, username, password properties in backend/src/main/java/com/mlplatform/config/AirflowConfig.java
- [X] T013 [P] Create DTO classes: TriggerPipelineRequest, PipelineRunInfoDto, PipelineRunDetailDto, PipelineOutputUrlDto, NotebookInfoDto in backend/src/main/java/com/mlplatform/dto/
- [X] T014 Create AirflowService — REST client for Airflow API: triggerDagRun(dagId, conf), getDagRunStatus(dagId, runId), uses basic auth from AirflowConfig in backend/src/main/java/com/mlplatform/service/AirflowService.java
- [X] T015 [P] Create NotebookStorageService — MinIO S3 client for: copyNotebookToMinIO(userId, notebookPath, runId), generatePresignedUrl(outputPath) in backend/src/main/java/com/mlplatform/service/NotebookStorageService.java
- [X] T016 Add airflow config section (url, username, password) and MinIO S3 config to backend/src/main/resources/application.yaml, application-local.yaml, and application-dev.yaml
- [X] T017 [P] Create pipeline.service.ts Angular HTTP client with methods: triggerPipeline(), listRuns(), getRunDetail(), getOutputUrl(), listNotebooks() in frontend/src/app/core/services/pipeline.service.ts

**Checkpoint**: Foundation ready — database schema, JPA entity, Airflow client, MinIO client, and Angular service all in place

---

## Phase 3: User Story 1 — Trigger Notebook as Pipeline Job (Priority: P1)

**Goal**: User triggers a notebook from the Pipelines UI, the system copies it to MinIO, executes via Airflow/Papermill, and the user can view status and output.

**Independent Test**: Trigger a simple "hello world" notebook as a pipeline job, confirm it completes with SUCCEEDED status and output notebook is downloadable.

### Implementation for User Story 1

- [X] T018 [US1] Create PipelineService with triggerPipeline() method — validates notebook exists, creates PipelineRun record (PENDING), calls NotebookStorageService.copyNotebookToMinIO(), calls AirflowService.triggerDagRun() with notebook paths in conf, updates run with airflow_dag_run_id in backend/src/main/java/com/mlplatform/service/PipelineService.java
- [X] T019 [US1] Add getPipelineRun() method to PipelineService — fetches run from DB, if status is PENDING/RUNNING polls AirflowService.getDagRunStatus() and updates status accordingly in backend/src/main/java/com/mlplatform/service/PipelineService.java
- [X] T020 [US1] Add listPipelineRuns() and getOutputUrl() methods to PipelineService — list filtered by user, output URL via NotebookStorageService.generatePresignedUrl() in backend/src/main/java/com/mlplatform/service/PipelineService.java
- [X] T021 [US1] Add listNotebooks() method to PipelineService — calls JupyterHub Content API to list .ipynb files from user's workspace in backend/src/main/java/com/mlplatform/service/PipelineService.java
- [X] T022 [US1] Create PipelineController with endpoints: POST /pipelines (trigger), GET /pipelines (list), GET /pipelines/{runId} (detail), GET /pipelines/{runId}/output (download URL), GET /pipelines/notebooks (list notebooks) — all secured with @AuthenticationPrincipal Jwt in backend/src/main/java/com/mlplatform/controller/PipelineController.java
- [X] T023 [P] [US1] Create pipelines.component (list view) — shows table of pipeline runs with status badges (PENDING/RUNNING/SUCCEEDED/FAILED), notebook name, timestamps, auto-refresh for active runs in frontend/src/app/features/pipelines/pipelines.component.ts|html|scss
- [X] T024 [P] [US1] Create trigger-dialog.component — dropdown to select notebook (from /pipelines/notebooks), optional parameters key-value pairs, enableSpark toggle, trigger button in frontend/src/app/features/pipelines/trigger-dialog/trigger-dialog.component.ts|html|scss
- [X] T025 [P] [US1] Create run-detail.component — shows run status, parameters, timestamps, error message (if failed), "View Output" button (downloads output notebook via pre-signed URL) in frontend/src/app/features/pipelines/run-detail/run-detail.component.ts|html|scss
- [X] T026 [US1] Wire pipelines route in Angular router and add "Pipelines" navigation item to portal sidebar
- [X] T027 [US1] Deploy Airflow with official Helm chart — `helm install airflow apache-airflow/airflow -f infrastructure/helm/airflow/local-values.yaml`, apply DAG ConfigMap and Spark RBAC, verify notebook_runner DAG is loaded
- [X] T028 [US1] Verify US1 end-to-end: create simple notebook in JupyterHub, trigger from Pipelines UI, watch status PENDING → RUNNING → SUCCEEDED, download and inspect output notebook with cell outputs preserved

**Checkpoint**: User Story 1 complete — notebook pipeline triggering, status monitoring, and output retrieval all working

---

## Phase 4: User Story 2 — Pipeline Job with Spark (Priority: P2)

**Goal**: User triggers a notebook that creates a Spark session, executor pods are spawned, distributed processing completes, and executors are cleaned up.

**Independent Test**: Trigger a notebook with Spark code (spark.range + count), confirm executor pods appear during execution and are removed after completion.

### Implementation for User Story 2

- [X] T029 [US2] Update notebook_runner DAG template to conditionally configure Spark environment when enable_spark=true in dag_run.conf — set SPARK_HOME, spark.master=k8s://, spark.executor.instances=2, spark.executor.cores=1, spark.executor.memory=2g, spark.kubernetes.container.image to notebook image in infrastructure/k8s/airflow/dag-configmap.yaml
- [X] T030 [US2] Update pod-template.yaml to use `airflow-spark-sa` ServiceAccount when Spark is enabled — ensures task pod can create executor pods in infrastructure/helm/airflow/pod-template.yaml
- [X] T031 [US2] Add Spark client dependencies (PySpark 4.0.x, hadoop-aws JARs) to notebook Docker image if not already present — update infrastructure/docker/notebook-image/Dockerfile with Spark installation and spark-defaults.conf for MinIO S3A endpoint
- [X] T032 [US2] Verify US2 end-to-end: create notebook with SparkSession code, trigger with enableSpark=true, confirm executor pods created during execution (`kubectl get pods -l spark-role=executor`), confirm output notebook has Spark results, confirm executor pods cleaned up within 2 minutes

**Checkpoint**: User Story 2 complete — Spark pipeline jobs spawn executors, process data, and clean up

---

## Phase 5: User Story 3 — Pipeline with Experiment Tracking (Priority: P3)

**Goal**: A pipeline job that includes MLflow experiment tracking calls successfully logs runs visible in the experiment tracking UI.

**Independent Test**: Trigger a notebook that trains a model with mlflow.log_param/log_metric, confirm the run appears in the Experiments UI.

### Implementation for User Story 3

- [X] T033 [US3] Verify MLFLOW_TRACKING_URI environment variable is injected into Airflow task pods via pod-template.yaml (should already be set from Phase 1 T003) — confirm value matches backend MLflow proxy URL
- [X] T034 [US3] Verify US3 end-to-end: create notebook with MLflow tracking code (set_experiment, log_param, log_metric), trigger as pipeline, confirm run appears in Experiments UI with correct parameters and metrics, confirm pipeline-logged run is indistinguishable from interactive run

**Checkpoint**: User Story 3 complete — experiment tracking works from pipeline jobs exactly as from interactive notebooks

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: User isolation, error handling, and full validation

- [X] T035 Verify user isolation — scientist1's pipeline runs are NOT visible to scientist2 and vice versa
- [X] T036 Verify failed pipeline handling — trigger a notebook with intentional error, confirm FAILED status with error_message populated, confirm output notebook shows failing cell with traceback
- [ ] T037 Verify resource exhaustion edge case — trigger pipeline when cluster resources are limited, confirm PENDING status with appropriate messaging
- [X] T038 Add dev profile mock responses for AirflowService — return mock pipeline runs with simulated status transitions for backend development without Airflow in backend/src/main/resources/application-dev.yaml
- [ ] T039 Run full quickstart.md validation (all 12 steps)
- [X] T040 Verify pipeline completes within 5 minutes for simple notebook (SC-001)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational — core pipeline triggering
- **User Story 2 (Phase 4)**: Depends on US1 (pipeline must work) — adds Spark support
- **User Story 3 (Phase 5)**: Depends on US1 (pipeline must work) + feature 003 (MLflow) — adds experiment tracking
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — no other story dependencies
- **User Story 2 (P2)**: Depends on US1 working (pipeline triggering infrastructure)
- **User Story 3 (P3)**: Depends on US1 working + feature 003 (MLflow experiment tracking)
- US2 and US3 are independent of each other and can run in parallel after US1

### Within Each Phase

- Setup: T002-T007 can run in parallel (different files)
- Foundational: T010-T013, T015, T017 can run in parallel; T014 depends on T012; T016 independent
- US1: T018-T021 sequential (service methods build on each other); T023-T025 parallel (different components); T022 depends on T018-T021; T026 depends on T023-T025
- US2: T029-T031 can run in parallel; T032 depends on all
- US3: T033-T034 sequential

### Parallel Opportunities

- All Setup tasks marked [P] (T002-T007) can run in parallel
- Foundational: T010, T011, T012, T013, T015, T017 all in parallel
- US1: Frontend components T023, T024, T025 in parallel with backend T018-T022
- US2 and US3 can proceed in parallel once US1 is complete

---

## Parallel Example: User Story 1

```bash
# Backend service (sequential):
Task: "Create PipelineService with triggerPipeline()" (T018)
Task: "Add getPipelineRun() to PipelineService" (T019)
Task: "Add listPipelineRuns() and getOutputUrl()" (T020)
Task: "Add listNotebooks()" (T021)
Task: "Create PipelineController" (T022)

# Frontend components (parallel, after pipeline.service.ts from Phase 2):
Task: "Create pipelines.component" (T023)
Task: "Create trigger-dialog.component" (T024)
Task: "Create run-detail.component" (T025)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T008)
2. Complete Phase 2: Foundational (T009-T017)
3. Complete Phase 3: User Story 1 (T018-T028)
4. **STOP and VALIDATE**: Trigger notebook, see status, download output
5. Pipeline triggering works — feature delivers core value

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready
2. User Story 1 → Notebook pipeline execution (MVP!)
3. User Story 2 → Spark distributed processing
4. User Story 3 → MLflow experiment tracking from pipelines
5. Polish → Isolation, error handling, full validation

---

## Notes

- This feature has the most components of any feature so far: backend (controller + 3 services + entity + repo), frontend (3 components + 1 service), infrastructure (Airflow Helm + DAG + RBAC + MinIO bucket)
- No Bitnami Helm charts — use official Apache Airflow chart and official MinIO chart
- Airflow REST API uses basic auth (admin:admin for local dev)
- The notebook_runner DAG is a single reusable template — no per-notebook DAG generation
- Total tasks: 40 (8 setup, 9 foundational, 11 US1, 4 US2, 2 US3, 6 polish)
