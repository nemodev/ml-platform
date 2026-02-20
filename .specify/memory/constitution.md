<!--
Sync Impact Report
===================
Version change: 1.0.0 → 2.0.0
Bump rationale: MAJOR — Principle VI redefined from "Simplicity &
YAGNI" (always-minimal approach) to "Production-Quality Within Scope"
(balanced engineering with best practices). This is an incompatible
redefinition: prior guidance actively discouraged proper patterns in
favor of the quickest path; new guidance requires best-practice
implementations within bounded scope.

Modified principles:
  - Principle VI: "Simplicity & YAGNI" → "Production-Quality Within
    Scope" (redefined from minimal-first to balanced engineering)

Added sections: None
Removed sections: None

Templates checked:
  - .specify/templates/plan-template.md ✅ — Constitution Check
    section (line 30-34) uses generic gate reference; no updates needed
  - .specify/templates/spec-template.md ✅ — User Scenarios and
    Success Criteria sections are principle-agnostic; no updates needed
  - .specify/templates/tasks-template.md ✅ — Phase structure and
    implementation strategy are compatible; no updates needed
  - .specify/templates/commands/*.md — No command files exist

Follow-up TODOs: None
-->

# ML Platform Constitution

## Core Principles

### I. MVP-First Incremental Delivery

- Each feature MUST be scoped, specified, and implemented as an
  independent increment using speckit (`/speckit.specify` →
  `/speckit.plan` → `/speckit.tasks` → `/speckit.implement`).
- A feature MUST be deployable and verifiable on Kubernetes before
  the next feature is started.
- The MVP scope defined in PROJECT.md is authoritative. Features
  outside that scope MUST NOT be implemented unless the scope is
  formally amended.
- No feature may depend on another feature that has not yet been
  completed and verified.

**Rationale**: The prior project attempt failed by designing and
implementing all features simultaneously. Incremental delivery
ensures each piece works before building on top of it.

### II. Infrastructure as Code

- All Kubernetes manifests, Helm charts, and cluster configuration
  MUST be version-controlled in the repository under `infrastructure/`.
- No manual `kubectl apply` or cluster configuration is permitted
  in production. All changes MUST flow through declarative manifests.
- Helm values files MUST separate environment-specific configuration
  (dev, staging, prod) from shared defaults.
- Secrets MUST NOT be committed to the repository. Secrets MUST be
  managed via Kubernetes Secrets, sealed-secrets, or an external
  secrets manager.

**Rationale**: Reproducible infrastructure is essential for a
multi-component platform running Keycloak, JupyterHub, MLflow,
Airflow, Spark, and KServe on Kubernetes.

### III. Unified Authentication

- Keycloak MUST be the single identity provider for the entire
  platform. No component may implement its own authentication.
- The Angular frontend MUST use OIDC Authorization Code Flow with
  PKCE to obtain JWTs from Keycloak.
- The Spring Boot backend MUST validate JWTs as an OAuth2 Resource
  Server. It MUST NOT maintain its own user/session store.
- JupyterHub MUST use `GenericOAuthenticator` configured against
  Keycloak so users get single sign-on into notebooks.
- MLflow and Airflow MUST accept the same Keycloak-issued tokens,
  either directly or proxied through the Spring Boot backend.
- Embedded iframes (JupyterLab, MLflow UI) MUST configure
  `Content-Security-Policy: frame-ancestors` to allow embedding
  from the frontend domain only.

**Rationale**: A unified auth chain from frontend through every
backend component is a prerequisite for multi-user security and
auditability. Fragmented auth creates security gaps and poor UX.

### IV. Environment Parity

- The Docker image used for interactive JupyterHub notebook servers
  MUST be the same image used for Airflow Papermill workers.
- Python dependencies, Spark configuration, and system libraries
  MUST be identical between development and production execution
  environments.
- Environment-specific differences (endpoints, credentials, resource
  limits) MUST be injected via environment variables or mounted
  config, never baked into images.

**Rationale**: Environment parity prevents "works on my machine"
failures when notebooks transition from interactive development
to Airflow production pipelines.

### V. Testing at System Boundaries

- Integration tests verifying cross-component communication MUST
  take priority: auth token propagation, API contract validation,
  Kubernetes resource creation, and S3/Delta Lake connectivity.
- Each feature MUST include at least one end-to-end verification
  that exercises the real Kubernetes deployment (not just mocked
  unit tests).
- Unit tests are valuable for business logic (e.g., DAG
  construction, data transformations) but MUST NOT be used as a
  substitute for integration testing in an infrastructure-heavy
  platform.
- Flaky tests MUST be quarantined and fixed before new feature
  work begins.

**Rationale**: In a platform composed of many independently
deployed services, the highest-risk failures occur at integration
boundaries, not within individual components.

### VI. Production-Quality Within Scope

- The MVP scope defined in PROJECT.md remains the boundary. Features
  outside that scope MUST NOT be implemented unless formally amended.
- Within the defined scope, every implementation MUST follow industry
  best practices and established patterns for the technology in use.
  Quick-and-dirty shortcuts that sacrifice code quality, proper error
  handling, or maintainability are not acceptable.
- Code MUST be written as if it will be directly transferred to the
  production project on a remote server. This means:
  - Proper separation of concerns and layered architecture.
  - Meaningful error handling with appropriate exception hierarchies
    and user-facing error messages.
  - Configuration externalized correctly (not hardcoded values or
    placeholder workarounds).
  - Consistent naming conventions and code organization that follow
    Spring Boot, Angular, and Kubernetes community standards.
- Abstractions and patterns MUST be introduced when they serve the
  current implementation. A service layer, DTOs, or a repository
  pattern is warranted when the code has concrete, present-day
  consumers — not only when anticipated by future requirements.
- The decision between a simple and a structured approach MUST be
  evaluated on transferability: choose the approach that a team
  inheriting this codebase would recognize as standard practice and
  could extend without refactoring the foundation.
- Speculative features (GPU profiles, advanced RBAC tiers,
  scale-to-zero, canary deployments, multi-cluster support) remain
  out of scope until explicitly scoped.

**Rationale**: This project is transitioning from proof-of-concept
to a refined, transferable codebase. Cutting corners creates
technical debt that costs more to fix during transfer than it saves
during initial development. The goal is a limited but solid
foundation — not a sprawling but fragile one.

## Technology Stack Constraints

The following versions are authoritative for the MVP. Version
changes require a constitution amendment.

| Component | Version | Role |
|-----------|---------|------|
| Keycloak | Latest | Identity provider (OIDC) |
| Angular | 17 | Frontend SPA |
| Spring Boot | 3.5.x | Backend REST API / orchestrator |
| JupyterHub | Latest | Multi-user notebook server on K8s |
| KServe | Latest | Model serving (inference endpoints) |
| MLflow | Latest | Experiment tracking and model registry |
| Airflow | 2.10.x | Workflow orchestration (Papermill) |
| Spark | 4.0.1 | Distributed data processing |
| Delta Lake | 4.0.x | ACID table format on S3 |

- "Latest" means the most recent stable release at time of
  implementation. The actual version MUST be pinned in Helm
  charts or Docker image tags once selected.
- All components run on Kubernetes. No component may require a
  separate deployment target.

## Development Workflow

- **Branch strategy**: Feature branches from `main`. Each branch
  corresponds to one speckit feature. Branches MUST be short-lived.
- **Commit discipline**: Conventional commit format is required
  (`feat:`, `fix:`, `infra:`, `test:`, `docs:`).
- **Feature delivery**: Each feature follows the speckit workflow:
  specify → plan → tasks → implement. No implementation without
  a completed spec and plan.
- **Deployment verification**: After merging a feature, the full
  platform MUST be deployable to a Kubernetes cluster and pass
  integration checks before starting the next feature.
- **Code review**: Every change MUST be reviewed against
  constitution principles before merge.

## Governance

- This constitution is the authoritative source for development
  standards on the ML Platform project. All code, infrastructure,
  and architectural decisions MUST comply with these principles.
- Amendments require: (1) a written proposal documenting the
  change and rationale, (2) review and approval, and (3) a
  migration plan if the amendment affects existing work.
- Version numbering follows semantic versioning: MAJOR for
  principle removals or incompatible redefinitions, MINOR for
  new principles or material expansions, PATCH for clarification
  or wording fixes.
- The MVP scope in PROJECT.md and this constitution MUST remain
  aligned. Any scope change requires a corresponding constitution
  review.

**Version**: 2.0.0 | **Ratified**: 2026-02-16 | **Last Amended**: 2026-02-20
