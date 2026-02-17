# ML Platform Architecture Reference

A Kubernetes-native machine learning platform that unifies interactive notebook development, experiment tracking, pipeline orchestration, and model serving under a single authentication boundary.

## Table of Contents

- [System Overview](#system-overview)
- [Technology Stack](#technology-stack)
- [Architecture Diagram](#architecture-diagram)
- [Component Reference](#component-reference)
- [Data Model](#data-model)
- [API Reference](#api-reference)
- [Project Structure](#project-structure)
- [Infrastructure Topology](#infrastructure-topology)
- [Authentication Flow](#authentication-flow)
- [Feature Breakdown](#feature-breakdown)
- [Development Setup](#development-setup)
- [Configuration Reference](#configuration-reference)
- [Design Principles](#design-principles)

---

## System Overview

The ML Platform provides data scientists with an integrated workflow:

1. **Develop** interactively in JupyterHub notebooks with pre-loaded sample data (Delta Lake)
2. **Track** experiments and metrics in MLflow with automatic user isolation
3. **Productionize** notebooks as Airflow pipelines with optional Spark support
4. **Deploy** trained models as REST inference endpoints via KServe

All components share a single Keycloak identity provider. The Spring Boot backend acts as the orchestration layer, and the Angular frontend provides a unified portal with embedded iframes for JupyterLab, MLflow UI, and custom pipeline/serving views.

---

## Technology Stack

| Layer | Technology | Version | Purpose |
|-------|-----------|---------|---------|
| **Frontend** | Angular | 17.3 | SPA portal with OIDC auth |
| **Backend** | Spring Boot | 3.5.0 | REST API, orchestration, JWT validation |
| **Language** | Java | 21 | Backend runtime |
| **Language** | TypeScript | 5.4+ | Frontend |
| **Language** | Python | 3.11 | Notebooks, pipelines, provisioning |
| **Identity** | Keycloak | 26.1 | OIDC provider, realm management |
| **Notebooks** | JupyterHub (Z2JH) | 3.3.8 | Multi-user notebook server on K8s |
| **Experiments** | MLflow | 2.15.0 | Tracking, model registry, artifact store |
| **Pipelines** | Apache Airflow | 2.10.3 | DAG orchestration, KubernetesExecutor |
| **Processing** | Apache Spark | 4.0.1 | Distributed compute (client mode on K8s) |
| **Data Format** | Delta Lake | >= 0.22.0 | ACID table format on S3 |
| **Object Storage** | MinIO | latest | S3-compatible storage (official chart) |
| **Database** | PostgreSQL | latest | Shared metadata store |
| **Model Serving** | KServe | 0.13.x | InferenceService CRDs, V2 protocol |
| **Container Runtime** | Kubernetes | - | All components run as K8s workloads |
| **Build** | Gradle | - | Backend build (Kotlin DSL) |
| **Migrations** | Flyway | - | Database schema versioning |

---

## Architecture Diagram

```
                        ┌──────────────────────────────────────────┐
                        │              Angular SPA                  │
                        │   (OIDC PKCE → Keycloak, JWT Bearer)     │
                        └────────────────┬─────────────────────────┘
                                         │ REST API + JWT
                        ┌────────────────▼─────────────────────────┐
                        │          Spring Boot Backend              │
                        │  ┌──────┬──────┬──────┬──────┬────────┐  │
                        │  │Auth  │Work- │Exper-│Pipe- │Model   │  │
                        │  │Ctrl  │space │iment │line  │Serving │  │
                        │  │      │Ctrl  │Ctrl  │Ctrl  │Ctrl    │  │
                        │  └──┬───┴──┬───┴──┬───┴──┬───┴───┬────┘  │
                        │     │      │      │      │       │       │
                        │  ┌──▼──┐┌──▼──┐┌──▼──┐┌──▼──┐┌───▼───┐  │
                        │  │User ││Wksp ││Mlfl ││Pipe ││KServe │  │
                        │  │Svc  ││Svc  ││Svc  ││Svc  ││Svc    │  │
                        │  └──┬──┘└──┬──┘└──┬──┘└──┬──┘└───┬───┘  │
                        └─────┼──────┼──────┼──────┼───────┼───────┘
                              │      │      │      │       │
              ┌───────────────┼──────┼──────┼──────┼───────┼──────┐
              │               │      │      │      │       │      │
        ┌─────▼────┐  ┌───────▼──┐ ┌─▼───┐ ┌▼─────▼──┐ ┌──▼────┐│
        │Keycloak  │  │Jupyter-  │ │MLfl-│ │Airflow + │ │KServe ││
        │(OIDC)    │  │Hub (Z2JH)│ │ow   │ │Papermill │ │(Raw)  ││
        └─────┬────┘  └────┬─────┘ └──┬──┘ └────┬─────┘ └───┬───┘│
              │             │          │         │           │     │
        ┌─────▼─────────────▼──────────▼─────────▼───────────▼──┐ │
        │                    PostgreSQL                          │ │
        │  ┌────────┐ ┌──────────┐ ┌──────┐ ┌───────┐ ┌──────┐ │ │
        │  │keycloak│ │ml_platfrm│ │mlflow│ │airflow│ │jhub  │ │ │
        │  └────────┘ └──────────┘ └──────┘ └───────┘ └──────┘ │ │
        └───────────────────────────────────────────────────────┘ │
        ┌──────────────────────────────────────────────────────┐  │
        │                      MinIO (S3)                       │  │
        │  ┌──────────────┐ ┌───────────────┐ ┌─────────────┐ │  │
        │  │mlflow-artifacts│ │sample-data   │ │pipelines    │ │  │
        │  └──────────────┘ └───────────────┘ └─────────────┘ │  │
        └──────────────────────────────────────────────────────┘  │
              └───────────── Kubernetes Cluster ──────────────────┘
```

---

## Component Reference

### Backend (Spring Boot)

**Package**: `com.mlplatform` | **Base path**: `backend/src/main/java/com/mlplatform/`

#### Controllers

| Controller | Path Prefix | Purpose |
|-----------|-------------|---------|
| `HealthController` | `/api/health` | Liveness/readiness probe |
| `AuthController` | `/api/auth` | OIDC user sync (`/userinfo`), logout |
| `PortalController` | `/api/portal` | Navigation sections metadata |
| `WorkspaceController` | `/api/workspaces` | JupyterHub workspace lifecycle (launch/status/terminate) |
| `ExperimentController` | `/api/experiments` | MLflow experiment CRUD, run listing, tracking URL |
| `PipelineController` | `/api/pipelines` | Trigger notebook pipelines, list runs, get output URLs |
| Model Serving Controller | `/api/serving`, `/api/models` | KServe deployment lifecycle, inference proxy |

#### Services

| Service | External Dependency | Protocol |
|---------|-------------------|----------|
| `UserService` | PostgreSQL | JPA (users table) |
| `WorkspaceService` | JupyterHub | REST via `JupyterHubService` (WebClient) |
| `MlflowService` | MLflow Tracking Server | REST via RestTemplate |
| `ModelRegistryService` | MLflow Model Registry | REST via RestTemplate |
| `PipelineService` | Airflow | REST via `AirflowService` (RestTemplate) |
| `NotebookStorageService` | MinIO | S3 via MinIO Java SDK |
| `KServeService` | Kubernetes API | K8s Java Client for InferenceService CRDs |

#### Configuration Classes

| Config | Profile | Purpose |
|--------|---------|---------|
| `SecurityConfig` | default | OAuth2 JWT resource server with Keycloak JWKS |
| `DevSecurityConfig` | `dev` | Mock JWT filter (bypasses Keycloak for local testing) |
| `CorsConfig` | all | Configurable allowed-origins via `app.cors.allowed-origins` |
| `JupyterHubConfig` | all | WebClient for JupyterHub API with service token auth |
| `MlflowConfig` | all | RestTemplate for MLflow with timeout settings |
| `AirflowConfig` | all | RestTemplate for Airflow with basic auth |
| `StorageConfig` | all | MinIO client initialization |
| `KServeConfig` | all | Kubernetes ApiClient for KServe CRDs |
| `GlobalExceptionHandler` | all | Maps service exceptions to HTTP error responses |

### Frontend (Angular)

**Base path**: `frontend/src/app/`

#### Core

| File | Purpose |
|------|---------|
| `core/auth/oidc-auth.module.ts` | OIDC client config (Keycloak, PKCE flow) |
| `core/services/auth.service.ts` | Authentication state (BehaviorSubject), login/logout |
| `core/guards/auth.guard.ts` | Route guard requiring authenticated user |
| `core/interceptors/auth.interceptor.ts` | Attaches JWT Bearer token to all API requests |
| `core/services/workspace.service.ts` | JupyterHub workspace API client |
| `core/services/experiment.service.ts` | MLflow experiment API client |
| `core/services/pipeline.service.ts` | Pipeline API client |

#### Feature Components

| Component | Route | Description |
|-----------|-------|-------------|
| `dashboard.component.ts` | `/` | Welcome page with username display |
| `notebooks.component.ts` | `/notebooks` | Workspace launcher + embedded JupyterLab iframe |
| `experiments.component.ts` | `/experiments` | Experiment list + embedded MLflow UI iframe |
| `pipelines.component.ts` | `/pipelines` | Pipeline run list with status filtering |
| `trigger-dialog.component.ts` | (dialog) | Trigger notebook execution form |
| `run-detail.component.ts` | `/pipelines/:id` | Pipeline run details with output download |

### Notebook Image

**Dockerfile**: `infrastructure/docker/notebook-image/Dockerfile`

Base: `jupyter/scipy-notebook:python-3.11` with:
- Java 17 JRE (for Spark)
- Hadoop AWS JARs (S3A filesystem)
- Spark defaults targeting K8s master
- Executor shell script for Spark-on-K8s worker pods

**Key Python packages** (`requirements.txt`):
```
numpy, pandas, scikit-learn, matplotlib, mlflow, scipy, seaborn, plotly,
torch, tensorflow, jupyterlab, notebook, jupyterhub, ipywidgets, ipykernel,
pyarrow, deltalake>=0.22.0, papermill>=2.6.0, boto3>=1.34.0, pyspark==4.0.1
```

This single image is used for:
1. Interactive JupyterHub notebook servers
2. Airflow pipeline worker pods (Papermill execution)
3. Spark executor pods
4. Sample data provisioning job

---

## Data Model

### PostgreSQL Databases

The shared PostgreSQL instance hosts 5 databases:

| Database | Owner | Purpose |
|----------|-------|---------|
| `keycloak` | Keycloak | OIDC realm, users, clients, sessions |
| `ml_platform` | Spring Boot | Application entities (users, workspaces, pipeline_runs, model_deployments) |
| `mlflow` | MLflow | Experiment metadata, run metrics, model registry |
| `airflow` | Airflow | DAG definitions, task instances, XCom |
| `jupyterhub` | JupyterHub | Hub state, user servers, API tokens |

### Application Tables (Flyway-managed)

```sql
-- V1: users
users (
    id              UUID PRIMARY KEY,
    oidc_subject    VARCHAR(255) NOT NULL UNIQUE,  -- Keycloak subject ID
    username        VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    email           VARCHAR(255),
    created_at      TIMESTAMP NOT NULL,
    last_login      TIMESTAMP NOT NULL
)

-- V2: workspaces
workspaces (
    id                    UUID PRIMARY KEY,
    user_id               UUID NOT NULL → users(id),
    profile               VARCHAR(50) DEFAULT 'EXPLORATORY',
    status                VARCHAR(20) DEFAULT 'PENDING',  -- PENDING|RUNNING|STOPPED|FAILED
    pod_name              VARCHAR(255),
    jupyterhub_username   VARCHAR(255) NOT NULL,
    started_at            TIMESTAMP,
    last_activity         TIMESTAMP,
    created_at            TIMESTAMP NOT NULL
)

-- V5: pipeline_runs
pipeline_runs (
    id                 UUID PRIMARY KEY,
    user_id            UUID NOT NULL → users(id),
    notebook_name      VARCHAR(255) NOT NULL,
    input_path         VARCHAR(512) NOT NULL,
    output_path        VARCHAR(512),
    status             VARCHAR(20) DEFAULT 'PENDING',  -- PENDING|RUNNING|SUCCESS|FAILED
    airflow_dag_run_id VARCHAR(255),
    parameters         JSONB,
    enable_spark       BOOLEAN DEFAULT FALSE,
    started_at         TIMESTAMP,
    completed_at       TIMESTAMP,
    error_message      TEXT,
    created_at         TIMESTAMP NOT NULL
)

-- V6: model_deployments
model_deployments (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL → users(id),
    model_name      VARCHAR(255) NOT NULL,
    model_version   INTEGER NOT NULL,
    endpoint_name   VARCHAR(255) NOT NULL UNIQUE,
    status          VARCHAR(20) NOT NULL,  -- CREATING|READY|FAILED|DELETING
    inference_url   VARCHAR(512),
    storage_uri     VARCHAR(512) NOT NULL,
    error_message   TEXT,
    created_at      TIMESTAMP NOT NULL,
    ready_at        TIMESTAMP,
    deleted_at      TIMESTAMP
)
```

### MinIO Buckets

| Bucket | Purpose | Access |
|--------|---------|--------|
| `ml-platform-mlflow` | MLflow artifacts (models, metrics, plots) | MLflow server (admin creds) |
| `ml-platform-sample-data` | Delta Lake table (California Housing) | Read-only user for notebooks |
| `ml-platform-pipelines` | Pipeline notebooks (input/output), Airflow logs | Backend + Airflow (admin creds) |

### Sample Delta Table Schema

Stored at `s3://ml-platform-sample-data/california-housing/`:

| Column | Type | Description |
|--------|------|-------------|
| MedInc | float64 | Median income in block group |
| HouseAge | float64 | Median house age |
| AveRooms | float64 | Average rooms per household |
| AveBedrms | float64 | Average bedrooms per household |
| Population | float64 | Block group population |
| AveOccup | float64 | Average household members |
| Latitude | float64 | Block group latitude |
| Longitude | float64 | Block group longitude |
| MedHouseVal | float64 | Median house value (target) |

20,640 rows from scikit-learn's `fetch_california_housing()`.

---

## API Reference

### Backend REST API

Base URL: `http://localhost:8080/api`

#### Authentication & Portal

```
GET    /health                    → HealthResponse {status}
GET    /auth/userinfo             → UserInfoResponse {username, email, displayName}
POST   /auth/logout               → 200 (sync user then logout)
GET    /portal/sections           → PortalSectionResponse[] {title, route, icon}
```

#### Workspaces (JupyterHub)

```
GET    /workspaces/profiles       → ComputeProfileDto[] {slug, displayName, description, cpu, memory}
POST   /workspaces                → WorkspaceStatusDto {id, status, message}
GET    /workspaces                → WorkspaceStatusDto {id, status, message}
GET    /workspaces/url            → WorkspaceUrlDto {url}
DELETE /workspaces                → 204
```

#### Experiments (MLflow)

```
POST   /experiments               → ExperimentInfoDto {id, name, lifecycleStage}
GET    /experiments               → ExperimentInfoDto[]
GET    /experiments/{id}          → ExperimentDetailDto {experiment, runs[]}
GET    /experiments/{id}/runs     → RunInfoDto[] {runId, status, metrics, params, startTime}
GET    /experiments/tracking-url  → TrackingUrlDto {url}
```

#### Pipelines (Airflow)

```
POST   /pipelines                 → PipelineRunInfoDto {id, status, notebookName, createdAt}
         Body: TriggerPipelineRequest {notebookPath, parameters, enableSpark}
GET    /pipelines                 → Page<PipelineRunInfoDto>
GET    /pipelines/{runId}         → PipelineRunDetailDto {id, status, inputPath, outputPath, parameters}
GET    /pipelines/{runId}/output  → PipelineOutputUrlDto {url, expiresAt}
GET    /pipelines/notebooks       → NotebookInfoDto[] {name, path, lastModified}
```

#### Model Serving (KServe)

```
GET    /models                    → RegisteredModelInfoDto[] {name, latestVersion, description}
GET    /models/{name}/versions    → ModelVersionInfoDto[] {version, status, stage, artifactUri}
POST   /serving/deployments       → DeploymentInfoDto {id, endpointName, status}
         Body: DeployModelRequest {modelName, modelVersion}
GET    /serving/deployments       → DeploymentInfoDto[]
GET    /serving/deployments/{id}  → DeploymentDetailDto {id, endpointName, status, inferenceUrl}
DELETE /serving/deployments/{id}  → 204
POST   /serving/deployments/{id}/predict → PredictionResponseDto
         Body: PredictionRequestDto
```

---

## Project Structure

```
ml-platform/
├── backend/
│   ├── Dockerfile                              # Eclipse Temurin 21 Alpine
│   ├── build.gradle.kts                        # Spring Boot 3.5.0, Java 21
│   ├── settings.gradle.kts
│   └── src/main/
│       ├── java/com/mlplatform/
│       │   ├── MlPlatformApplication.java
│       │   ├── config/
│       │   │   ├── SecurityConfig.java         # OAuth2 JWT (production)
│       │   │   ├── DevSecurityConfig.java      # Mock JWT (dev profile)
│       │   │   ├── CorsConfig.java
│       │   │   ├── GlobalExceptionHandler.java
│       │   │   ├── JupyterHubConfig.java       # WebClient
│       │   │   ├── MlflowConfig.java           # RestTemplate
│       │   │   ├── AirflowConfig.java          # RestTemplate + basic auth
│       │   │   ├── StorageConfig.java          # MinIO client
│       │   │   └── KServeConfig.java           # K8s API client
│       │   ├── controller/
│       │   │   ├── AuthController.java
│       │   │   ├── HealthController.java
│       │   │   ├── PortalController.java
│       │   │   ├── WorkspaceController.java
│       │   │   ├── ExperimentController.java
│       │   │   └── PipelineController.java
│       │   ├── dto/                            # Java records (20+ DTOs)
│       │   ├── model/
│       │   │   ├── User.java                   # JPA entity
│       │   │   ├── Workspace.java              # JPA entity
│       │   │   ├── PipelineRun.java            # JPA entity
│       │   │   └── ModelDeployment.java        # JPA entity
│       │   ├── repository/                     # Spring Data JPA interfaces
│       │   └── service/
│       │       ├── UserService.java
│       │       ├── WorkspaceService.java
│       │       ├── JupyterHubService.java
│       │       ├── MlflowService.java
│       │       ├── ModelRegistryService.java
│       │       ├── PipelineService.java
│       │       ├── AirflowService.java
│       │       ├── NotebookStorageService.java
│       │       ├── KServeService.java
│       │       └── *UnavailableException.java  # Typed exceptions
│       └── resources/
│           ├── application.yaml                # Default config
│           ├── application-dev.yaml            # Dev profile overrides
│           ├── application-local.yaml          # Local profile overrides
│           └── db/migration/
│               ├── V1__create_users.sql
│               ├── V2__create_workspaces.sql
│               ├── V005__create_pipeline_runs.sql
│               └── V006__create_model_deployments.sql
│
├── frontend/
│   ├── angular.json
│   ├── package.json                            # Angular 17.3, angular-auth-oidc-client
│   ├── tsconfig.json
│   └── src/
│       ├── main.ts
│       ├── environments/
│       │   ├── environment.ts                  # Dev config
│       │   └── environment.prod.ts             # Production config
│       └── app/
│           ├── app.component.ts                # Shell with nav
│           ├── app.routes.ts                   # Lazy-loaded routes with auth guard
│           ├── core/
│           │   ├── auth/oidc-auth.module.ts    # Keycloak OIDC config
│           │   ├── guards/auth.guard.ts
│           │   ├── interceptors/auth.interceptor.ts
│           │   └── services/
│           │       ├── auth.service.ts
│           │       ├── workspace.service.ts
│           │       ├── experiment.service.ts
│           │       └── pipeline.service.ts
│           └── features/
│               ├── dashboard/dashboard.component.ts
│               ├── notebooks/notebooks.component.ts
│               ├── experiments/experiments.component.ts
│               └── pipelines/
│                   ├── pipelines.component.ts
│                   ├── trigger-dialog/trigger-dialog.component.ts
│                   └── run-detail/run-detail.component.ts
│
├── infrastructure/
│   ├── docker/
│   │   └── notebook-image/
│   │       ├── Dockerfile                      # scipy-notebook + Java + Spark + ML libs
│   │       └── requirements.txt                # 21 Python packages
│   ├── helm/
│   │   ├── jupyterhub/
│   │   │   ├── Chart.yaml                      # Z2JH 3.3.8
│   │   │   ├── values.yaml                     # Production defaults
│   │   │   └── local-values.yaml               # Local dev overrides
│   │   ├── mlflow/
│   │   │   ├── Chart.yaml
│   │   │   ├── values.yaml                     # MLflow 2.15.0 config
│   │   │   ├── local-values.yaml
│   │   │   └── templates/
│   │   │       ├── configmap.yaml
│   │   │       ├── deployment.yaml
│   │   │       └── service.yaml
│   │   ├── minio/
│   │   │   └── local-values.yaml               # Standalone, 3 buckets, RO policy
│   │   └── airflow/
│   │       ├── local-values.yaml               # KubernetesExecutor, Papermill DAG
│   │       └── pod-template.yaml
│   ├── k8s/
│   │   ├── namespace.yaml                      # ml-platform namespace
│   │   ├── keycloak/
│   │   │   ├── configmap.yaml                  # Realm JSON (users, clients)
│   │   │   ├── deployment.yaml                 # Keycloak 26.1
│   │   │   └── service.yaml
│   │   ├── airflow/
│   │   │   ├── dag-configmap.yaml              # notebook_runner DAG
│   │   │   └── spark-rbac.yaml                 # ServiceAccount + ClusterRoleBinding
│   │   └── sample-data/
│   │       ├── provision-job.yaml              # K8s Job (writes Delta table)
│   │       ├── provision-script-configmap.yaml # Python provisioning script
│   │       ├── read-only-secret.yaml           # MinIO RO credentials
│   │       └── sample-notebook-configmap.yaml  # Example Jupyter notebook
│   └── scripts/
│       └── port-forward.sh                     # Local dev port forwarding
│
├── specs/                                      # Feature specifications (speckit)
│   ├── 001-keycloak-auth-portal/
│   ├── 002-jupyterhub-notebook/
│   ├── 003-mlflow-experiment-tracking/
│   ├── 004-sample-delta-data/
│   ├── 005-airflow-notebook-pipeline/
│   └── 006-model-serving-inference/
│
├── .specify/                                   # speckit configuration
│   └── memory/constitution.md                  # Project constitution (v1.0.0)
│
├── .gitignore
├── .dockerignore
└── CLAUDE.md                                   # AI agent instructions
```

---

## Infrastructure Topology

### Kubernetes Namespace: `ml-platform`

All workloads run in a single `ml-platform` namespace.

| Workload | Type | Image | Ports |
|----------|------|-------|-------|
| Keycloak | Deployment | `quay.io/keycloak/keycloak:26.1` | 8080 |
| PostgreSQL | StatefulSet (Helm) | official postgres | 5432 |
| JupyterHub | Deployment (Z2JH Helm) | official jupyterhub | 8081 (hub), 80 (proxy) |
| JupyterHub user pods | Pod (dynamic) | `ml-platform-notebook:latest` | 8888 |
| MLflow | Deployment (custom Helm) | `ghcr.io/mlflow/mlflow:v2.15.0` | 5000 |
| MinIO | StatefulSet (official Helm) | official minio | 9000 (API), 9001 (console) |
| Airflow Webserver | Deployment (Helm) | `apache/airflow:2.10.3-python3.11` | 8080 |
| Airflow Scheduler | Deployment (Helm) | same | - |
| Airflow Triggerer | Deployment (Helm) | same | - |
| Pipeline worker pods | Pod (dynamic) | `ml-platform-notebook:latest` | - |
| Spark executor pods | Pod (dynamic) | `ml-platform-notebook:latest` | - |
| Backend | Deployment | `ml-platform-backend:latest` | 8080 |
| Sample data provisioner | Job (one-shot) | `ml-platform-notebook:latest` | - |

### Helm Charts

| Chart | Source | Local Values |
|-------|--------|-------------|
| JupyterHub | `https://hub.jupyter.org/helm-chart/` (Z2JH 3.3.8) | `helm/jupyterhub/local-values.yaml` |
| MinIO | `https://charts.min.io` (official) | `helm/minio/local-values.yaml` |
| Airflow | `https://airflow.apache.org` (official) | `helm/airflow/local-values.yaml` |
| MLflow | Custom chart in `helm/mlflow/` | `helm/mlflow/local-values.yaml` |

**Important**: Do NOT use Bitnami Helm charts. All charts use official upstream repositories.

### Service Connectivity (In-Cluster)

```
Frontend (browser) ─── https://portal.ml-platform.local ───► Backend (8080)
                                                              ├──► Keycloak     (keycloak.ml-platform.svc:8080)
                                                              ├──► JupyterHub   (jupyterhub-hub.ml-platform.svc:8081)
                                                              ├──► MLflow       (mlflow.ml-platform.svc:5000)
                                                              ├──► Airflow      (airflow-webserver.ml-platform.svc:8080)
                                                              ├──► MinIO        (minio.ml-platform.svc:9000)
                                                              └──► K8s API      (kubernetes.default.svc)

Notebook pods ──► MLflow (MLFLOW_TRACKING_URI=http://mlflow.ml-platform.svc:5000)
              ──► MinIO  (AWS_ENDPOINT_URL=http://minio.ml-platform.svc:9000)
```

---

## Authentication Flow

### Login Sequence

```
1. User visits Angular app
2. App redirects to Keycloak (Authorization Code + PKCE)
3. User authenticates at Keycloak login page
4. Keycloak redirects back with authorization code
5. App exchanges code for JWT (access + refresh tokens)
6. App calls POST /api/auth/userinfo → Backend syncs user to DB
7. All subsequent API calls include Authorization: Bearer <JWT>
8. Backend validates JWT signature against Keycloak JWKS endpoint
```

### Keycloak Realm Configuration

- **Realm**: `ml-platform`
- **Clients**:
  - `ml-platform-portal` — Public client (PKCE), redirect to `http://localhost:4200/*`
  - `ml-platform-jupyterhub` — Confidential client for JupyterHub OAuth
  - `ml-platform-backend` — Bearer-only client for JWT validation
- **Test Users**: `scientist1`/`scientist1`, `scientist2`/`scientist2`

### Token Propagation

| Component | Auth Method |
|-----------|-------------|
| Backend API | JWT Bearer (validated against Keycloak JWKS) |
| JupyterHub | GenericOAuthenticator (Keycloak OIDC) |
| MLflow | No direct auth; proxied through backend |
| Airflow | Basic auth (admin/admin) proxied through backend |
| MinIO | AWS credentials (env vars or secrets) |
| KServe | Inference proxied through backend (JWT-protected) |

---

## Feature Breakdown

### Feature Dependency Chain

```
001-Keycloak Auth & Portal
    └──► 002-JupyterHub Notebook Embedding
              ├──► 003-MLflow Experiment Tracking
              │         └──► 005-Airflow Notebook Pipeline
              │                    └──► 006-Model Serving & Inference
              └──► 004-Sample Delta Lake Data
```

### Feature Summary

| # | Feature | Status | Backend Files | Frontend Files | Infra Files |
|---|---------|--------|--------------|----------------|-------------|
| 001 | Keycloak Auth & Portal | Implemented | Auth/Portal controllers, UserService, SecurityConfig | auth.service, oidc-auth.module, auth.guard, dashboard | keycloak/ manifests |
| 002 | JupyterHub Notebooks | Implemented | WorkspaceController, WorkspaceService, JupyterHubService | notebooks.component, workspace.service | jupyterhub/ helm, notebook-image/ |
| 003 | MLflow Experiments | Implemented | ExperimentController, MlflowService, MlflowConfig | experiments.component, experiment.service | mlflow/ helm, minio/ helm |
| 004 | Sample Delta Data | Implemented | (none) | (none) | sample-data/ manifests, minio/ updates |
| 005 | Airflow Pipelines | Implemented | PipelineController, PipelineService, AirflowService, NotebookStorageService | pipelines, trigger-dialog, run-detail | airflow/ helm, dag-configmap, spark-rbac |
| 006 | Model Serving | In Progress | KServeService, ModelRegistryService, KServeConfig, ModelDeployment | (pending) | (pending KServe manifests) |

---

## Development Setup

### Prerequisites

- Kubernetes cluster (Rancher Desktop, Docker Desktop, or minikube)
- `kubectl`, `helm`, `java 21`, `node 18+`, `docker`

### Local Port Mapping

Run `infrastructure/scripts/port-forward.sh` to expose cluster services:

| Service | Local Port | Cluster Port |
|---------|-----------|-------------|
| Keycloak | 8180 | 8080 |
| PostgreSQL | 5432 | 5432 |
| JupyterHub | 8181 | 80 |
| Backend | 8080 | 8080 |
| MLflow | 15000 | 5000 |
| MinIO API | 9000 | 9000 |
| MinIO Console | 9001 | 9001 |
| Airflow | 8280 | 8080 |

### Build Commands

```bash
# Backend
cd backend && ./gradlew build

# Frontend
cd frontend && npm install && npm run build

# Notebook image
docker build -t ml-platform-notebook:latest infrastructure/docker/notebook-image/

# Backend image
cd backend && ./gradlew build && docker build -t ml-platform-backend:latest .
```

### Deployment Order

```bash
# 1. Namespace
kubectl apply -f infrastructure/k8s/namespace.yaml

# 2. PostgreSQL (create databases: keycloak, ml_platform, mlflow, airflow, jupyterhub)

# 3. Keycloak
kubectl apply -f infrastructure/k8s/keycloak/

# 4. MinIO (official chart from charts.min.io)
helm install minio minio/minio -n ml-platform -f infrastructure/helm/minio/local-values.yaml

# 5. MLflow
helm install mlflow infrastructure/helm/mlflow -n ml-platform -f infrastructure/helm/mlflow/local-values.yaml

# 6. JupyterHub
helm install jupyterhub jupyterhub/jupyterhub -n ml-platform -f infrastructure/helm/jupyterhub/values.yaml -f infrastructure/helm/jupyterhub/local-values.yaml

# 7. Sample data provisioning
kubectl apply -f infrastructure/k8s/sample-data/

# 8. Airflow
helm install airflow apache-airflow/airflow -n ml-platform -f infrastructure/helm/airflow/local-values.yaml
kubectl apply -f infrastructure/k8s/airflow/

# 9. Backend
kubectl apply -f backend deployment manifests

# 10. Port forwarding
bash infrastructure/scripts/port-forward.sh
```

---

## Configuration Reference

### Backend Application Properties

```yaml
# application.yaml — key settings
spring.datasource.url: jdbc:postgresql://postgresql.ml-platform.svc:5432/ml_platform
spring.security.oauth2.resourceserver.jwt.issuer-uri: http://keycloak.ml-platform.svc:8080/realms/ml-platform
spring.flyway.enabled: true

services:
  jupyterhub.url: http://jupyterhub-hub.ml-platform.svc:8081
  mlflow.url: http://mlflow.ml-platform.svc:5000
  airflow.url: http://airflow-webserver.ml-platform.svc:8080
  minio.endpoint: http://minio.ml-platform.svc:9000
```

### Environment Variables for Notebook Pods

Injected via JupyterHub singleuser config:

| Variable | Value | Source |
|----------|-------|--------|
| `AWS_ENDPOINT_URL` | `http://minio.ml-platform.svc:9000` | extraEnv |
| `AWS_ALLOW_HTTP` | `true` | extraEnv |
| `AWS_ACCESS_KEY_ID` | `sample-data-readonly` | Secret: `sample-data-readonly-credentials` |
| `AWS_SECRET_ACCESS_KEY` | (from secret) | Secret: `sample-data-readonly-credentials` |
| `MLFLOW_TRACKING_URI` | `http://mlflow.ml-platform.svc:5000` | extraEnv (via local-values) |

### Airflow DAG Configuration

The `notebook_runner` DAG (in `dag-configmap.yaml`) accepts these `dag_run.conf` parameters:

| Parameter | Required | Description |
|-----------|----------|-------------|
| `notebook_path` / `input_path` | Yes | S3 path to input notebook |
| `output_path` | Yes | S3 path for executed notebook output |
| `parameters` | No | Dict of Papermill parameters |
| `enable_spark` | No | Boolean — sets up Spark env vars |
| `spark_image` | No | Override Spark executor image |

---

## Design Principles

From the [project constitution](.specify/memory/constitution.md) (v1.0.0):

1. **MVP-First Incremental Delivery** — Each feature is independently deployable and verified before the next begins. The prior project failed by designing everything simultaneously.

2. **Infrastructure as Code** — All K8s manifests, Helm charts, and configs are version-controlled under `infrastructure/`. No manual cluster operations.

3. **Unified Authentication** — Keycloak is the single identity provider. No component implements its own auth. JWTs propagate from frontend through backend to all services.

4. **Environment Parity** — The notebook image used interactively in JupyterHub is identical to Airflow pipeline workers and Spark executors.

5. **Testing at System Boundaries** — Integration tests at component boundaries take priority over unit test coverage. Each feature must include end-to-end K8s verification.

6. **Simplicity & YAGNI** — No GPU profiles, advanced RBAC, scale-to-zero, or multi-cluster until explicitly scoped. Prefer direct solutions over abstractions.
