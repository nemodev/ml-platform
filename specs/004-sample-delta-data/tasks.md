# Tasks: Sample Delta Lake Data

**Input**: Design documents from `/specs/004-sample-delta-data/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Project structure and shared configuration for sample data provisioning

- [X] T001 Create directory structure for sample data K8s manifests at infrastructure/k8s/sample-data/
- [X] T002 [P] Add `deltalake>=0.22.0` to notebook image requirements in infrastructure/docker/notebook-image/requirements.txt
- [X] T003 [P] AWS_ALLOW_HTTP is a runtime env var injected via JupyterHub (T009), not a build-time Dockerfile change — no action needed

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: MinIO bucket, credentials, and notebook image updates that MUST be complete before user stories

**CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Update MinIO Helm local-values to add `ml-platform-sample-data` bucket in infrastructure/helm/minio/local-values.yaml
- [X] T005 Create MinIO read-only credentials Secret for sample data access in infrastructure/k8s/sample-data/read-only-secret.yaml
- [X] T006 Create the provisioning Python script as a ConfigMap in infrastructure/k8s/sample-data/provision-script-configmap.yaml
- [X] T007 Create the Kubernetes Job manifest in infrastructure/k8s/sample-data/provision-job.yaml
- [X] T008 [P] deltalake added to requirements.txt (T002); existing Dockerfile pip install picks it up
- [X] T009 Update JupyterHub local-values.yaml and values.yaml with S3 environment variables for notebook pods

**Checkpoint**: MinIO has new bucket, provisioning Job is deployable, notebook image includes deltalake, notebook pods have S3 credentials

---

## Phase 3: User Story 1 — Read Sample Delta Table from Notebook (Priority: P1)

**Goal**: A data user reads the pre-provisioned Delta table from object storage into a pandas DataFrame and inspects schema, row count, and sample rows.

**Independent Test**: Deploy MinIO + run provisioning Job, launch notebook, execute code that reads Delta table and displays first 5 rows with schema info.

### Implementation for User Story 1

- [ ] T010 [US1] Deploy updated MinIO with sample-data bucket — run `helm upgrade` with updated local-values from T004
- [ ] T011 [US1] Run the provisioning Job to create the Delta table — `kubectl apply -f infrastructure/k8s/sample-data/provision-job.yaml` and verify logs show success with 20,640 rows
- [ ] T012 [US1] Deploy updated JupyterHub with S3 environment variables — `helm upgrade` with updated local-values from T009
- [ ] T013 [US1] Verify Delta table is readable from a notebook cell — open notebook, run `from deltalake import DeltaTable; dt = DeltaTable("s3://ml-platform-sample-data/california-housing"); df = dt.to_pandas(); print(df.shape, df.dtypes)` — confirm (20640, 9) shape and float64 types
- [ ] T014 [US1] Verify schema inspection shows expected columns (MedInc, HouseAge, AveRooms, AveBedrms, Population, AveOccup, Latitude, Longitude, MedHouseVal) and non-zero row count from notebook cell
- [ ] T015 [US1] Verify read-only enforcement — attempt `write_deltalake("s3://ml-platform-sample-data/test-write", df.head())` from notebook and confirm it raises an access denied error

**Checkpoint**: User Story 1 complete — Delta table readable from notebooks with correct schema, read-only enforced

---

## Phase 4: User Story 2 — Train a Model on Sample Data (Priority: P2)

**Goal**: A user loads the sample Delta table and trains a scikit-learn model, evaluates performance, and optionally logs the run to MLflow.

**Independent Test**: Load sample data, split into train/test, fit RandomForestRegressor, print RMSE and R² score, log to MLflow experiment.

### Implementation for User Story 2

- [X] T016 [US2] Create sample notebook file as a ConfigMap in infrastructure/k8s/sample-data/sample-notebook-configmap.yaml
- [X] T017 [US2] Update JupyterHub values.yaml with extraVolumes and extraVolumeMounts to mount sample notebook at /home/jovyan/examples/sample-delta-data.ipynb
- [ ] T018 [US2] Deploy updated JupyterHub with sample notebook mount — `helm upgrade` with updated values
- [ ] T019 [US2] Verify training workflow from notebook — load Delta table, train/test split, fit model, confirm RMSE ~0.5 and R² ~0.8
- [ ] T020 [US2] Verify MLflow integration — run `mlflow.set_experiment("california-housing-demo")`, log params/metrics/model, confirm run appears in Experiments UI

**Checkpoint**: User Story 2 complete — full data → train → evaluate → log workflow works end-to-end

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Final verification and documentation

- [ ] T021 Verify sample notebook is visible at /home/jovyan/examples/ in a freshly launched notebook workspace
- [ ] T022 Verify all authenticated users can access the sample data (test with user1 and user2)
- [ ] T023 Run full quickstart.md validation (all 10 steps)
- [ ] T024 Verify data loads in under 10 seconds (SC-001) — time the DeltaTable read + to_pandas() call

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational phase — deploys and verifies Delta table access
- **User Story 2 (Phase 4)**: Depends on User Story 1 (data must be readable) — adds training workflow + sample notebook
- **Polish (Phase 5)**: Depends on both user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — core data access
- **User Story 2 (P2)**: Depends on US1 (needs readable Delta table) + feature 003 (MLflow for logging)

### Within Each Phase

- T004-T007 are sequential (bucket → credentials → script → Job)
- T008 and T009 can run in parallel with T004-T007
- T010-T012 are sequential deployment steps
- T013-T015 are sequential verification steps
- T016-T018 are sequential (create notebook → mount config → deploy)

### Parallel Opportunities

- T002 and T003 can run in parallel (different files)
- T008 can run in parallel with T004-T007 (Docker build vs K8s manifests)
- Phase 3 verification steps (T013-T015) run sequentially but each is quick

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T009)
3. Complete Phase 3: User Story 1 (T010-T015)
4. **STOP and VALIDATE**: Read Delta table from notebook, verify schema and row count
5. Data access works — feature delivers core value

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready
2. User Story 1 → Delta table readable from notebooks (MVP!)
3. User Story 2 → Training workflow + sample notebook + MLflow integration
4. Polish → Full validation and cross-user verification

---

## Notes

- This feature has no backend Java or frontend TypeScript code
- All tasks are infrastructure (K8s manifests, Helm values, Docker image)
- The provisioning Job is idempotent — safe to re-run
- MinIO from feature 003 is a prerequisite
- Total tasks: 24 (3 setup, 6 foundational, 6 US1, 5 US2, 4 polish)
