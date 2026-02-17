# Tasks: Model Serving & Inference

**Input**: Design documents from `/specs/006-model-serving-inference/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/api.yaml

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: KServe installation, serving namespace, and shared infrastructure

- [ ] T001 Install KServe CRDs and controller in raw deployment mode — apply upstream kserve.yaml and kserve-cluster-resources.yaml, patch inferenceservice-config for RawDeployment default
- [ ] T002 [P] Create serving namespace manifest at infrastructure/k8s/kserve/serving-namespace.yaml — namespace `ml-platform-serving`
- [ ] T003 [P] Create KServe S3 Secret for MinIO access at infrastructure/k8s/kserve/s3-secret.yaml — annotated with serving.kserve.io/s3-endpoint and s3-usehttps=0, contains MinIO credentials
- [ ] T004 [P] Create ServiceAccount `kserve-s3-sa` at infrastructure/k8s/kserve/service-account.yaml — references the S3 Secret for model artifact download
- [ ] T005 Apply namespace, Secret, and ServiceAccount: `kubectl apply -f infrastructure/k8s/kserve/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Backend services, database migration, and frontend services that MUST be complete before user stories

**CRITICAL**: No user story work can begin until this phase is complete

- [ ] T006 Create Flyway migration V006__create_model_deployments.sql with model_deployments table (id, user_id FK, model_name, model_version, endpoint_name UNIQUE, status, inference_url, storage_uri, error_message, created_at, ready_at, deleted_at) and indexes in backend/src/main/resources/db/migration/V006__create_model_deployments.sql
- [ ] T007 [P] Create ModelDeployment JPA entity with status enum (DEPLOYING, READY, FAILED, DELETING, DELETED), endpoint_name unique constraint, relationships to User in backend/src/main/java/com/mlplatform/model/ModelDeployment.java
- [ ] T008 [P] Create ModelDeploymentRepository (Spring Data JPA) with findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc, findByEndpointName methods in backend/src/main/java/com/mlplatform/repository/ModelDeploymentRepository.java
- [ ] T009 [P] Create KServeConfig configuration class with serving namespace, K8s API client bean in backend/src/main/java/com/mlplatform/config/KServeConfig.java
- [ ] T010 [P] Create DTO classes: RegisteredModelInfoDto, ModelVersionInfoDto, DeployModelRequest, DeploymentInfoDto, DeploymentDetailDto, PredictionRequestDto, PredictionResponseDto in backend/src/main/java/com/mlplatform/dto/
- [ ] T011 Create ModelRegistryService — MLflow Model Registry REST API client: listRegisteredModels (filtered by user prefix), getModelVersions, getModelVersionDetail (resolves artifact URI) in backend/src/main/java/com/mlplatform/service/ModelRegistryService.java
- [ ] T012 Create KServeService — Kubernetes Java client for InferenceService CRDs: createInferenceService (builds CRD with mlflow modelFormat, storageUri, raw deployment annotation, ServiceAccount), getInferenceServiceStatus, deleteInferenceService in backend/src/main/java/com/mlplatform/service/KServeService.java
- [ ] T013 [P] Add kserve config section (serving namespace) and kubernetes client config to backend/src/main/resources/application.yaml, application-local.yaml, and application-dev.yaml
- [ ] T014 [P] Create model.service.ts Angular HTTP client with methods: listRegisteredModels(), getModelVersions(modelName) in frontend/src/app/core/services/model.service.ts
- [ ] T015 [P] Create serving.service.ts Angular HTTP client with methods: deployModel(), listDeployments(), getDeployment(), deleteDeployment(), predict() in frontend/src/app/core/services/serving.service.ts

**Checkpoint**: Foundation ready — database schema, JPA entity, MLflow registry client, KServe client, and Angular services all in place

---

## Phase 3: User Story 1 — Deploy Registered Model to Inference Endpoint (Priority: P1)

**Goal**: User registers a model in MLflow Model Registry from a notebook, then deploys it as a KServe inference endpoint via the portal. The endpoint status progresses from DEPLOYING to READY.

**Independent Test**: Register a scikit-learn model in MLflow, deploy it via portal, confirm InferenceService is created and endpoint becomes READY with health check passing.

### Implementation for User Story 1

- [ ] T016 [US1] Create ServingService with deployModel() method — validates model exists in MLflow registry, resolves artifact URI, creates ModelDeployment record (DEPLOYING), calls KServeService.createInferenceService() with endpoint name `{username}-{model}-v{version}` in backend/src/main/java/com/mlplatform/service/ServingService.java
- [ ] T017 [US1] Add getDeployment() method to ServingService — fetches from DB, if DEPLOYING polls KServeService.getInferenceServiceStatus() and updates status/inferenceUrl/errorMessage accordingly in backend/src/main/java/com/mlplatform/service/ServingService.java
- [ ] T018 [US1] Add listDeployments() and deleteDeployment() methods to ServingService — list filtered by user (excluding soft-deleted), delete calls KServeService.deleteInferenceService() and sets status to DELETING/DELETED in backend/src/main/java/com/mlplatform/service/ServingService.java
- [ ] T019 [US1] Create ModelController with endpoints: GET /models (list registered models), GET /models/{modelName}/versions (list versions) — secured with @AuthenticationPrincipal Jwt, user prefix filtering in backend/src/main/java/com/mlplatform/controller/ModelController.java
- [ ] T020 [US1] Create ServingController with endpoints: POST /serving/deployments (deploy), GET /serving/deployments (list), GET /serving/deployments/{id} (detail), DELETE /serving/deployments/{id} (delete) — secured with @AuthenticationPrincipal Jwt in backend/src/main/java/com/mlplatform/controller/ServingController.java
- [ ] T021 [P] [US1] Create models.component — lists registered models from MLflow registry with latest version, "Deploy" button per model in frontend/src/app/features/models/models.component.ts|html|scss
- [ ] T022 [P] [US1] Create deploy-dialog.component — select model version from dropdown, confirm deploy button in frontend/src/app/features/models/deploy-dialog/deploy-dialog.component.ts|html|scss
- [ ] T023 [P] [US1] Create deployments.component — lists active deployments with status badges (DEPLOYING/READY/FAILED), endpoint name, model info, delete button, auto-refresh for DEPLOYING status in frontend/src/app/features/models/deployments/deployments.component.ts|html|scss
- [ ] T024 [US1] Wire models and deployments routes in Angular router, add "Models" navigation item to portal sidebar
- [ ] T025 [US1] Verify US1 end-to-end: register a scikit-learn model in notebook (`mlflow.register_model()`), navigate to Models UI, deploy version 1, watch status DEPLOYING → READY, verify `kubectl get inferenceservices -n ml-platform-serving` shows READY, verify health check at `/v2/models/{name}/ready`

**Checkpoint**: User Story 1 complete — model deployment lifecycle from registry to live endpoint working

---

## Phase 4: User Story 2 — Make Inference Call to Deployed Model (Priority: P2)

**Goal**: User sends prediction requests to a deployed endpoint via the portal or from a pipeline DAG. The V2 inference protocol returns structured prediction responses.

**Independent Test**: Send a prediction request with valid California Housing features, receive a valid numeric prediction in V2 format.

### Implementation for User Story 2

- [ ] T026 [US2] Add predict() method to ServingService — validates deployment is READY, forwards request to KServe endpoint using V2 inference protocol (`POST /v2/models/{name}/infer`), returns PredictionResponse in backend/src/main/java/com/mlplatform/service/ServingService.java
- [ ] T027 [US2] Add predict endpoint to ServingController: POST /serving/deployments/{id}/predict — proxies inference request through backend, handles V2 request/response mapping in backend/src/main/java/com/mlplatform/controller/ServingController.java
- [ ] T028 [P] [US2] Create predict-dialog.component — JSON input editor for V2 inference request, submit button, displays prediction response, shows errors for malformed input in frontend/src/app/features/models/predict-dialog/predict-dialog.component.ts|html|scss
- [ ] T029 [US2] Verify inference via portal: open predict dialog for a READY deployment, enter California Housing features as V2 input, submit, confirm numeric prediction response
- [ ] T030 [US2] Verify inference from pipeline DAG: create notebook that calls inference endpoint via in-cluster URL (`http://{name}.ml-platform-serving.svc.cluster.local/v2/models/{name}/infer`), trigger as pipeline job (feature 005), confirm output notebook shows predictions
- [ ] T031 [US2] Verify error handling: send malformed input (wrong shape/datatype), confirm 400 error with clear message

**Checkpoint**: User Story 2 complete — inference requests work from portal and pipeline DAGs

---

## Phase 5: User Story 3 — Authenticated Inference Access (Priority: P3)

**Goal**: Inference requests through the backend proxy require valid Keycloak JWT. Unauthenticated requests are rejected. Pipeline DAGs call in-cluster endpoints directly (no auth needed for service-to-service).

**Independent Test**: Send inference request without token → 401; send with valid token → prediction returned.

### Implementation for User Story 3

- [ ] T032 [US3] Verify backend proxy auth — send prediction request to POST /serving/deployments/{id}/predict without Authorization header, confirm 401 response
- [ ] T033 [US3] Verify pipeline service-to-service access — pipeline DAG notebook calls KServe ClusterIP URL directly within cluster, confirm no auth needed (KServe raw mode has no auth on ClusterIP)
- [ ] T034 [US3] Verify user isolation — scientist1 cannot access scientist2's deployments or send inference to scientist2's endpoints via the backend proxy

**Checkpoint**: User Story 3 complete — auth enforced on backend proxy, in-cluster access works for pipelines

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Error handling, edge cases, and full validation

- [ ] T035 Verify deployment failure handling — attempt to deploy an invalid model format, confirm FAILED status with clear error message
- [ ] T036 Verify deployment deletion — delete a READY deployment, confirm InferenceService removed from K8s, status set to DELETED, record preserved in DB
- [ ] T037 Verify model registry persistence — model registered in MLflow remains visible after MLflow pod restart
- [ ] T038 Add dev profile mock responses for KServeService and ModelRegistryService — return mock models/versions/deployments/predictions for backend development without KServe in backend/src/main/resources/application-dev.yaml
- [ ] T039 Run full quickstart.md validation (all 12 steps)
- [ ] T040 Verify inference response time < 2 seconds (SC-002) and endpoint availability over 1 hour (SC-005)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately (requires K8s cluster)
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational — model deployment lifecycle
- **User Story 2 (Phase 4)**: Depends on US1 (need a READY endpoint) — inference requests
- **User Story 3 (Phase 5)**: Depends on US2 (need working inference) — auth verification
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — core deployment
- **User Story 2 (P2)**: Depends on US1 (must have a deployed endpoint to call)
- **User Story 3 (P3)**: Depends on US2 (must have working inference to test auth)

### Within Each Phase

- Setup: T002-T004 parallel, T005 depends on all
- Foundational: T007-T010, T014-T015 parallel; T011 and T012 depend on T009; T013 independent
- US1: T016-T018 sequential (service methods build on each other); T021-T023 parallel (frontend); T019-T020 depend on T016-T018; T24 depends on T021-T023
- US2: T026-T027 sequential (backend); T028 parallel (frontend); T29-T31 depend on all
- US3: T032-T034 sequential verification steps

### Parallel Opportunities

- Setup: T002, T003, T004 in parallel
- Foundational: T007, T008, T009, T010, T014, T015 all in parallel
- US1: Frontend T021, T022, T023 in parallel with backend T016-T020
- US1 and US2 frontend work (T028) can start in parallel if API contract is known

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T005)
2. Complete Phase 2: Foundational (T006-T015)
3. Complete Phase 3: User Story 1 (T016-T025)
4. **STOP and VALIDATE**: Register model, deploy, see READY status
5. Model deployment works — feature delivers core value

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready
2. User Story 1 → Model deployment lifecycle (MVP!)
3. User Story 2 → Inference requests from portal + pipelines
4. User Story 3 → Authentication verification
5. Polish → Edge cases, dev profile, full validation

---

## Notes

- KServe is installed from upstream manifests (not Helm — no official Helm chart for raw mode)
- The Kubernetes Java Client is added as a dependency to interact with InferenceService CRDs
- MLflow Model Registry API is part of the existing MLflow server (no new deployment)
- Pipeline DAGs call inference endpoints directly via cluster DNS (no auth, service-to-service)
- Backend proxy adds Keycloak JWT auth for external inference calls
- No Bitnami charts used anywhere in this feature
- Total tasks: 40 (5 setup, 10 foundational, 10 US1, 6 US2, 3 US3, 6 polish)
