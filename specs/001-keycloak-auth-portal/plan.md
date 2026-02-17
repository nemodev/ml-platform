# Implementation Plan: Keycloak Auth & Portal Shell

**Branch**: `001-keycloak-auth-portal` | **Date**: 2026-02-16 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-keycloak-auth-portal/spec.md`

## Summary

Implement the foundational authentication and portal shell for the ML
Platform. Keycloak provides OIDC-based SSO, the Angular 17 frontend
provides the portal shell with navigation, and the Spring Boot 3.5.x
backend validates JWTs as an OAuth2 Resource Server. This is the first
feature and prerequisite for all subsequent features (002-006).

Key technical decisions from research:
- Keycloak 26.x with realm config imported via ConfigMap
- `angular-auth-oidc-client` for frontend OIDC with PKCE
- `spring-boot-starter-oauth2-resource-server` for backend JWT validation
- Angular SPA served as static resources from the Spring Boot backend
- Three profiles: dev (mock auth), local (real Keycloak via port-forward),
  production (K8s internal)

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17, Keycloak 26.x,
  angular-auth-oidc-client
**Storage**: PostgreSQL (shared with Keycloak), H2 (dev profile)
**Testing**: JUnit 5 + Spring Security Test (backend),
  Jasmine + Karma (frontend), integration tests against real Keycloak
**Target Platform**: Kubernetes (all components)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Login flow < 30s, token validation < 100ms overhead
**Constraints**: Stateless backend (no server-side sessions), JWT-only auth
**Scale/Scope**: ~20 concurrent users for MVP

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. MVP-First Incremental Delivery | PASS | Feature 001 is independent, deployable, verifiable on K8s |
| II. Infrastructure as Code | PASS | Keycloak deployed via K8s manifests + Helm; realm config in ConfigMap |
| III. Unified Authentication | PASS | Keycloak is the sole IdP; Angular uses OIDC PKCE; Spring Boot validates JWTs |
| IV. Environment Parity | N/A | No notebook/Airflow images in this feature |
| V. Testing at System Boundaries | PASS | Integration test planned: real Keycloak → frontend login → backend JWT validation |
| VI. Simplicity & YAGNI | PASS | Single role, no RBAC; SPA served from backend; no BFF pattern |

**Post-Phase 1 re-check**: All gates still PASS. No violations introduced
during design.

## Project Structure

### Documentation (this feature)

```text
specs/001-keycloak-auth-portal/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # OpenAPI contract
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── build.gradle.kts
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/mlplatform/
    │   │   ├── MlPlatformApplication.java
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java        # OAuth2 Resource Server + JWKS
    │   │   │   ├── DevSecurityConfig.java     # Mock auth for dev profile
    │   │   │   └── CorsConfig.java            # CORS per profile
    │   │   ├── controller/
    │   │   │   ├── AuthController.java        # /auth/userinfo, /auth/logout
    │   │   │   ├── HealthController.java      # /health
    │   │   │   └── PortalController.java      # /portal/sections
    │   │   ├── model/
    │   │   │   └── User.java                  # JPA entity
    │   │   ├── repository/
    │   │   │   └── UserRepository.java        # Spring Data JPA
    │   │   └── service/
    │   │       └── UserService.java           # JWT → User sync
    │   └── resources/
    │       ├── application.yaml               # Production config
    │       ├── application-local.yaml          # Local dev config
    │       ├── application-dev.yaml            # Dev (H2, mock auth)
    │       └── db/migration/
    │           └── V1__create_users.sql       # Flyway migration
    └── test/
        └── java/com/mlplatform/
            ├── controller/
            │   └── AuthControllerTest.java
            └── integration/
                └── AuthFlowIntegrationTest.java

frontend/
├── angular.json
├── package.json
└── src/
    ├── main.ts                               # Bootstrap with OIDC
    ├── environments/
    │   ├── environment.ts                    # Dev defaults
    │   └── environment.prod.ts               # Production config
    └── app/
        ├── app.component.ts                  # Portal shell (nav + layout)
        ├── app.routes.ts                     # Routing with auth guard
        ├── core/
        │   ├── auth/
        │   │   └── oidc-auth.module.ts       # OIDC config (PKCE, silent renew)
        │   ├── guards/
        │   │   └── auth.guard.ts             # canActivate for routes
        │   ├── interceptors/
        │   │   └── auth.interceptor.ts       # Auto-attach Bearer token
        │   └── services/
        │       └── auth.service.ts           # User state, logout
        └── features/
            ├── dashboard/
            │   └── dashboard.component.ts    # Landing page after login
            ├── notebooks/
            │   └── notebooks.component.ts    # Placeholder for feature 002
            ├── experiments/
            │   └── experiments.component.ts  # Placeholder for feature 003
            └── pipelines/
                └── pipelines.component.ts    # Placeholder for feature 005

infrastructure/
├── k8s/
│   ├── namespace.yaml                        # ml-platform namespace
│   ├── keycloak/
│   │   ├── deployment.yaml                   # Keycloak pod
│   │   ├── service.yaml                      # ClusterIP service
│   │   └── configmap.yaml                    # Realm JSON (clients, users)
│   └── postgresql/
│       └── (use Helm chart — bitnami/postgresql)
├── helm/
│   └── keycloak/
│       ├── values.yaml                       # Production values
│       └── local-values.yaml                 # Local dev values
└── scripts/
    └── port-forward.sh                       # Local dev port-forwarding
```

**Structure Decision**: Web application structure (backend + frontend)
matching the prior project pattern. Infrastructure follows constitution
Principle II (IaC) with K8s manifests and Helm charts under
`infrastructure/`.

## Complexity Tracking

No constitution violations to justify. All design choices align with
Principle VI (Simplicity & YAGNI):
- Single user role (no RBAC tables)
- SPA served from backend (no separate Nginx)
- ConfigMap realm import (no Terraform)
- Shared PostgreSQL instance (no per-service databases)
