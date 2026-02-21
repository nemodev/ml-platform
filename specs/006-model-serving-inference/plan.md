# Implementation Plan: Model Serving & Inference

**Branch**: `006-model-serving-inference` | **Date**: 2026-02-16 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/006-model-serving-inference/spec.md`

## Summary

Add KServe-based model serving with MLflow Model Registry integration.
Users register trained models in the MLflow Model Registry from
notebooks, deploy them as KServe InferenceServices via the portal, and
send inference requests through the backend proxy. KServe runs in raw
deployment mode (no Knative/Istio) using the mlserver runtime for the
V2 inference protocol. Pipeline DAGs from feature 005 can call
inference endpoints directly within the cluster.

Key technical decisions from research:
- KServe in raw deployment mode (plain K8s Deployments + Services)
- MLflow Model Registry for model versioning and artifact resolution
- MLServer runtime with V2 inference protocol
- Backend proxy for inference requests (auth gateway)
- K8s Java client for InferenceService CRD management
- Dedicated `ml-platform-serving` namespace for inference pods
- KServe S3 secret for MinIO model artifact access
- No Knative, no Istio, no scale-to-zero in MVP

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend),
  Python 3.11 (notebooks for model registration)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17, KServe 0.16.x,
  MLflow 3.10.0 (Model Registry), Kubernetes Java Client
**Storage**: PostgreSQL (`model_deployments` table),
  MinIO (model artifacts via MLflow)
**Testing**: JUnit 5 (backend), Jasmine + Karma (frontend),
  integration tests with KServe + MLflow
**Target Platform**: Kubernetes (all components)
**Project Type**: Web application (backend + frontend + infrastructure)
**Performance Goals**: Endpoint ready < 5 min (SC-001), inference
  response < 2s (SC-002)
**Constraints**: Raw deployment mode, scikit-learn models only for MVP,
  single replica per endpoint, no autoscaling
**Scale/Scope**: ~20 concurrent users, inference concurrency limited
  by pod resources

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. MVP-First Incremental Delivery | PASS | Feature 006 depends on 003+005; independently deployable and verifiable |
| II. Infrastructure as Code | PASS | KServe manifests, S3 Secret, ServiceAccount, all version-controlled |
| III. Unified Authentication | PASS | Users authenticate via Keycloak JWT; backend proxies inference with auth |
| IV. Environment Parity | N/A | No new shared images; KServe uses its own runtime images |
| V. Testing at System Boundaries | PASS | Integration tests: portal → backend → KServe → MLServer → MinIO |
| VI. Simplicity & YAGNI | PASS | Raw mode, single replica, no autoscaling, no canary, no Knative |

**Post-Phase 1 re-check**: All gates still PASS. No violations
introduced during design.

## Project Structure

### Documentation (this feature)

```text
specs/006-model-serving-inference/
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
    │   │   │   └── KServeConfig.java              # KServe namespace + K8s client
    │   │   ├── controller/
    │   │   │   ├── ModelController.java            # /models/* endpoints (registry)
    │   │   │   └── ServingController.java          # /serving/* endpoints (deploy/infer)
    │   │   ├── dto/
    │   │   │   ├── RegisteredModelInfoDto.java
    │   │   │   ├── ModelVersionInfoDto.java
    │   │   │   ├── DeployModelRequest.java
    │   │   │   ├── DeploymentInfoDto.java
    │   │   │   ├── DeploymentDetailDto.java
    │   │   │   ├── PredictionRequestDto.java
    │   │   │   └── PredictionResponseDto.java
    │   │   ├── model/
    │   │   │   └── ModelDeployment.java            # JPA entity
    │   │   ├── repository/
    │   │   │   └── ModelDeploymentRepository.java  # Spring Data JPA
    │   │   └── service/
    │   │       ├── ModelRegistryService.java       # MLflow Model Registry API client
    │   │       ├── KServeService.java              # K8s API for InferenceService CRDs
    │   │       └── ServingService.java             # Orchestration: deploy, status, infer
    │   └── resources/
    │       ├── application.yaml                    # Add kserve config section
    │       ├── application-local.yaml              # Local KServe namespace
    │       ├── application-dev.yaml                # Mock KServe + registry responses
    │       └── db/migration/
    │           └── V006__create_model_deployments.sql
    └── test/
        └── java/com/mlplatform/
            └── controller/
                ├── ModelControllerTest.java
                └── ServingControllerTest.java

frontend/
└── src/
    └── app/
        ├── features/
        │   └── models/
        │       ├── models.component.ts             # Registered model list
        │       ├── models.component.html
        │       ├── models.component.scss
        │       ├── deploy-dialog/
        │       │   ├── deploy-dialog.component.ts  # Deploy version form
        │       │   ├── deploy-dialog.component.html
        │       │   └── deploy-dialog.component.scss
        │       ├── deployments/
        │       │   ├── deployments.component.ts    # Active endpoints list
        │       │   ├── deployments.component.html
        │       │   └── deployments.component.scss
        │       └── predict-dialog/
        │           ├── predict-dialog.component.ts # Test inference form
        │           ├── predict-dialog.component.html
        │           └── predict-dialog.component.scss
        └── core/
            └── services/
                ├── model.service.ts                # HTTP client for model registry
                └── serving.service.ts              # HTTP client for serving API

infrastructure/
└── k8s/
    └── kserve/
        ├── serving-namespace.yaml                  # ml-platform-serving namespace
        ├── s3-secret.yaml                          # MinIO credentials for KServe
        └── service-account.yaml                    # ServiceAccount for model download
```

**Structure Decision**: Extends the web application structure from
features 001-005. Two new backend controllers (models + serving), three
new services (registry, KServe, serving orchestration), four new
frontend components, and KServe infrastructure manifests. No Helm chart
for KServe — installed via upstream manifests.

## Complexity Tracking

No constitution violations to justify. All design choices align with
Principle VI (Simplicity & YAGNI):
- Raw deployment mode (no Knative/Istio)
- Single replica per endpoint (no autoscaling)
- scikit-learn models only (no multi-framework in MVP)
- No canary deployments or A/B testing
- No scale-to-zero
- Backend proxy for inference (no service mesh auth)
- K8s Java client for CRD management (no custom operator)
