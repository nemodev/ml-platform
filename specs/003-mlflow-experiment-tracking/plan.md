# Implementation Plan: MLflow Experiment Tracking

**Branch**: `003-mlflow-experiment-tracking` | **Date**: 2026-02-16 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-mlflow-experiment-tracking/spec.md`

## Summary

Add MLflow experiment tracking to the ML Platform. Users log training
runs (parameters, metrics, model artifacts) from notebooks, and view
results in the MLflow UI embedded in the portal. A shared MLflow
tracking server stores metadata in PostgreSQL and artifacts in MinIO.
User isolation is achieved via username-prefixed experiment names
managed by the backend proxy.

Key technical decisions from research:
- MLflow tracking server (`ghcr.io/mlflow/mlflow:2.15.0`) deployed
  via custom Helm chart with `--serve-artifacts` for proxied artifact
  access
- MinIO (official Helm chart from `charts.min.io`, standalone mode) for
  S3-compatible artifact storage
- Shared PostgreSQL for MLflow metadata (new `mlflow` database)
- Backend proxy for experiment CRUD with user-prefix isolation
- MLflow UI embedded via iframe with network-level isolation
  (ClusterIP, no auth layer on MLflow itself)
- `MLFLOW_TRACKING_URI` injected into notebook containers via
  JupyterHub KubeSpawner config

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend),
  Python 3.11 (notebooks)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17, MLflow 2.15.0,
  MinIO (official Helm chart, `charts.min.io`), Keycloak 26.x
**Storage**: PostgreSQL (`mlflow` database for metadata),
  MinIO (`ml-platform-mlflow` bucket for artifacts)
**Testing**: JUnit 5 (backend), Jasmine + Karma (frontend),
  integration tests with real MLflow + notebook
**Target Platform**: Kubernetes (all components)
**Project Type**: Web application (backend + frontend + infrastructure)
**Performance Goals**: Run logging < 5s, run visible in UI within 30s
  of cell completion (SC-001)
**Constraints**: No MLflow auth layer (network isolation only),
  user-prefix isolation, single MinIO bucket
**Scale/Scope**: ~20 concurrent users, artifact storage capacity-bound
  by MinIO PVC

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. MVP-First Incremental Delivery | PASS | Feature 003 depends on features 001+002; independently deployable and verifiable |
| II. Infrastructure as Code | PASS | MLflow + MinIO deployed via Helm charts; all config version-controlled |
| III. Unified Authentication | PASS | Portal enforces auth; MLflow API access proxied through authenticated backend |
| IV. Environment Parity | N/A | No new images shared across environments in this feature |
| V. Testing at System Boundaries | PASS | Integration tests: notebook → backend proxy → MLflow → MinIO; UI iframe embedding |
| VI. Simplicity & YAGNI | PASS | No MLflow model registry, no auth proxy, single MinIO node, user-prefix isolation |

**Post-Phase 1 re-check**: All gates still PASS. No violations
introduced during design.

## Project Structure

### Documentation (this feature)

```text
specs/003-mlflow-experiment-tracking/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # OpenAPI contract
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
└── src/
    ├── main/
    │   ├── java/com/mlplatform/
    │   │   ├── config/
    │   │   │   └── MlflowConfig.java          # MLflow URL + config
    │   │   ├── controller/
    │   │   │   └── ExperimentController.java   # /experiments/* endpoints
    │   │   ├── dto/
    │   │   │   ├── CreateExperimentRequest.java
    │   │   │   ├── ExperimentInfoDto.java
    │   │   │   ├── ExperimentDetailDto.java
    │   │   │   ├── RunInfoDto.java
    │   │   │   └── TrackingUrlDto.java
    │   │   └── service/
    │   │       └── MlflowService.java          # MLflow REST API client + prefix logic
    │   └── resources/
    │       ├── application.yaml                # Add mlflow config section
    │       ├── application-local.yaml          # Local MLflow URL
    │       └── application-dev.yaml            # Mock MLflow responses
    └── test/
        └── java/com/mlplatform/
            └── controller/
                └── ExperimentControllerTest.java

frontend/
└── src/
    └── app/
        ├── features/
        │   └── experiments/
        │       ├── experiments.component.ts    # Replace placeholder with MLflow iframe
        │       ├── experiments.component.html
        │       └── experiments.component.scss
        └── core/
            └── services/
                └── experiment.service.ts       # HTTP client for experiment API

infrastructure/
├── helm/
│   ├── mlflow/
│   │   ├── Chart.yaml                         # Custom chart metadata
│   │   ├── templates/
│   │   │   ├── deployment.yaml                # MLflow server pod
│   │   │   ├── service.yaml                   # ClusterIP on port 5000
│   │   │   └── configmap.yaml                 # MLflow env vars
│   │   ├── values.yaml                        # Production values
│   │   └── local-values.yaml                  # Local dev values
│   └── minio/
│       ├── (use official MinIO Helm chart from charts.min.io)
│       └── local-values.yaml                  # Local dev overrides
└── scripts/
    └── port-forward.sh                        # Add MLflow (5000) + MinIO (9000)
```

**Structure Decision**: Extends the web application structure from
features 001-002. New backend service + controller for MLflow proxy,
frontend component update for experiments iframe, and new Helm charts
for MLflow and MinIO infrastructure.

## Complexity Tracking

No constitution violations to justify. All design choices align with
Principle VI (Simplicity & YAGNI):
- User-prefix isolation (no MLflow multi-tenancy plugin)
- Network-level security (no OAuth2 Proxy for MLflow)
- Single MinIO node (no operator, no erasure coding)
- No MLflow model registry UI (enabled in feature 006 — the registry
  is built into the tracking server but not exposed until then)
- Backend proxy reuses existing RestTemplate/WebClient pattern
