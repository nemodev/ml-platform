# Implementation Plan: Airflow Notebook Pipeline

**Branch**: `005-airflow-notebook-pipeline` | **Date**: 2026-02-16 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/005-airflow-notebook-pipeline/spec.md`

## Summary

Add Airflow-based notebook pipeline execution to the ML Platform. Users
trigger notebooks as production jobs from a custom Pipelines UI in the
portal. The backend copies notebooks from the user's JupyterHub
workspace to MinIO, triggers a reusable Airflow DAG via the REST API,
and polls for job status. Papermill executes notebooks in isolated K8s
pods using the same notebook Docker image (environment parity). Spark
jobs spawn executor pods on the cluster for distributed processing.

Key technical decisions from research:
- Official Apache Airflow Helm chart with KubernetesExecutor (no
  Bitnami, no message broker)
- Papermill execution via KubernetesPodOperator in isolated pods
- Single reusable `notebook_runner` DAG template (no per-notebook DAGs)
- Notebooks copied to MinIO before execution (immutable snapshots)
- Custom Angular pipeline UI (not embedded Airflow iframe)
- Spark client mode: 2 executors, 1 CPU / 2Gi RAM each
- Backend proxy for Airflow REST API with user isolation
- Shared PostgreSQL for Airflow metadata (`airflow` database)

**Important**: No Bitnami Helm charts or images are used in this
feature. The official MinIO chart (`charts.min.io`) replaces the
Bitnami MinIO chart referenced in features 003/004.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend),
  Python 3.11 (notebooks/pipeline pods)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17, Airflow 2.10.x
  (official Helm chart), Papermill 2.6.0, PySpark 4.0.x, Delta Lake 4.0.x
**Storage**: PostgreSQL (`airflow` database for metadata),
  MinIO (`ml-platform-pipelines` bucket for notebooks + logs)
**Testing**: JUnit 5 (backend), Jasmine + Karma (frontend),
  integration tests with real Airflow + notebook execution
**Target Platform**: Kubernetes (all components)
**Project Type**: Web application (backend + frontend + infrastructure)
**Performance Goals**: Simple notebook pipeline completes < 5 min
  (SC-001), Spark executor cleanup < 2 min (SC-005)
**Constraints**: KubernetesExecutor only (no CeleryExecutor in MVP),
  single DAG template, no scheduled pipelines
**Scale/Scope**: ~20 concurrent users, pipeline concurrency limited
  by K8s resources

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. MVP-First Incremental Delivery | PASS | Feature 005 depends on 001+002+003; independently deployable and verifiable |
| II. Infrastructure as Code | PASS | Airflow Helm chart, DAG ConfigMap, Spark RBAC, all version-controlled |
| III. Unified Authentication | PASS | Users authenticate via Keycloak JWT; backend proxies Airflow with basic auth |
| IV. Environment Parity | PASS | Pipeline pods use identical notebook Docker image (FR-005) |
| V. Testing at System Boundaries | PASS | Integration tests: portal → backend → Airflow → K8s pod → MinIO |
| VI. Simplicity & YAGNI | PASS | Single DAG template, KubernetesExecutor, no scheduler, no CeleryExecutor |

**Post-Phase 1 re-check**: All gates still PASS. No violations
introduced during design.

## Project Structure

### Documentation (this feature)

```text
specs/005-airflow-notebook-pipeline/
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
    │   │   │   └── AirflowConfig.java           # Airflow URL + credentials
    │   │   ├── controller/
    │   │   │   └── PipelineController.java       # /pipelines/* endpoints
    │   │   ├── dto/
    │   │   │   ├── TriggerPipelineRequest.java
    │   │   │   ├── PipelineRunInfoDto.java
    │   │   │   ├── PipelineRunDetailDto.java
    │   │   │   ├── PipelineOutputUrlDto.java
    │   │   │   └── NotebookInfoDto.java
    │   │   ├── model/
    │   │   │   └── PipelineRun.java              # JPA entity
    │   │   ├── repository/
    │   │   │   └── PipelineRunRepository.java    # Spring Data JPA
    │   │   └── service/
    │   │       ├── AirflowService.java           # Airflow REST API client
    │   │       ├── PipelineService.java           # Pipeline orchestration logic
    │   │       └── NotebookStorageService.java   # MinIO copy + pre-signed URLs
    │   └── resources/
    │       ├── application.yaml                  # Add airflow config section
    │       ├── application-local.yaml            # Local Airflow URL
    │       ├── application-dev.yaml              # Mock Airflow responses
    │       └── db/migration/
    │           └── V005__create_pipeline_runs.sql
    └── test/
        └── java/com/mlplatform/
            └── controller/
                └── PipelineControllerTest.java

frontend/
└── src/
    └── app/
        ├── features/
        │   └── pipelines/
        │       ├── pipelines.component.ts        # Pipeline run list
        │       ├── pipelines.component.html
        │       ├── pipelines.component.scss
        │       ├── trigger-dialog/
        │       │   ├── trigger-dialog.component.ts    # Trigger form
        │       │   ├── trigger-dialog.component.html
        │       │   └── trigger-dialog.component.scss
        │       └── run-detail/
        │           ├── run-detail.component.ts    # Run detail view
        │           ├── run-detail.component.html
        │           └── run-detail.component.scss
        └── core/
            └── services/
                └── pipeline.service.ts           # HTTP client for pipeline API

infrastructure/
├── helm/
│   └── airflow/
│       ├── local-values.yaml                    # KubernetesExecutor, DB, MinIO
│       └── pod-template.yaml                    # Pod template for task pods
├── k8s/
│   └── airflow/
│       ├── dag-configmap.yaml                   # notebook_runner DAG
│       └── spark-rbac.yaml                      # ServiceAccount + Role + RoleBinding
└── scripts/
    └── port-forward.sh                          # Add Airflow webserver (8280)
```

**Structure Decision**: Extends the web application structure from
features 001-003. New backend controller + services for pipeline
proxy, new frontend components for pipeline UI, Airflow Helm chart
deployment, and K8s manifests for DAG template and Spark RBAC.

## Complexity Tracking

No constitution violations to justify. All design choices align with
Principle VI (Simplicity & YAGNI):
- Single reusable DAG template (no per-notebook DAG generation)
- KubernetesExecutor only (no CeleryExecutor, no broker)
- No scheduled pipelines (on-demand only via portal)
- No Airflow UI embedding (custom frontend instead)
- Spark client mode (no Spark Operator CRD)
- Backend proxy reuses existing RestTemplate/WebClient pattern
