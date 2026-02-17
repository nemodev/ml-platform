# Feature Specification: Airflow Notebook Pipeline

**Feature Branch**: `005-airflow-notebook-pipeline`
**Created**: 2026-02-16
**Status**: Draft
**Input**: User description: "Airflow Notebook Pipeline"

**Depends on**: `001-keycloak-auth-portal` (portal shell),
`002-jupyterhub-notebook` (notebook environment),
`003-mlflow-experiment-tracking` (experiment logging from notebooks)

## Clarifications

### Session 2026-02-16

- Q: Where does the pipeline system read notebooks from — user's JupyterHub PVC, object storage, or a Git repo? → A: Notebooks are copied to object storage (MinIO) when the user triggers a pipeline; Airflow reads from MinIO
- Q: Which Airflow executor type should be used? → A: KubernetesExecutor (each task as a K8s pod, no broker needed). CeleryExecutor may be added later for other applications but is out of scope for MVP.
- Q: Should the Pipelines UI embed Airflow's native UI or be a custom frontend? → A: Custom pipeline UI in the portal frontend; backend proxies Airflow REST API for triggering and status
- Q: What default Spark executor configuration should be used? → A: 2 executors, 1 CPU / 2Gi RAM each (demonstrates distribution, fits small clusters)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Trigger Notebook as Pipeline Job (Priority: P1)

A data scientist has developed a notebook in the interactive
environment and wants to run it as a production pipeline job. From the
portal's "Pipelines" section, the user selects a notebook and triggers
it as a non-interactive job. The system accepts the request and begins
executing the notebook. The user can monitor the job status.

**Why this priority**: The ability to run notebooks as production jobs
is the core dev-to-prod bridge. Without it, notebooks remain
experimental artifacts only.

**Independent Test**: Can be verified by triggering a simple notebook
(e.g., prints "hello world") as a pipeline job and confirming it
completes successfully with the output preserved.

**Acceptance Scenarios**:

1. **Given** a notebook file accessible to the pipeline system, **When**
   the user triggers it as a pipeline job, **Then** the system accepts
   the job and begins execution.
2. **Given** a running pipeline job, **When** the user checks the status
   in the Pipelines section, **Then** they can see whether the job is
   queued, running, succeeded, or failed.
3. **Given** a completed pipeline job, **When** the user views the
   output, **Then** they can see the executed notebook with all cell
   outputs preserved.
4. **Given** a pipeline job that fails in a specific cell, **When** the
   user views the output, **Then** the failing cell and error message
   are clearly identified.

---

### User Story 2 - Pipeline Job with Spark (Priority: P2)

A data scientist triggers a notebook pipeline job that contains
distributed data processing code using Spark. The pipeline system
spawns Spark driver and executor pods on the cluster. The Spark job
processes data and writes results. The user can see that Spark workers
were used in the job output.

**Why this priority**: Spark support is what differentiates production
pipelines from just re-running notebooks. It enables processing
datasets that don't fit in memory on a single node.

**Independent Test**: Can be verified by triggering a notebook that
creates a Spark session, reads data, performs a transformation, and
writes output. The job log shows executor pods were created.

**Acceptance Scenarios**:

1. **Given** a notebook that initializes a Spark session, **When** it
   runs as a pipeline job, **Then** the Spark session starts
   successfully and executor pods are spawned on the cluster.
2. **Given** a running Spark job within a pipeline, **When** the job
   processes a dataset, **Then** the processing is distributed across
   multiple executor pods.
3. **Given** a Spark pipeline job that completes, **When** the user
   views the output notebook, **Then** the Spark operation results are
   captured in the cell outputs.
4. **Given** a Spark pipeline job, **When** it finishes (success or
   failure), **Then** the executor pods are cleaned up and cluster
   resources are released.

---

### User Story 3 - Pipeline with Experiment Tracking (Priority: P3)

A notebook pipeline job includes experiment tracking calls. When the
pipeline runs non-interactively, the training run is still logged to
the experiment tracking server. The user can see the pipeline-generated
run in the experiment tracking UI alongside their interactive runs.

**Why this priority**: Experiment tracking in production pipelines
ensures that production model training is auditable and comparable to
development runs.

**Independent Test**: Can be verified by triggering a notebook that
trains a model with experiment tracking, then confirming the run
appears in the experiment tracking UI.

**Acceptance Scenarios**:

1. **Given** a notebook pipeline job that logs a training run to the
   experiment tracker, **When** the job completes, **Then** the run is
   visible in the experiment tracking UI.
2. **Given** a pipeline-generated run, **When** the user views it in
   the experiment tracking UI, **Then** it is indistinguishable from an
   interactive run (same parameters, metrics, artifacts format).

---

### Edge Cases

- What happens when a pipeline job exceeds available cluster resources?
  The job enters a "pending" state until resources are available; the
  user sees "waiting for resources" in the job status.
- What happens when the pipeline system itself is unavailable? The
  backend returns an error when the user tries to trigger a job; the
  user sees "pipeline service unavailable."
- What happens when a Spark job requests more executors than the
  cluster can provide? Spark starts with available executors and scales
  up as resources become available (dynamic allocation).
- What happens when the notebook file is missing or corrupt at
  execution time? The pipeline job fails immediately with a clear
  error indicating the notebook could not be read.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide a pipeline orchestration service
  that can execute notebooks non-interactively.
- **FR-002**: The system MUST use a notebook parameterization tool to
  inject parameters and execute notebooks as batch jobs.
- **FR-003**: When triggering a pipeline, the backend MUST copy the
  notebook from the user's JupyterHub workspace to object storage
  (MinIO) so Airflow can access an immutable snapshot.
- **FR-003a**: The pipeline system MUST save the executed output notebook
  (with all cell outputs) to object storage (MinIO) for retrieval.
- **FR-004**: Pipeline jobs MUST support initializing Spark sessions
  that spawn driver and executor pods on the cluster.
- **FR-005**: The Docker image used for pipeline workers MUST be
  identical to the notebook server image (environment parity).
- **FR-006**: The portal's Pipelines section MUST provide a custom UI
  (not an embedded Airflow iframe) that allows users to trigger a
  notebook as a pipeline job, view job status, and retrieve output
  notebooks. The backend proxies Airflow's REST API to provide user
  isolation and a simplified interface.
- **FR-007**: The backend MUST proxy Airflow's REST API for triggering
  DAG runs, polling job status, and retrieving output notebook
  locations. Airflow itself is not exposed to users directly.
- **FR-008**: Pipeline jobs MUST have access to the experiment tracking
  server for logging runs.
- **FR-009**: Spark executor pods MUST be cleaned up after the pipeline
  job completes (success or failure).
- **FR-010**: Pipeline jobs MUST have read access to object storage for
  data access (same as notebook servers).

### Key Entities

- **Pipeline Job**: An execution of a notebook in non-interactive mode.
  Has a status (queued, running, succeeded, failed), input notebook
  path, output notebook path, and optional parameters.
- **DAG**: A directed acyclic graph defining the workflow steps. For
  MVP, a single-step DAG that runs one notebook via Papermill.
- **Spark Session**: A distributed compute session created within a
  pipeline job. Spawns executor pods on the cluster.
- **Output Notebook**: The executed version of the input notebook with
  all cell outputs preserved. Stored in object storage (MinIO).

### Assumptions

- The pipeline orchestration system (Airflow) runs on the same
  Kubernetes cluster using the KubernetesExecutor. Each Papermill task
  runs as a separate K8s pod using the notebook image. No Redis or
  message broker is required. CeleryExecutor may be added in the future
  for other workloads but is out of scope for MVP.
- For MVP, pipeline triggering is done via the portal UI; scheduled
  pipelines (cron) are out of scope.
- The notebook image with Spark client libraries is the same image used
  for interactive notebooks (per constitution: environment parity).
- Spark-on-Kubernetes is the execution mode; no standalone Spark
  cluster is needed. Default configuration: 2 executors with 1 CPU and
  2Gi RAM each. Users do not configure Spark resources in MVP — the
  defaults are fixed in the DAG definition.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can trigger a notebook pipeline job and see it
  complete within 5 minutes for a simple notebook (no Spark).
- **SC-002**: A user can trigger a Spark-enabled notebook pipeline and
  confirm that executor pods were spawned and the distributed
  processing completed.
- **SC-003**: The output notebook from a pipeline job preserves all
  cell outputs and is viewable by the user.
- **SC-004**: A pipeline job that includes experiment tracking
  successfully logs the run, visible in the experiment tracking UI.
- **SC-005**: Spark executor pods are cleaned up within 2 minutes of
  pipeline job completion.
