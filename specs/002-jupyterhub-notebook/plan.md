# Implementation Plan: JupyterHub Notebook Embedding

**Branch**: `002-jupyterhub-notebook` | **Date**: 2026-02-16 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-jupyterhub-notebook/spec.md`

## Summary

Embed JupyterHub notebook servers into the ML Platform portal. Users
click "Notebooks" in the portal navigation (built in feature 001) and
get an interactive JupyterLab environment loaded in an iframe, with
SSO passthrough via Keycloak. Each user gets their own isolated
notebook server with persistent storage and pre-installed ML libraries.

Key technical decisions from research:
- Z2JH Helm Chart v4.3.2 for JupyterHub deployment on Kubernetes
- `GenericOAuthenticator` for Keycloak SSO integration
- Custom Docker image (based on `jupyter/scipy-notebook`) with full
  ML stack (scikit-learn, scipy, seaborn, plotly, etc.)
- Spring Boot backend proxies JupyterHub REST API for workspace
  lifecycle management
- Iframe embedding with CSP `frame-ancestors` for security
- Dynamic PVC (10Gi) per user for persistent workspace storage
- 30-minute idle culler timeout

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend),
  Python 3.11 (notebook image)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17,
  JupyterHub (Z2JH Helm v4.3.x), Keycloak 26.x,
  angular-auth-oidc-client
**Storage**: PostgreSQL (workspace table + JupyterHub DB),
  Kubernetes PVC (10Gi per user for notebook files)
**Testing**: JUnit 5 (backend), Jasmine + Karma (frontend),
  integration tests against real JupyterHub + Keycloak
**Target Platform**: Kubernetes (all components)
**Project Type**: Web application (backend + frontend + infrastructure)
**Performance Goals**: Warm start < 60s, cold start < 120s,
  iframe load seamless after server running
**Constraints**: Single "Exploratory" profile only, no GPU,
  stateless backend, JupyterHub admin API token for backend
**Scale/Scope**: ~20 concurrent users, each with 10Gi PVC

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. MVP-First Incremental Delivery | PASS | Feature 002 depends only on completed feature 001; independently deployable and verifiable on K8s |
| II. Infrastructure as Code | PASS | JupyterHub deployed via Helm chart with values files; Dockerfile for custom image; all K8s manifests version-controlled |
| III. Unified Authentication | PASS | JupyterHub uses GenericOAuthenticator against same Keycloak realm; no separate auth system |
| IV. Environment Parity | PASS | Custom notebook image will be shared between JupyterHub and future Airflow workers (feature 005) |
| V. Testing at System Boundaries | PASS | Integration tests planned: portal login → workspace launch → JupyterHub API → iframe load |
| VI. Simplicity & YAGNI | PASS | Single compute profile, no profile selector UI, no GPU support, no max session age |

**Post-Phase 1 re-check**: All gates still PASS. No violations
introduced during design.

## Project Structure

### Documentation (this feature)

```text
specs/002-jupyterhub-notebook/
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
├── build.gradle.kts                          # Add WebClient dependency
└── src/
    ├── main/
    │   ├── java/com/mlplatform/
    │   │   ├── config/
    │   │   │   └── JupyterHubConfig.java     # JupyterHub URL + API token config
    │   │   ├── controller/
    │   │   │   └── WorkspaceController.java  # /workspaces/* endpoints
    │   │   ├── dto/
    │   │   │   ├── ComputeProfileDto.java    # Profile response DTO
    │   │   │   ├── WorkspaceStatusDto.java   # Status response DTO
    │   │   │   └── WorkspaceUrlDto.java      # URL response DTO
    │   │   ├── model/
    │   │   │   └── Workspace.java            # JPA entity
    │   │   ├── repository/
    │   │   │   └── WorkspaceRepository.java  # Spring Data JPA
    │   │   └── service/
    │   │       ├── JupyterHubService.java    # JupyterHub REST API client
    │   │       └── WorkspaceService.java     # Workspace lifecycle orchestration
    │   └── resources/
    │       ├── application.yaml              # Add jupyterhub config section
    │       ├── application-local.yaml        # Local JupyterHub URL
    │       ├── application-dev.yaml          # Mock workspace service
    │       └── db/migration/
    │           └── V2__create_workspaces.sql # Workspace table migration
    └── test/
        └── java/com/mlplatform/
            └── controller/
                └── WorkspaceControllerTest.java

frontend/
└── src/
    └── app/
        ├── features/
        │   └── notebooks/
        │       ├── notebooks.component.ts    # Replace placeholder with workspace UI
        │       ├── notebooks.component.html  # Template with iframe + status
        │       └── notebooks.component.scss  # Iframe full-height styling
        └── core/
            └── services/
                └── workspace.service.ts      # HTTP client for workspace API

infrastructure/
├── docker/
│   └── notebook-image/
│       ├── Dockerfile                        # Custom JupyterLab image
│       └── requirements.txt                  # Python ML dependencies
├── helm/
│   └── jupyterhub/
│       ├── Chart.yaml                        # Z2JH dependency
│       ├── values.yaml                       # Production values
│       └── local-values.yaml                 # Local dev values
└── k8s/
    └── keycloak/
        └── configmap.yaml                    # Add jupyterhub client
```

**Structure Decision**: Extends the web application structure from
feature 001. New backend endpoints, frontend component update,
infrastructure additions for JupyterHub Helm chart and custom Docker
image. Follows the same package structure (`com.mlplatform`).

## Complexity Tracking

No constitution violations to justify. All design choices align with
Principle VI (Simplicity & YAGNI):
- Single compute profile (no profile selector)
- Backend proxies JupyterHub API (no direct frontend-to-JupyterHub)
- Iframe embedding (no reverse proxy or custom integration)
- Dynamic PVC with cluster defaults (no custom StorageClass)
- Shared PostgreSQL (no separate JupyterHub database server)
