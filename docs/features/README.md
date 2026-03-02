# Feature Documentation

Developer-friendly summaries of the ML Platform PoC's 10 features. Each document covers architecture decisions, key implementation details, challenges, limitations, and what to invest in for a production version.

These are **not** specs. For user stories and acceptance criteria, see `specs/NNN-*/spec.md`.

## Feature Dependency Chain

```
001 Keycloak Auth ─────────────────────────────────────────────┐
    ↓                                                          │
002 JupyterHub Notebooks ──┬───────────────────────────────────┤
    ↓                      ↓                                   │
003 MLflow Experiments   004 Sample Delta Data                 │
    ↓                                                          │
007 Analysis Entity & UI Customization                         │
    ↓                      ↓                  ↓                │
005 Airflow Pipelines    009 Streamlit Viz  008 Custom Images  │
    ↓                                         ↓                │
006 Model Serving                           010 Resource       │
                                                Profiles       │
                                                               │
All features depend on ────────────────────────────────────────┘
```

## Features

| # | Feature | Summary | Doc |
|---|---------|---------|-----|
| 001 | [Keycloak Auth & Portal](001-keycloak-auth-portal.md) | OIDC PKCE auth, user sync, JWT propagation, dev profile mocking | Foundation |
| 002 | [JupyterHub Notebooks](002-jupyterhub-notebook.md) | Workspace lifecycle, named servers, SSO, idle culling | Foundation |
| 003 | [MLflow Experiment Tracking](003-mlflow-experiment-tracking.md) | User-prefixed experiments, backend proxy, CSS iframe injection | Depends on 002 |
| 004 | [Sample Delta Data](004-sample-delta-data.md) | K8s Job provisioning of Delta Lake tables on MinIO | Depends on 002 |
| 005 | [Airflow Notebook Pipeline](005-airflow-notebook-pipeline.md) | KubernetesExecutor, Papermill, immutable snapshots, optional Spark | Depends on 007 |
| 006 | [Model Serving & Inference](006-model-serving-inference.md) | KServe Standard mode, V2 protocol, backend-proxied predictions | Depends on 003, 005 |
| 007 | [Analysis Entity & UI Customization](007-notebook-ui-customization.md) | Analysis scoping, JupyterLab chrome removal, postMessage bridge | Depends on 002, 003 |
| 008 | [Custom Notebook Images](008-custom-notebook-images.md) | Kaniko in-cluster builds, Docker Distribution registry, build queue | Depends on 002 |
| 009 | [Streamlit Visualization](009-streamlit-visualization.md) | jupyter-server-proxy, custom extension, polling startup detection | Depends on 007 |
| 010 | [Resource Profiles](010-notebook-resource-profiles.md) | Config-based CPU/memory profiles, K8s Metrics API, profile switching | Depends on 002, 008 |

## Cross-Cutting Patterns

These patterns repeat across features. Each is introduced once and referenced from subsequent docs:

**Dev profile mocking** (introduced in [001](001-keycloak-auth-portal.md)) — Every external-service integration checks `isDevProfile()` and returns mock data when running locally without infrastructure. This allows frontend development without Keycloak, JupyterHub, MLflow, Airflow, or KServe.

**Analysis-scoped architecture** (introduced in [007](007-notebook-ui-customization.md)) — The Analysis entity groups workspaces, experiments, and visualizations under a single named context. All workspace and experiment APIs are scoped to `/api/v1/analyses/{analysisId}/...`.

**Three iframe embedding patterns** — Each embedded UI uses a different integration strategy:
- JupyterLab: postMessage command bridge via Comlink RPC ([007](007-notebook-ui-customization.md))
- MLflow: CSS injection to hide native chrome ([003](003-mlflow-experiment-tracking.md))
- Streamlit: polling startup detection with status state machine ([009](009-streamlit-visualization.md))

**Kubernetes Java Client** — Shared pattern for managing K8s resources from the backend: InferenceService CRDs ([006](006-model-serving-inference.md)), Kaniko Jobs and ConfigMaps ([008](008-custom-notebook-images.md)), and pod metrics queries ([010](010-notebook-resource-profiles.md)).

**Workspace lifecycle state machine** (introduced in [002](002-jupyterhub-notebook.md)) — PENDING → RUNNING → IDLE → STOPPED/FAILED. Extended by custom images ([008](008-custom-notebook-images.md)), Streamlit process lifecycle ([009](009-streamlit-visualization.md)), and profile switching ([010](010-notebook-resource-profiles.md)).

**Single notebook image** (introduced in [002](002-jupyterhub-notebook.md)) — One Docker image serves as JupyterHub server, Airflow pipeline worker, Spark executor, and data provisioning container. Custom images ([008](008-custom-notebook-images.md)) extend this base.

## Key References

- [ARCHITECTURE.md](../ARCHITECTURE.md) — System diagrams, data model, API reference
- [TROUBLESHOOTING.md](../TROUBLESHOOTING.md) — 13 documented deployment issues and fixes
- [PROJECT_REFERENCE.md](../../PROJECT_REFERENCE.md) — Comprehensive project reference
- `specs/NNN-*/spec.md` — Formal specifications with user stories and acceptance criteria
