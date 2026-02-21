# Research: MLflow Experiment Tracking

**Feature**: `003-mlflow-experiment-tracking`
**Date**: 2026-02-16

## Decisions

### D1: MLflow Tracking Server Deployment

**Decision**: Deploy MLflow tracking server using the official Docker
image `ghcr.io/mlflow/mlflow:v3.10.0` with a custom Helm chart. The
server runs with `--serve-artifacts` flag to proxy artifact access
through itself.

**Rationale**: The official MLflow image is lightweight and
well-maintained. A custom Helm chart (not a community chart) gives
full control over configuration. The `--serve-artifacts` flag means
clients don't need direct MinIO credentials — the server proxies
artifact uploads/downloads. The prior project used the same approach.

**Alternatives considered**:
- Community MLflow Helm chart: Less control, often outdated.
- MLflow managed service (Databricks): Not self-hosted; violates K8s
  constraint.
- Direct artifact access from clients: Requires distributing MinIO
  credentials to every notebook; security risk.

### D2: MinIO for Artifact Storage

**Decision**: Deploy MinIO on K8s for S3-compatible artifact storage.
Create an `ml-platform-mlflow` bucket for MLflow artifacts. Use the
official MinIO Helm chart (`charts.min.io`) in standalone mode
(single-node for MVP).

**Rationale**: MinIO provides S3-compatible APIs without cloud
dependency (per spec clarification). A single-node deployment is
sufficient for MVP. The prior project used MinIO Operator with a
4-server tenant, but that's over-engineered for MVP (Principle VI).

**Alternatives considered**:
- MinIO Operator (multi-server): Over-engineered for MVP.
- AWS S3: Requires cloud credentials; not local-first.
- Local filesystem PVC: Not S3-compatible; breaks artifact serving.

### D3: Shared PostgreSQL for MLflow Metadata

**Decision**: Use the existing PostgreSQL instance (deployed by feature
001) with a new `mlflow` database. Connection string:
`postgresql://postgres:{password}@postgresql.ml-platform.svc:5432/mlflow`

**Rationale**: Reuses existing infrastructure; no additional database
deployment needed. MLflow's metadata is lightweight. The prior project
shared PostgreSQL across all components. Requires `psycopg2-binary`
installed in the MLflow container.

**Alternatives considered**:
- SQLite: Not suitable for production; file locking issues.
- Separate PostgreSQL instance: Wasteful for MVP.

### D4: User-Prefixed Experiment Naming for Isolation

**Decision**: The backend proxy automatically prepends the
authenticated user's username to experiment names when creating
experiments. Example: user `scientist1` creates experiment
`my-training` → stored as `scientist1/my-training`. When listing
experiments, the backend filters by the current user's prefix and
strips the prefix before returning results.

**Rationale**: Per spec clarification. This is the simplest isolation
mechanism that doesn't require modifying MLflow internals. The mlflow
client in notebooks connects via the backend proxy, which handles
the prefixing transparently. Users never see the prefix.

**Alternatives considered**:
- MLflow tags for ownership: Tags aren't enforced; any user can query
  any experiment by ID.
- Separate MLflow instances per user: Wasteful, complex.
- No isolation: Spec requires per-user isolation (FR-007).

### D5: Backend Proxy for MLflow API

**Decision**: The Spring Boot backend exposes experiment-related
endpoints that proxy to MLflow's REST API (v2.0). The backend handles
user authentication (JWT), experiment name prefixing, and response
filtering. The frontend and notebooks never communicate directly with
MLflow.

**Rationale**: Consistent with the backend proxy pattern established
in feature 002. Centralizes auth, isolation, and auditing. The prior
project used an MlflowService that proxied all MLflow API calls.

**Key proxy endpoints**:
- `POST /api/v1/experiments` → MLflow `experiments/create` (with
  user prefix)
- `GET /api/v1/experiments` → MLflow `experiments/search` (filtered
  by user prefix)
- `GET /api/v1/experiments/{id}` → MLflow `experiments/get`
- `GET /api/v1/experiments/{id}/runs` → MLflow `runs/search`

### D6: Notebook MLflow Configuration via Environment Variable

**Decision**: Inject the `MLFLOW_TRACKING_URI` environment variable
into notebook server containers via JupyterHub's KubeSpawner
configuration. The URI points to the Spring Boot backend's experiment
proxy endpoint (`http://backend.ml-platform.svc:8080/api/v1/mlflow-proxy`),
not directly to MLflow.

**Rationale**: Users should not need to configure the tracking URI
manually (FR-002). Environment variable injection via KubeSpawner
`environment` config is the standard JupyterHub approach. Routing
through the backend proxy enables user identification and experiment
prefixing.

**Alternatives considered**:
- Direct MLflow URL in notebooks: Bypasses backend proxy; no user
  isolation, no auth.
- Jupyter server extension for configuration: Over-engineering.
- Manual `mlflow.set_tracking_uri()` in each notebook: Bad UX,
  violates FR-002.

### D7: MLflow UI Embedding via Iframe

**Decision**: Embed the MLflow UI in the portal's "Experiments"
section using an iframe. The iframe URL points to the MLflow tracking
server via port-forward (local) or in-cluster proxy (production).
MLflow has no auth layer — network isolation (ClusterIP) provides
security. Configure MLflow to set CSP `frame-ancestors` to allow the
portal domain.

**Rationale**: Per spec clarification, network-level isolation is
chosen over auth proxy. MLflow's UI is a React SPA that serves well
in an iframe. The CSP header is set via MLflow's
`--app-name "mlflow-tracking"` and environment variable
`MLFLOW_ALLOW_IFRAME_EMBEDDING=true` (or Nginx config).

**Alternatives considered**:
- OAuth2 Proxy in front of MLflow: User chose network isolation
  instead for simplicity.
- Backend reverse-proxies MLflow UI: Complex websocket/asset proxying.
- Custom experiment UI in Angular: Massive duplication of MLflow's
  functionality; violates Principle VI.

### D8: MLflow Service Configuration

**Decision**: MLflow tracking server runs with these flags:
```
mlflow server \
  --host 0.0.0.0 \
  --port 5000 \
  --backend-store-uri postgresql://... \
  --default-artifact-root s3://ml-platform-mlflow/artifacts \
  --serve-artifacts
```

The server uses `gunicorn` with 2 workers (sufficient for MVP).
Health check via `GET /health`. Readiness probe on `/health`.

**Rationale**: Standard MLflow server configuration. `--serve-artifacts`
is critical — it makes the server proxy artifact uploads/downloads,
so notebook clients only need the tracking URI (no MinIO credentials).

### D9: MinIO Bucket and Credential Management

**Decision**: Create a single bucket `ml-platform-mlflow` with a
service account for MLflow. Credentials stored as Kubernetes Secret
and injected into the MLflow pod via environment variables
(`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`MLFLOW_S3_ENDPOINT_URL`).

**Rationale**: Standard MinIO credential management for K8s
workloads. The secret is referenced by the MLflow deployment, not
by notebooks (since artifact access is proxied through MLflow).
