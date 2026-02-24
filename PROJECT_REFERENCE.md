# ML Platform — Comprehensive Project Reference

> **Purpose**: Single source of truth for this project. Another Claude session reading only this file should understand the entire codebase, architecture, and current state.
>
> **Last updated**: 2026-02-21 | **All 7 features implemented and verified**

---

## 1. What This Project Is

A Kubernetes-native machine learning platform that unifies:

1. **Develop** — Interactive JupyterHub notebooks with pre-loaded sample data (Delta Lake on MinIO)
2. **Track** — MLflow experiment tracking with automatic user/analysis isolation
3. **Productionize** — Airflow notebook pipelines (Papermill + optional Spark)
4. **Deploy** — KServe model serving with V2 inference protocol
5. **Organize** — Analysis entity groups notebooks + experiments per user

All components share Keycloak SSO. The Spring Boot backend orchestrates everything. The Angular frontend embeds JupyterLab and MLflow UI in iframes.

---

## 2. Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| **Backend** | Spring Boot (Java 21) | 3.5.x |
| **Frontend** | Angular (TypeScript 5.4+) | 17.3 |
| **Identity** | Keycloak | 26.1 |
| **Notebooks** | JupyterHub (Z2JH Helm) | 4.3.2 (JupyterHub 5.x) |
| **Experiments** | MLflow | 3.10.0 |
| **Pipelines** | Apache Airflow | 2.10.3 |
| **Processing** | Apache Spark | 4.0.1 |
| **Data Format** | Delta Lake | >= 0.22.0 |
| **Storage** | MinIO (S3-compatible) | latest (official chart) |
| **Database** | PostgreSQL | latest (shared instance) |
| **Model Serving** | KServe (Standard mode) | 0.16.x |
| **Build** | Gradle (Kotlin DSL) | - |
| **Migrations** | Flyway | - |

---

## 3. Feature Status

All 7 features are **implemented** and verified end-to-end on the r1 cluster.

| # | Feature | Key Entities | Key Backend | Key Frontend |
|---|---------|-------------|-------------|-------------|
| 001 | Keycloak Auth & Portal | User | AuthController, PortalController, UserService, SecurityConfig | auth.service, oidc-auth.module, auth.guard, dashboard |
| 002 | JupyterHub Notebooks | Workspace | WorkspaceController, WorkspaceService, JupyterHubService | notebooks.component, workspace.service |
| 003 | MLflow Experiments | (MLflow-managed) | ExperimentController, MlflowService, ModelRegistryService | experiments.component, experiment.service |
| 004 | Sample Delta Data | — | — | — |
| 005 | Airflow Pipelines | PipelineRun | PipelineController, PipelineService, AirflowService, NotebookStorageService | pipelines, trigger-dialog, run-detail |
| 006 | Model Serving | ModelDeployment | ModelController, ServingController, ServingService, KServeService | models, deploy-dialog, deployments, predict-dialog |
| 007 | Notebook UI Customization | Analysis | AnalysisController, AnalysisService | analyses, analysis-layout, jupyter-bridge.service |

### Feature Dependency Chain

```
001-Keycloak Auth & Portal
    └──► 002-JupyterHub Notebooks
              ├──► 003-MLflow Experiment Tracking
              │         └──► 005-Airflow Notebook Pipeline
              │                    └──► 006-Model Serving & Inference
              ├──► 004-Sample Delta Lake Data
              └──► 007-Notebook UI Customization (Analysis entity)
```

---

## 4. Architecture

### Component Diagram

```
                        ┌──────────────────────────────────────────┐
                        │              Angular SPA                  │
                        │   (OIDC PKCE → Keycloak, JWT Bearer)     │
                        └────────────────┬─────────────────────────┘
                                         │ REST API + JWT
                        ┌────────────────▼─────────────────────────┐
                        │          Spring Boot Backend              │
                        │  ┌──────┬──────┬──────┬──────┬────────┐  │
                        │  │Auth  │Anlys │Exper-│Pipe- │Model/  │  │
                        │  │Ctrl  │+Wksp │iment │line  │Serving │  │
                        │  │      │Ctrl  │Ctrl  │Ctrl  │Ctrl    │  │
                        │  └──┬───┴──┬───┴──┬───┴──┬───┴───┬────┘  │
                        │  ┌──▼──┐┌──▼──┐┌──▼──┐┌──▼──┐┌───▼───┐  │
                        │  │User ││Anlys││Mlfl ││Pipe ││Servng │  │
                        │  │Svc  ││+Wksp││Svc  ││Svc  ││Svc    │  │
                        │  └──┬──┘└──┬──┘└──┬──┘└──┬──┘└───┬───┘  │
                        └─────┼──────┼──────┼──────┼───────┼───────┘
                              │      │      │      │       │
        ┌─────────┐  ┌───────▼──┐ ┌─▼───┐ ┌▼─────▼──┐ ┌──▼────┐
        │Keycloak │  │Jupyter-  │ │MLfl-│ │Airflow + │ │KServe │
        │(OIDC)   │  │Hub (Z2JH)│ │ow   │ │Papermill │ │(Raw)  │
        └────┬────┘  └────┬─────┘ └──┬──┘ └────┬─────┘ └───┬───┘
             │             │          │         │           │
        ┌────▼─────────────▼──────────▼─────────▼───────────▼──┐
        │                    PostgreSQL                         │
        │  ┌────────┐ ┌──────────┐ ┌──────┐ ┌───────┐ ┌─────┐ │
        │  │keycloak│ │ml_platfrm│ │mlflow│ │airflow│ │jhub │ │
        │  └────────┘ └──────────┘ └──────┘ └───────┘ └─────┘ │
        └──────────────────────────────────────────────────────┘
        ┌──────────────────────────────────────────────────────┐
        │                      MinIO (S3)                       │
        │  ┌────────────────┐ ┌─────────────┐ ┌─────────────┐ │
        │  │ml-platform-    │ │ml-platform- │ │ml-platform- │ │
        │  │mlflow          │ │sample-data  │ │pipelines    │ │
        │  └────────────────┘ └─────────────┘ └─────────────┘ │
        └──────────────────────────────────────────────────────┘
```

### Service Connectivity (In-Cluster)

| From | To | Address |
|------|----|---------|
| Backend | Keycloak | `keycloak.ml-platform.svc:8080` |
| Backend | JupyterHub | `hub.ml-platform.svc:8081` |
| Backend | JupyterHub Proxy | `proxy-public.ml-platform.svc:80` |
| Backend | MLflow | `mlflow.ml-platform.svc:5000` |
| Backend | Airflow | `airflow-webserver.ml-platform.svc:8080` |
| Backend | MinIO | `minio.ml-platform.svc:9000` |
| Backend | K8s API | `kubernetes.default.svc` |
| Notebook pods | MLflow | `http://mlflow.ml-platform.svc:5000` (via `MLFLOW_TRACKING_URI`) |
| Notebook pods | MinIO | `http://minio.ml-platform.svc:9000` (via `AWS_ENDPOINT_URL`) |
| KServe pods | MinIO | `http://minio.ml-platform.svc:9000` (via S3 secret) |

### Namespaces

| Namespace | Purpose |
|-----------|---------|
| `ml-platform` | All platform components (backend, frontend, JupyterHub, MLflow, Airflow, MinIO, PostgreSQL, Keycloak) |
| `ml-platform-serving` | KServe InferenceService pods (predictors) |

---

## 5. Data Model

### PostgreSQL Databases (5)

| Database | Owner | Purpose |
|----------|-------|---------|
| `keycloak` | Keycloak | OIDC realm, users, clients, sessions |
| `ml_platform` | Spring Boot | Application entities (users, analyses, workspaces, pipeline_runs, model_deployments) |
| `mlflow` | MLflow | Experiment metadata, run metrics, model registry |
| `airflow` | Airflow | DAG definitions, task instances, XCom |
| `jupyterhub` | JupyterHub | Hub state, user servers, API tokens |

### Application Tables (Flyway-managed, 8 migrations)

```sql
-- V1: users
users (
    id              UUID PRIMARY KEY,
    oidc_subject    VARCHAR(255) NOT NULL UNIQUE,
    username        VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    email           VARCHAR(255),
    created_at      TIMESTAMP NOT NULL,
    last_login      TIMESTAMP NOT NULL
)

-- V8: analyses
analyses (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL → users(id),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP NOT NULL
)
-- Constraints: UNIQUE(user_id, name)

-- V2 + V8: workspaces
workspaces (
    id                    UUID PRIMARY KEY,
    user_id               UUID NOT NULL → users(id),
    analysis_id           UUID → analyses(id),
    profile               VARCHAR(50) DEFAULT 'EXPLORATORY',
    status                VARCHAR(20) DEFAULT 'PENDING',
    pod_name              VARCHAR(255),
    jupyterhub_username   VARCHAR(255) NOT NULL,
    started_at            TIMESTAMP,
    last_activity         TIMESTAMP,
    created_at            TIMESTAMP NOT NULL
)
-- Status values: PENDING | RUNNING | IDLE | STOPPED | FAILED
-- Constraint: one active workspace (PENDING|RUNNING|IDLE) per analysis

-- V5: pipeline_runs
pipeline_runs (
    id                 UUID PRIMARY KEY,
    user_id            UUID NOT NULL → users(id),
    notebook_name      VARCHAR(255) NOT NULL,
    input_path         VARCHAR(512) NOT NULL,
    output_path        VARCHAR(512),
    status             VARCHAR(20) DEFAULT 'PENDING',
    airflow_dag_run_id VARCHAR(255),
    parameters         JSONB,
    enable_spark       BOOLEAN DEFAULT FALSE,
    started_at         TIMESTAMP,
    completed_at       TIMESTAMP,
    error_message      TEXT,
    created_at         TIMESTAMP NOT NULL
)
-- Status values: PENDING | RUNNING | SUCCEEDED | FAILED

-- V6 + V7: model_deployments
model_deployments (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL → users(id),
    model_name      VARCHAR(255) NOT NULL,
    model_version   INTEGER NOT NULL,
    endpoint_name   VARCHAR(255) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    inference_url   VARCHAR(512),
    storage_uri     VARCHAR(512) NOT NULL,
    error_message   TEXT,
    created_at      TIMESTAMP NOT NULL,
    ready_at        TIMESTAMP,
    deleted_at      TIMESTAMP
)
-- Status values: DEPLOYING | READY | FAILED | DELETING | DELETED
-- Constraint: UNIQUE(endpoint_name) WHERE deleted_at IS NULL
```

### Flyway Migrations

| Migration | Description |
|-----------|------------|
| `V1__create_users.sql` | Users table |
| `V2__create_workspaces.sql` | Workspaces table |
| `V005__create_pipeline_runs.sql` | Pipeline runs table |
| `V006__create_model_deployments.sql` | Model deployments table |
| `V007__adjust_model_deployment_endpoint_uniqueness.sql` | Partial unique index (active only) |
| `V008__create_analyses_and_link_workspaces.sql` | Analyses table + workspace FK |

### MinIO Buckets

| Bucket | Purpose |
|--------|---------|
| `ml-platform-mlflow` | MLflow artifacts (models, metrics, plots) |
| `ml-platform-sample-data` | Delta Lake table (California Housing, 20,640 rows) |
| `ml-platform-pipelines` | Pipeline notebooks (input/output per user per run) |

### Sample Delta Table

Location: `s3://ml-platform-sample-data/california-housing/`

| Column | Type | Description |
|--------|------|-------------|
| MedInc | float64 | Median income |
| HouseAge | float64 | Median house age |
| AveRooms | float64 | Average rooms |
| AveBedrms | float64 | Average bedrooms |
| Population | float64 | Block group population |
| AveOccup | float64 | Average occupancy |
| Latitude | float64 | Latitude |
| Longitude | float64 | Longitude |
| MedHouseVal | float64 | Median house value (target) |

Source: scikit-learn `fetch_california_housing()`, 20,640 rows.

---

## 6. API Reference

All endpoints use prefix `/api/v1/`. All except `/health` require JWT Bearer authentication.

### Authentication & Portal

```
GET    /v1/health                 → {status, timestamp}
GET    /v1/auth/userinfo          → {username, email, displayName}
POST   /v1/auth/logout            → 200
GET    /v1/portal/sections        → [{title, route, icon}]
```

### Analyses

```
POST   /v1/analyses               → {id, name, description, createdAt}
         Body: {name, description}
GET    /v1/analyses               → [{id, name, description, createdAt}]
GET    /v1/analyses/{id}          → {id, name, description, createdAt}
DELETE /v1/analyses/{id}          → 204
```

### Workspaces (analysis-scoped)

```
GET    /v1/analyses/{aId}/workspaces/profiles      → [{slug, displayName, cpu, memory}]
POST   /v1/analyses/{aId}/workspaces               → {id, status, message}  Body: {profile}
GET    /v1/analyses/{aId}/workspaces               → {id, status, message}
GET    /v1/analyses/{aId}/workspaces/url            → {url}
GET    /v1/analyses/{aId}/workspaces/kernel-status  → {status}
DELETE /v1/analyses/{aId}/workspaces                → 204
```

### Experiments (analysis-scoped)

```
POST   /v1/analyses/{aId}/experiments              → {id, name, lifecycleStage}  Body: {name}
GET    /v1/analyses/{aId}/experiments              → [{id, name, lifecycleStage}]
GET    /v1/analyses/{aId}/experiments/{id}         → {experiment, runs[]}
GET    /v1/analyses/{aId}/experiments/{id}/runs    → [{runId, status, metrics, params}]
GET    /v1/analyses/{aId}/experiments/tracking-url → {url}
*      /v1/mlflow-proxy/**                         → MLflow API proxy (user+analysis prefixed)
```

### Pipelines

```
POST   /v1/pipelines              → {id, status, notebookName, createdAt}
         Body: {notebookPath, parameters, enableSpark}
GET    /v1/pipelines              → [{id, status, notebookName, createdAt}]
GET    /v1/pipelines/{runId}      → {id, status, inputPath, outputPath, parameters}
GET    /v1/pipelines/{runId}/output → {url, expiresAt}
GET    /v1/pipelines/notebooks    → [{name, path, lastModified}]
```

### Models (MLflow Registry)

```
GET    /v1/models                 → [{name, latestVersion, description}]
GET    /v1/models/{name}/versions → [{version, status, stage, artifactUri}]
```

### Serving (KServe Deployments)

```
POST   /v1/serving/deployments       → {id, endpointName, status}
         Body: {modelName, modelVersion}
GET    /v1/serving/deployments       → [{id, endpointName, status}]
GET    /v1/serving/deployments/{id}  → {id, endpointName, status, inferenceUrl}
DELETE /v1/serving/deployments/{id}  → 204
POST   /v1/serving/deployments/{id}/predict → predictions
         Body: {instances: [...]}
```

---

## 7. Project Structure

```
ml-platform/
├── backend/
│   ├── Dockerfile                              # Eclipse Temurin 21 Alpine
│   ├── build.gradle.kts                        # Spring Boot 3.5.0, Java 21
│   └── src/main/
│       ├── java/com/mlplatform/
│       │   ├── config/                         # SecurityConfig, CorsConfig, *Config (8 files)
│       │   ├── controller/                     # 9 controllers (see API Reference)
│       │   │   ├── AnalysisController.java     # /api/v1/analyses
│       │   │   ├── AuthController.java         # /api/v1/auth
│       │   │   ├── ExperimentController.java   # /api/v1/analyses/{id}/experiments + mlflow-proxy
│       │   │   ├── HealthController.java       # /api/v1/health
│       │   │   ├── ModelController.java        # /api/v1/models
│       │   │   ├── PipelineController.java     # /api/v1/pipelines
│       │   │   ├── PortalController.java       # /api/v1/portal
│       │   │   ├── ServingController.java      # /api/v1/serving/deployments
│       │   │   └── WorkspaceController.java    # /api/v1/analyses/{id}/workspaces
│       │   ├── dto/                            # Java records (20+ DTOs)
│       │   ├── model/                          # JPA entities: Analysis, ModelDeployment, PipelineRun, User, Workspace
│       │   ├── repository/                     # Spring Data JPA interfaces
│       │   └── service/                        # 11 services + 4 *UnavailableException
│       └── resources/
│           ├── application.yaml
│           ├── application-dev.yaml
│           └── db/migration/                   # V1, V2, V005, V006, V007, V008
│
├── frontend/
│   └── src/app/
│       ├── app.component.ts                    # Shell with nav sidebar
│       ├── app.routes.ts                       # Lazy-loaded routes with auth guard
│       ├── core/
│       │   ├── auth/oidc-auth.module.ts        # Keycloak OIDC PKCE config
│       │   ├── guards/auth.guard.ts
│       │   ├── interceptors/auth.interceptor.ts
│       │   └── services/                       # 8 services (analysis, auth, experiment, jupyter-bridge, model, pipeline, serving, workspace)
│       └── features/
│           ├── analyses/                       # Analysis list + layout (tabbed notebooks/experiments)
│           ├── dashboard/                      # Welcome page
│           ├── models/                         # Registry + deployments + deploy/predict dialogs
│           ├── notebooks/                      # Embedded via analysis-layout iframe
│           ├── experiments/                    # Embedded via analysis-layout iframe
│           └── pipelines/                      # Run list + trigger-dialog + run-detail
│
├── infrastructure/
│   ├── docker/notebook-image/                  # scipy-notebook + Java + Spark + ML libs
│   │   ├── Dockerfile
│   │   └── requirements.txt                    # numpy, pandas, sklearn, mlflow, deltalake, papermill, pyspark, etc.
│   ├── helm/
│   │   ├── jupyterhub/                         # Z2JH 4.3.2: Chart.yaml, values.yaml, local-values.yaml
│   │   ├── mlflow/                             # Custom chart: templates/, values.yaml
│   │   ├── minio/                              # local-values.yaml (official chart)
│   │   └── airflow/                            # local-values.yaml, pod-template.yaml (official chart)
│   ├── k8s/
│   │   ├── namespace.yaml
│   │   ├── keycloak/                           # configmap.yaml (realm JSON), deployment.yaml, service.yaml
│   │   ├── airflow/                            # dag-configmap.yaml (notebook_runner), spark-rbac.yaml
│   │   ├── sample-data/                        # provision-job, provision-script, sample-notebook, batch-inference-notebook, RO secret
│   │   └── platform/base/                      # backend-deployment, frontend-deployment, nginx-template
│   └── scripts/
│       ├── deploy-full-stack.sh                # End-to-end deployment for r1 cluster
│       └── port-forward.sh                     # Local dev port forwarding
│
├── specs/                                      # Feature specifications (speckit format)
│   ├── 001-keycloak-auth-portal/
│   ├── 002-jupyterhub-notebook/
│   ├── 003-mlflow-experiment-tracking/
│   ├── 004-sample-delta-data/
│   ├── 005-airflow-notebook-pipeline/
│   ├── 006-model-serving-inference/
│   └── 007-notebook-ui-customization/
│
├── docs/ARCHITECTURE.md                        # Detailed architecture reference
├── CLAUDE.md                                   # AI agent quick-reference instructions
├── PROJECT_REFERENCE.md                        # This file — comprehensive source of truth
└── .specify/memory/constitution.md             # Project constitution v2.0.0
```

---

## 8. Authentication

### Keycloak Configuration

- **Realm**: `ml-platform`
- **Clients**:
  - `ml-platform-portal` — Public client (PKCE flow), redirects to portal
  - `ml-platform-jupyterhub` — Confidential client for JupyterHub GenericOAuthenticator
  - `ml-platform-backend` — Bearer-only client for JWT validation
- **Test Users**:
  - `user1` / `password1` (user1@ml-platform.local)
  - `user2` / `password2` (user2@ml-platform.local)

### Authentication Flow

1. Angular app redirects to Keycloak (Authorization Code + PKCE)
2. User authenticates → Keycloak returns authorization code
3. App exchanges code for JWT tokens (access + refresh)
4. `POST /api/v1/auth/userinfo` syncs user to database
5. All API calls include `Authorization: Bearer <JWT>`
6. Backend validates JWT against Keycloak JWKS endpoint

### Token Propagation

| Component | Auth Method |
|-----------|-------------|
| Backend API | JWT Bearer (Keycloak JWKS validation) |
| JupyterHub | GenericOAuthenticator (Keycloak OIDC) |
| MLflow | No auth; proxied through backend |
| Airflow | Basic auth (admin/admin) proxied through backend |
| MinIO | AWS credentials (env vars or secrets) |
| KServe | Inference proxied through backend (JWT-protected) |

---

## 9. Infrastructure

### Kubernetes Workloads (`ml-platform` namespace)

| Workload | Type | Image |
|----------|------|-------|
| Keycloak | Deployment | `quay.io/keycloak/keycloak:26.1` |
| PostgreSQL | StatefulSet (Helm) | official postgres |
| JupyterHub | Deployment (Z2JH Helm) | official jupyterhub |
| User notebook pods | Pod (dynamic) | `ml-platform-notebook:latest` |
| MLflow | Deployment (custom Helm) | `ghcr.io/mlflow/mlflow:v3.10.0` |
| MinIO | StatefulSet (official Helm) | official minio |
| Airflow (webserver, scheduler, triggerer) | Deployment (Helm) | `apache/airflow:2.10.3-python3.11` |
| Pipeline worker pods | Pod (dynamic) | `ml-platform-notebook:latest` |
| Spark executor pods | Pod (dynamic) | `ml-platform-notebook:latest` |
| Backend | Deployment | `ml-platform-backend:latest` |
| Frontend | Deployment | nginx serving Angular build |
| Sample data provisioner | Job (one-shot) | `ml-platform-notebook:latest` |

### KServe Workloads (`ml-platform-serving` namespace)

| Workload | Type | Runtime |
|----------|------|---------|
| `*-predictor` | Deployment (InferenceService) | mlserver (V2 protocol) |

KServe runs in **Standard mode** — no Knative, no Istio, no scale-to-zero.

### Helm Charts (DO NOT use Bitnami)

| Chart | Source |
|-------|--------|
| JupyterHub | `https://hub.jupyter.org/helm-chart/` (Z2JH 4.3.2) |
| MinIO | `https://charts.min.io` (official) |
| Airflow | `https://airflow.apache.org` (official) |
| MLflow | Custom chart in `infrastructure/helm/mlflow/` |

### Notebook Image (single image, 4 uses)

**Base**: `jupyter/scipy-notebook:python-3.11` + Java 17 JRE + Hadoop AWS + Spark

Used for: (1) JupyterHub notebook servers, (2) Airflow pipeline workers, (3) Spark executors, (4) data provisioning.

Key packages: `numpy, pandas, scikit-learn, matplotlib, mlflow, deltalake, papermill, pyspark, boto3, torch, tensorflow`

### Sample Notebooks (mounted via ConfigMap)

| Notebook | ConfigMap | Mount Path |
|----------|----------|-----------|
| `sample-delta-data.ipynb` | `sample-notebook` | `/home/jovyan/examples/sample-delta-data.ipynb` |
| `batch-inference.ipynb` | `batch-inference-notebook` | `/home/jovyan/examples/batch-inference.ipynb` |

---

## 10. Configuration

### Backend (`application.yaml`)

```yaml
spring.datasource.url: jdbc:postgresql://postgresql.ml-platform.svc:5432/ml_platform
spring.security.oauth2.resourceserver.jwt.issuer-uri: http://keycloak.ml-platform.svc:8080/realms/ml-platform
spring.flyway.enabled: true

services:
  jupyterhub:
    url: http://hub.ml-platform.svc:8081
    proxy-url: http://proxy-public.ml-platform.svc:80
    api-token: ${JUPYTERHUB_API_TOKEN}
  mlflow:
    url: http://mlflow.ml-platform.svc:5000
    tracking-url: http://mlflow.ml-platform.svc:5000
  airflow:
    url: http://airflow-webserver.ml-platform.svc:8080
    username: admin
    password: admin
  minio:
    endpoint: http://minio.ml-platform.svc:9000
```

Backend env var overrides (on r1): `SERVICES_JUPYTERHUB_URL`, `SERVICES_JUPYTERHUB_PROXY_URL`, `JUPYTERHUB_API_TOKEN`

### Notebook Pod Environment

| Variable | Value | Source |
|----------|-------|--------|
| `AWS_ENDPOINT_URL` | `http://minio.ml-platform.svc:9000` | JupyterHub extraEnv |
| `AWS_ALLOW_HTTP` | `true` | JupyterHub extraEnv |
| `AWS_ACCESS_KEY_ID` | `sample-data-readonly` | K8s Secret |
| `AWS_SECRET_ACCESS_KEY` | (from secret) | K8s Secret |
| `MLFLOW_TRACKING_URI` | `http://mlflow.ml-platform.svc:5000` | JupyterHub extraEnv |

### Airflow DAG (`notebook_runner`)

The `notebook_runner` DAG (`infrastructure/k8s/airflow/dag-configmap.yaml`) runs notebooks via Papermill in a KubernetesPodOperator. Accepts `dag_run.conf`:

| Parameter | Required | Description |
|-----------|----------|-------------|
| `notebook_path` / `input_path` | Yes | S3 path to input notebook |
| `output_path` | Yes | S3 path for executed output |
| `parameters` | No | Dict of Papermill parameters |
| `enable_spark` | No | Boolean — sets up Spark-on-K8s env vars |

Pipeline worker pods get: `AWS_ENDPOINT_URL`, `AWS_ALLOW_HTTP=true`, `AWS_ACCESS_KEY_ID/SECRET`, `MLFLOW_TRACKING_URI`.

---

## 11. Deployment

### Local Development (Rancher Desktop)

```bash
# Prerequisites: kubectl, helm, java 21, node 18+, docker
# Uses kubectl context: local

# 1. Build images locally
docker build -t ml-platform-notebook:latest infrastructure/docker/notebook-image/
cd backend && ./gradlew build && docker build -t ml-platform-backend:latest . && cd ..

# 2. Deploy infrastructure
kubectl apply -f infrastructure/k8s/namespace.yaml
kubectl apply -f infrastructure/k8s/keycloak/
helm install minio minio/minio -n ml-platform -f infrastructure/helm/minio/local-values.yaml
helm install mlflow infrastructure/helm/mlflow -n ml-platform -f infrastructure/helm/mlflow/local-values.yaml
helm install jupyterhub jupyterhub/jupyterhub -n ml-platform -f infrastructure/helm/jupyterhub/values.yaml -f infrastructure/helm/jupyterhub/local-values.yaml
kubectl apply -f infrastructure/k8s/sample-data/
helm install airflow apache-airflow/airflow -n ml-platform -f infrastructure/helm/airflow/local-values.yaml
kubectl apply -f infrastructure/k8s/airflow/

# 3. Port forwarding
bash infrastructure/scripts/port-forward.sh
```

| Service | Local Port |
|---------|-----------|
| Keycloak | 8180 |
| Backend | 8080 |
| JupyterHub | 8181 |
| MLflow | 15000 |
| MinIO API/Console | 9000/9001 |
| Airflow | 8280 |

### Remote Cluster (r1)

```bash
# Uses kubectl context: r1
# Images pushed to ttl.sh ephemeral registry (24h expiry)
# Access via: http://172.16.100.10:30080

bash infrastructure/scripts/deploy-full-stack.sh
```

---

## 12. Frontend Routes

| Route | Component | Description |
|-------|-----------|-------------|
| `/dashboard` | DashboardComponent | Welcome page |
| `/analyses` | AnalysesComponent | Analysis list (create/select/delete) |
| `/analyses/:id/notebooks` | AnalysisLayoutComponent | Embedded JupyterLab iframe |
| `/analyses/:id/experiments` | AnalysisLayoutComponent | Embedded MLflow UI iframe |
| `/models` | ModelsComponent | Model registry + deployments |
| `/pipelines` | PipelinesComponent | Pipeline runs + trigger dialog |
| `/notebooks` | redirect → `/analyses` | Backward compat |
| `/experiments` | redirect → `/analyses` | Backward compat |

---

## 13. Design Principles

From the project constitution (v2.0.0):

1. **MVP-First Incremental Delivery** — Each feature independently deployable and verified
2. **Infrastructure as Code** — All configs version-controlled under `infrastructure/`
3. **Unified Authentication** — Keycloak is the single identity provider
4. **Environment Parity** — Same notebook image for JupyterHub, Airflow workers, Spark executors
5. **Testing at System Boundaries** — Integration tests at component boundaries over unit test coverage
6. **Production-Quality Within Scope** — Best practices within bounded scope; no GPU profiles, advanced RBAC, scale-to-zero, or multi-cluster until explicitly scoped
