# Research: Airflow Notebook Pipeline

**Feature**: `005-airflow-notebook-pipeline` | **Date**: 2026-02-16

## Summary

Feature 005 adds Airflow-based notebook pipeline execution with
Papermill and Spark-on-Kubernetes support. Users trigger notebooks
as production jobs from the portal. The backend copies notebooks to
MinIO, generates DAGs dynamically, and proxies the Airflow REST API
for job status. Spark executors run as K8s pods using the same
notebook Docker image (environment parity).

## Decisions

### D1: Airflow Helm Chart — Apache Official Chart

**Decision**: Use the official Apache Airflow Helm chart from
`https://airflow.apache.org`.

**Rationale**: The official chart is actively maintained by the Apache
community, supports KubernetesExecutor natively, and provides built-in
support for git-sync, remote logging, and pod templates. The prior
project used this chart successfully. No Bitnami chart is used.

**Alternatives considered**:
- Bitnami Airflow chart: **Rejected** — not free to use.
- Custom Helm chart: Unnecessary complexity when the official chart
  covers all requirements.

**Version**: Airflow 2.10.x (pinned in constitution), chart version
  latest compatible.

### D2: Executor — KubernetesExecutor

**Decision**: Use KubernetesExecutor. Each Airflow task runs as an
isolated K8s pod using the notebook Docker image.

**Rationale**: KubernetesExecutor requires no message broker (Redis/
RabbitMQ), naturally supports environment parity (each pod uses the
notebook image), and aligns with the platform's K8s-native architecture.
CeleryExecutor may be added in the future for other workloads but is
out of scope for MVP.

**Alternatives considered**:
- CeleryExecutor: Requires Redis/RabbitMQ broker. Overkill for
  notebook pipeline jobs. Deferred to future.
- LocalExecutor: No pod isolation, tasks run in scheduler process.
  Cannot use the notebook image or spawn Spark sessions.

### D3: Notebook Execution — Papermill via KubernetesPodOperator

**Decision**: Use `papermill` (v2.6.0) for notebook execution. The
Airflow DAG uses KubernetesPodOperator to launch a pod that runs
`papermill` with the notebook image.

**Rationale**: Papermill is the standard tool for parameterized notebook
execution. KubernetesPodOperator (from `apache-airflow-providers-cncf-kubernetes`)
gives full control over the pod spec — image, resources, environment
variables, volumes. The prior project used this exact pattern.

**Command pattern**:
```
papermill s3://ml-platform-pipelines/{user}/{run_id}/input.ipynb \
         s3://ml-platform-pipelines/{user}/{run_id}/output.ipynb \
         -p param1 value1
```

**Alternatives considered**:
- PapermillOperator (airflow-provider-papermill): Runs Papermill in the
  scheduler/worker process, not in an isolated pod. Cannot use the
  notebook image.
- nbconvert: No parameterization support.

### D4: DAG Management — Dynamic Generation from Backend

**Decision**: The backend dynamically generates DAG Python files and
deploys them via a shared volume (PVC) mounted into the Airflow
scheduler and webserver pods. No git-sync is used.

**Rationale**: For MVP, pipelines are triggered on-demand from the
portal. Each pipeline trigger creates a DAG run for a pre-deployed
"notebook-runner" DAG template. The template DAG accepts notebook path
and parameters via `dag_run.conf`. This avoids generating per-notebook
DAGs and keeps the DAG count manageable.

**DAG template approach**: A single DAG template `notebook_runner`
is deployed as a ConfigMap mounted into the Airflow scheduler. When
triggered via the REST API, `dag_run.conf` provides:
- `notebook_path`: S3 path to the input notebook
- `output_path`: S3 path for the executed output notebook
- `parameters`: Dict of Papermill parameters
- `enable_spark`: Boolean to configure Spark environment

**Alternatives considered**:
- Per-notebook DAG generation: Creates DAG sprawl. Hard to manage
  lifecycle. Unnecessary for MVP.
- Git-sync: Requires a Git repository for DAGs. Adds complexity
  for on-demand triggering.

### D5: Notebook Flow — Copy to MinIO Before Execution

**Decision**: When a user triggers a pipeline, the backend:
1. Reads the notebook from the user's JupyterHub workspace via the
   JupyterHub Content API (`GET /user/{username}/api/contents/{path}`)
2. Copies it to `s3://ml-platform-pipelines/{user}/{run_id}/input.ipynb`
3. Triggers the Airflow DAG with the S3 path
4. Airflow pod reads from MinIO, executes with Papermill, writes
   output to `s3://ml-platform-pipelines/{user}/{run_id}/output.ipynb`

**Rationale**: Copying to MinIO decouples pipeline execution from the
user's live workspace, creates an immutable snapshot of the notebook
version, and allows Airflow pods to access the notebook without
mounting user PVCs.

**MinIO bucket**: `ml-platform-pipelines` (new bucket for pipeline
input/output notebooks and Airflow logs).

### D6: MinIO — Official MinIO Helm Chart (Not Bitnami)

**Decision**: Use the official MinIO Helm chart from
`https://charts.min.io/` for deploying MinIO.

**Rationale**: Bitnami charts are not free to use. The official MinIO
chart supports single-node and distributed modes, bucket creation via
post-install Job, and is maintained by the MinIO team.

**Note**: This decision applies to all features using MinIO (003, 004,
005). Features 003 and 004 reference the Bitnami chart and will need
to be updated during implementation to use the official chart instead.

**Alternatives considered**:
- Bitnami MinIO chart: **Rejected** — not free to use.
- MinIO Operator: More complex (CRD-based). Overkill for single-node
  MVP. The prior project used the Operator for a 4-server cluster.
- Custom manifests: Unnecessary when official chart exists.

### D7: Spark-on-Kubernetes — Client Mode in Pipeline Pods

**Decision**: Pipeline pods that need Spark initialize a SparkSession
in `client` mode with `spark.master=k8s://https://kubernetes.default.svc`.
The Spark driver runs inside the Papermill pod; executors are spawned
as separate K8s pods.

**Rationale**: Spark client mode is the simplest way to run Spark from
within a K8s pod. No Spark Operator or SparkApplication CRD needed.
The notebook image already includes PySpark 4.0.x and the necessary
Hadoop S3A JARs.

**Default configuration** (from clarification):
- 2 executors, 1 CPU / 2Gi RAM each
- Image: Same notebook image (environment parity)
- Spark properties set via `spark-defaults.conf` in the Docker image

**Service account**: The Airflow pod (and thus the Spark driver) needs
a K8s ServiceAccount with permissions to create/delete executor pods.

### D8: Airflow REST API — Backend Proxy Pattern

**Decision**: The Spring Boot backend proxies Airflow's REST API.
Endpoints:
- Trigger DAG run: `POST /api/v1/dags/{dagId}/dagRuns`
- Get DAG run status: `GET /api/v1/dags/{dagId}/dagRuns/{runId}`
- List DAG runs: `GET /api/v1/dags/{dagId}/dagRuns`

**Rationale**: Same proxy pattern used for MLflow in feature 003 and
JupyterHub in feature 002. The backend adds user isolation (filtering
runs by the triggering user), authentication (Keycloak JWT → Airflow
basic auth), and a simplified API surface for the frontend.

**Airflow authentication**: Airflow REST API uses basic auth. The
backend stores Airflow credentials in application config (not exposed
to users). Users authenticate via Keycloak JWT to the backend.

### D9: Pipeline Frontend — Custom Angular Components

**Decision**: Build custom Angular components for the Pipelines section:
- Pipeline list: Shows user's pipeline runs with status
- Trigger dialog: Select notebook from workspace, optional parameters
- Run detail: Shows status, logs, link to output notebook

**Rationale**: Airflow's native UI exposes internal DAG details, admin
controls, and all users' DAGs. A custom UI provides user isolation,
simplified status display, and consistent portal look-and-feel.

**Prior project pattern**: The data-intelligence project built similar
custom components in `frontend/src/app/features/pipelines/`.

### D10: Airflow Database — Shared PostgreSQL

**Decision**: Create an `airflow` database in the shared PostgreSQL
instance for Airflow metadata.

**Rationale**: Same pattern as MLflow in feature 003 (separate DB in
shared PostgreSQL). Avoids deploying a dedicated PostgreSQL for Airflow.

### D11: Airflow Remote Logging — S3 to MinIO

**Decision**: Configure Airflow remote logging to write task logs to
`s3://ml-platform-pipelines/airflow-logs/`.

**Rationale**: Remote logging to MinIO persists logs beyond pod
lifecycle and makes them retrievable via the backend API. The prior
project used this exact pattern.

**Configuration**:
```
AIRFLOW__LOGGING__REMOTE_LOGGING=true
AIRFLOW__LOGGING__REMOTE_BASE_LOG_FOLDER=s3://ml-platform-pipelines/airflow-logs
AIRFLOW__LOGGING__REMOTE_LOG_CONN_ID=minio_default
```

### D12: Spark ServiceAccount and RBAC

**Decision**: Create a dedicated Kubernetes ServiceAccount
`airflow-spark-sa` with RBAC permissions to create, get, list, and
delete pods in the `ml-platform` namespace. This ServiceAccount is
used by Airflow task pods that run Spark.

**Rationale**: Spark client mode needs to create executor pods. The
default ServiceAccount doesn't have these permissions. A dedicated
SA with minimal RBAC follows the principle of least privilege.
