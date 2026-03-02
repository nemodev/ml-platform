# Feature 005: Airflow Notebook Pipeline

> Turns interactive notebooks into production pipelines using Airflow's KubernetesExecutor and Papermill, with optional Spark-on-K8s distributed processing.

## What & Why

Notebooks are great for experimentation, but they need to become reproducible, scheduled, and scalable for production. We integrate Apache Airflow to run notebooks as pipeline tasks via Papermill. The key insight is **environment parity**: the same Docker image that runs interactive notebooks also runs pipeline workers and Spark executors. A user triggers a pipeline from the portal, the backend copies the notebook to MinIO (immutable snapshot), triggers Airflow's `notebook_runner` DAG, and the user monitors progress through the portal UI. No Airflow UI is exposed to users — the backend proxies everything.

## Architecture

```
Portal (trigger button)
    ↓ POST /api/v1/pipelines
Backend (PipelineService)
    ├── Copy notebook from JupyterHub → MinIO (input snapshot)
    ├── Create PipelineRun entity (PENDING)
    └── Trigger Airflow DAG via REST API
        ↓
Airflow (KubernetesExecutor)
    ↓ notebook_runner DAG
    ├── prepare_command (PythonOperator) — build papermill command
    └── run_notebook (KubernetesPodOperator)
        ↓ ml-platform-notebook:latest
        papermill input.ipynb output.ipynb -p key value
        ↓ (optional: Spark executors spawned as K8s pods)
        ↓
MinIO: s3://ml-platform-pipelines/{username}/{runId}/output.ipynb
```

**Key decisions:**

- **KubernetesExecutor** — Each Airflow task runs as an isolated K8s pod. No Celery broker or Redis needed. Pods are created on demand and cleaned up after completion.
- **Single reusable DAG** — One `notebook_runner` DAG handles all pipeline runs. Configuration (notebook path, parameters, Spark flag) is passed via `dag_run.conf`. This avoids DAG sprawl and simplifies management.
- **Papermill for parameterized execution** — Papermill reads cells tagged with `parameters` and overrides them with provided values. Output notebooks retain all cell outputs for debugging and auditing.
- **Immutable notebook snapshots** — Before triggering Airflow, the backend copies the notebook from JupyterHub to MinIO. This ensures the pipeline runs against a fixed version, even if the user modifies the notebook afterward.
- **Spark client mode** — When `enable_spark=true`, the pipeline pod runs as the Spark driver (client mode) and spawns executor pods in the same namespace. No Spark Operator CRD needed — just RBAC for pod/service creation.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `controller/PipelineController.java` | Trigger, list, detail, output URL, notebook listing |
| Backend | `service/PipelineService.java` | Orchestrates trigger flow, status sync with Airflow |
| Backend | `service/AirflowService.java` | Airflow REST API client with basic auth |
| Backend | `service/NotebookStorageService.java` | MinIO copy, pre-signed URL generation |
| Backend | `model/PipelineRun.java` | JPA entity with status lifecycle |
| Frontend | `features/pipelines/pipelines.component.ts` | Run list with auto-refresh, status filtering |
| Frontend | `features/pipelines/trigger-dialog/` | Notebook selector, Spark toggle, parameter editor |
| Frontend | `features/pipelines/run-detail/` | Run metadata, output viewer, error display |
| Infra | `k8s/airflow/dag-configmap.yaml` | `notebook_runner` DAG (PythonOperator + KubernetesPodOperator) |
| Infra | `k8s/airflow/spark-rbac.yaml` | ServiceAccount with pod/service creation permissions |
| Infra | `helm/airflow/local-values.yaml` | KubernetesExecutor, MinIO remote logging |
| Infra | `helm/airflow/pod-template.yaml` | Task pod template with notebook image |

**Trigger flow:** `PipelineService.triggerPipeline()` validates the notebook path (.ipynb), syncs the user from JWT, fetches notebook bytes from JupyterHub's Content API, copies to MinIO (`{prefix}/{userId}/{runId}/input.ipynb`), creates a `PipelineRun` entity, calls `AirflowService.triggerDagRun()` with the conf payload, and updates the run with the Airflow DAG run ID. If any step fails, the run is marked FAILED with the error message.

**Status synchronization:** When the frontend requests run status, `PipelineService` polls Airflow's REST API for the latest DAG run state and maps it: `queued/scheduled/deferred` → PENDING, `running` → RUNNING, `success` → SUCCEEDED, anything else → FAILED.

**Spark environment setup:** The DAG's `prepare_command` task checks `enable_spark` from the config. If true, it prepends environment variable exports to the papermill command: `SPARK_HOME`, `SPARK_MASTER` (k8s://...), executor configuration (2 instances, 1 CPU, 2Gi each), and S3A filesystem settings. The pod template includes `airflow-spark-sa` service account with RBAC for creating executor pods and services.

**Output viewing:** After completion, `getOutputUrl()` generates a 15-minute pre-signed MinIO URL for the output notebook. The frontend opens it in a new tab via `window.open()`.

## Challenges & Solutions

- **Airflow DAG paused by default** — New DAGs are paused. `AirflowService.triggerDagRun()` first unpauses the DAG via PATCH before triggering the run.
- **MinIO path sanitization** — User IDs containing special characters are sanitized to alphanumeric + hyphens for S3 object paths.
- **Airflow auth translation** — The portal uses JWT; Airflow uses basic auth. The backend translates between them, so Airflow's admin credentials never reach the frontend.
- **Remote logging to MinIO** — Airflow logs are written to `s3://ml-platform-pipelines/ml-platform/airflow-logs` via the S3 logging connection, persisting beyond pod lifecycle.

## Limitations

- **No DAG scheduling** — Pipelines are triggered on-demand only. No cron-based scheduling is exposed through the portal.
- **Single DAG pattern** — All pipelines use `notebook_runner`. Complex multi-step DAGs (notebook A → notebook B) aren't supported.
- **No retry logic** — If Airflow trigger fails, the run is immediately marked FAILED. No automatic retries with backoff.
- **Hardcoded Spark defaults** — 2 executors, 1 CPU, 2Gi each. Users can't customize Spark configuration through the portal.
- **Hardcoded MinIO credentials in DAG ConfigMap** — `minioadmin:minioadmin` appears in the Spark S3A configuration. Should use K8s Secrets.
- **15-minute output URL expiry** — Pre-signed URLs for output notebooks expire. Users on slow connections may need to re-fetch.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| CeleryExecutor | Requires Redis broker. KubernetesExecutor is simpler and provides better pod isolation. |
| Per-notebook DAGs | DAG sprawl. Single template DAG with `dag_run.conf` is cleaner and scales. |
| nbconvert instead of Papermill | No parameterization support. Papermill's cell tagging is standard and well-documented. |
| Spark Operator (CRD) | Additional CRD installation and complexity. Client-mode Spark from the pipeline pod is sufficient for PoC. |
| Direct KubernetesPodOperator (no Papermill) | Loses notebook-specific features: cell-level output capture, parameter injection, reproducible execution. |
| Exposing Airflow UI to users | Airflow's UI is powerful but complex. A simplified portal view is better for the target audience. |

## Potential Improvements

- **DAG scheduling** — Allow users to set cron schedules for recurring pipeline runs.
- **Multi-step pipelines** — Support DAGs with multiple notebook steps and data dependencies.
- **Customizable Spark profiles** — Let users select Spark executor count and resources, similar to workspace profiles.
- **Pipeline templates** — Pre-built pipeline patterns (train → evaluate → deploy) that users can customize.
- **Live log streaming** — Stream Airflow task logs to the portal in real-time instead of waiting for completion.
- **Externalize MinIO credentials** — Move all credentials to K8s Secrets and reference them from the DAG ConfigMap.
