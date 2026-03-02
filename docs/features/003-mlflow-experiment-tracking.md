# Feature 003: MLflow Experiment Tracking

> Integrates MLflow for experiment tracking with per-user/analysis isolation, embedded UI via iframe with CSS injection, and a backend proxy for notebook-to-MLflow communication.

## What & Why

Data scientists need to track experiments (parameters, metrics, models) as they iterate in notebooks. MLflow is the de facto standard, so rather than building a custom tracking system, we deploy MLflow 3.10.0 as a shared service and integrate it into the platform. The challenge is multi-tenancy: MLflow has no built-in user isolation. We solve this with username/analysis-prefixed experiment names, enforced by the backend proxy. The MLflow UI is embedded in the portal via iframe, customized with CSS injection to remove redundant chrome — this is the second of three iframe patterns in the platform (JupyterLab uses postMessage bridge in Feature 007, Streamlit uses polling startup in Feature 009).

## Architecture

```
Notebook Pod
    ↓ mlflow.log_params(), mlflow.log_model()
    ↓ (MLFLOW_TRACKING_URI → backend proxy)
Backend (/api/v1/mlflow-proxy/**)
    ↓ (auto-prefixes experiment names with username/analysisId)
MLflow Tracking Server (ClusterIP, port 5000)
    ├── PostgreSQL (mlflow database) — metadata
    └── MinIO (ml-platform-mlflow bucket) — artifacts
```

**Key decisions:**

- **User-prefixed experiment naming** — Experiments are stored as `{username}/{analysisId}/{experiment-name}` in MLflow. The backend proxy automatically adds and strips this prefix, so users see unqualified names. This is the simplest isolation mechanism that doesn't require modifying MLflow internals.
- **Backend proxy pattern** — Notebooks send MLflow API calls to the backend (`/api/v1/mlflow-proxy/**`), not directly to MLflow. The proxy rewrites experiment names, adds user prefixes, and filters responses. This centralizes access control without deploying an OAuth2 sidecar.
- **CSS injection for iframe customization** — The Angular component injects `<style>` elements into the MLflow iframe's `contentDocument` to hide the sidebar, header breadcrumbs, and force light theme. This is fragile (breaks if MLflow's DOM structure changes) but avoids modifying MLflow itself.
- **`--serve-artifacts` flag** — MLflow serves artifacts through its own API rather than giving clients direct MinIO access. This simplifies credential management — only MLflow needs MinIO credentials.
- **Network isolation** — MLflow runs as ClusterIP (no external access). All access goes through the backend proxy or in-cluster DNS.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `controller/ExperimentController.java` | CRUD endpoints + wildcard proxy (`/mlflow-proxy/**`) |
| Backend | `service/MlflowService.java` | MLflow REST API client with user prefix management |
| Backend | `config/MlflowConfig.java` | RestTemplate with 5s connect / 60s read timeouts |
| Backend | `service/ModelRegistryService.java` | Model Registry API for Features 006 |
| Frontend | `features/experiments/experiments.component.ts` | Iframe embedding, CSS injection, localStorage presets |
| Frontend | `core/services/experiment.service.ts` | Analysis-scoped HTTP client |
| Infra | `helm/mlflow/values.yaml` | MLflow 3.10.0 config, PostgreSQL, MinIO backend |
| Infra | `helm/mlflow/templates/deployment.yaml` | InitContainer installs psycopg2 + boto3 |

**Proxy rewriting:** `ExperimentController` intercepts specific MLflow API paths. For `experiments/create`, it rewrites the request body to inject the user/analysis prefix. For `get-by-name`, it rewrites the query parameter. For search endpoints, it filters response experiments to only those matching the user's prefix, then strips the prefix from names. Header filtering removes Host, Authorization, and transfer-encoding headers to prevent leaks.

**CSS injection approach:** The experiments component calls `hideMlflowSidebar()`, `hideMlflowHeader()`, and `forceLightTheme()` on iframe load. Each method injects a `<style>` element targeting specific MLflow DOM selectors (`aside`, `[role="complementary"]`, `main > div > div:first-child`). A 500ms retry handles React re-renders. For theme control, the component pre-sets localStorage keys (`_mlflow_dark_mode_toggle_enabled=false`, `mlflow.workflowType_v1=model_training`) before loading the iframe. See `docs/TROUBLESHOOTING.md` items 1–2 for the dark mode fix details.

**MLflow Helm deployment:** The custom Helm chart uses the official MLflow Docker image with an initContainer that installs `psycopg2-binary` and `boto3` into a shared `emptyDir` volume (added to `PYTHONPATH`). This avoids building a custom MLflow image. AWS credentials for MinIO are injected from a K8s Secret.

## Challenges & Solutions

- **MLflow dark mode in embedded iframe** — MLflow 3.10.0's Dubois CSS-in-JS reads `_mlflow_dark_mode_toggle_enabled` from localStorage before `window.matchMedia`. We inject a `<script>` via nginx `sub_filter` that intercepts `localStorage.setItem` and forces light-mode values. Also requires `no-store` cache headers to prevent stale HTML.
- **MLflow CORS in iframe** — MLflow rejects requests with a portal `Origin` header. Solution: nginx strips the Origin header via `proxy_set_header Origin ""`.
- **MLflow API requires max_results** — An empty POST body to `/experiments/search` fails because `max_results` defaults to 0. Backend always includes `"max_results": 100`.
- **MLflow 3.x model URI change** — Model version `source` field changed from S3 paths to `models:/m-{id}` registry URIs. `ModelRegistryService.resolveModelStorageUri()` calls the download-uri API and converts to actual S3 paths. Critical for Feature 006 (KServe needs S3 URIs).

## Limitations

- **CSS selector fragility** — The iframe customization targets specific MLflow DOM structure (`main > div > div:nth-child(2)`, `aside`). MLflow UI updates will break these selectors.
- **No experiment deletion** — The platform doesn't expose MLflow's experiment delete API. Experiments accumulate indefinitely.
- **Case-sensitive prefix filtering** — Production filtering uses exact case-sensitive prefix matching; dev profile uses case-insensitive. Minor inconsistency.
- **No retry logic for transient MLflow failures** — `MlflowService` doesn't implement circuit breakers or retries. If MLflow is briefly unavailable, requests fail immediately.
- **Hardcoded PostgreSQL password in Helm values** — `localdevpassword` in `values.yaml` should be a Secret reference.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| OAuth2 proxy sidecar for MLflow | Adds infrastructure complexity. Backend proxy is simpler and provides finer-grained control over experiment filtering. |
| MLflow with built-in auth | MLflow's auth plugin is experimental and doesn't support Keycloak OIDC natively. |
| Weights & Biases / Neptune | Commercial products add cost and external dependencies for a PoC. |
| Custom tracking backend | Reinventing the wheel. MLflow is the standard, and the real project will likely use it. |
| Direct notebook-to-MLflow access | Can't enforce user isolation without a proxy layer. Notebooks would need admin MinIO credentials. |

## Potential Improvements

- **Server-side CSS/theme configuration** — Use MLflow's `--static-prefix` or custom nginx rewrites instead of client-side CSS injection for more stable iframe customization.
- **Circuit breaker for MLflow calls** — Add resilience4j or Spring Retry to handle transient MLflow/MinIO outages gracefully.
- **Experiment archival/deletion** — Expose MLflow's lifecycle management through the portal for cleanup.
- **Artifact browsing** — Allow users to browse experiment artifacts (plots, models) directly in the portal instead of only through the MLflow iframe.
- **Batch experiment operations** — Support comparing experiments across analyses or exporting metrics for reporting.
