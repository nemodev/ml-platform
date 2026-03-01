# Implementation Plan: Streamlit Visualization

**Branch**: `009-streamlit-visualization` | **Date**: 2026-02-28 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/009-streamlit-visualization/spec.md`

## Summary

Add a "Visualization" tab to the analysis layout that embeds Streamlit apps running inside the user's notebook pod. The system detects Streamlit Python files in a dedicated `visualize/` folder, serves the selected app via `jupyter-server-proxy`, and displays it in an iframe. A lightweight Jupyter server extension manages Streamlit process lifecycle within the pod. The backend proxies all visualization API calls through JupyterHub. Includes a sample dashboard using the California Housing dataset.

Streamlit and its infrastructure dependencies are treated as **foundational platform packages** — they are baked into the base notebook image and protected from custom image builds via a pip constraints file. This ensures the Visualization feature works regardless of which custom image a user selects.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend), Python 3.11 (Jupyter extension + Streamlit apps)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17, Streamlit >=1.33.0, jupyter-server-proxy >=4.0.0
**Storage**: None (no database tables; all state is ephemeral in-memory within notebook pods)
**Testing**: Manual E2E verification on Kubernetes (existing pattern for infrastructure-heavy features)
**Target Platform**: Kubernetes (same as existing platform)
**Project Type**: Web application (backend + frontend + notebook image)
**Performance Goals**: Streamlit app visible in <10 seconds after tab click; file switching in <5 seconds
**Constraints**: One Streamlit process per workspace at a time; process stays alive during workspace session
**Scale/Scope**: Single-user per workspace; ~1-10 Streamlit files per workspace

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. MVP-First Incremental Delivery | PASS | Independent feature, deployable and verifiable on K8s. All dependencies (002, 004, 007) are completed. |
| II. Infrastructure as Code | PASS | Notebook image changes are in Dockerfile/requirements.txt under `infrastructure/`. No manual cluster config. |
| III. Unified Authentication | PASS | Streamlit is accessed through JupyterHub proxy chain (inherits Keycloak SSO). No separate auth. CORS and XSRF disabled on Streamlit since auth boundary is JupyterHub. |
| IV. Environment Parity | PASS | Streamlit is added to the same notebook image used everywhere. No env-specific differences. |
| V. Testing at System Boundaries | PASS | Verification via E2E quickstart scenarios on K8s deployment. Tests proxy chain, iframe embedding, process lifecycle. |
| VI. Production-Quality Within Scope | PASS | Proper service layer in backend, well-structured Angular component, Jupyter extension following standard patterns. No shortcuts. |

**Post-design re-check**: PASS — No database tables, no new K8s resources beyond notebook image changes. Architecture follows existing proxy-through-backend pattern.

## Project Structure

### Documentation (this feature)

```text
specs/009-streamlit-visualization/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output (no DB tables)
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/mlplatform/
│   ├── controller/
│   │   └── VisualizationController.java     # REST endpoints (proxies to notebook extension)
│   ├── service/
│   │   └── StreamlitService.java            # Business logic, proxy calls to notebook pod
│   └── dto/
│       ├── StreamlitFileDto.java            # File info record
│       ├── StreamlitFileListDto.java        # File list wrapper
│       ├── StreamlitStatusDto.java          # Process status record
│       └── StartStreamlitRequestDto.java    # Start request record

frontend/
├── src/app/
│   ├── features/
│   │   ├── analyses/
│   │   │   └── analysis-layout.component.ts # Modified: add Visualization tab
│   │   └── visualization/
│   │       ├── visualization.component.ts   # New: main component
│   │       ├── visualization.component.html # New: template
│   │       └── visualization.component.scss # New: styles
│   └── core/services/
│       └── visualization.service.ts         # New: HTTP service

infrastructure/
├── docker/notebook-image/
│   ├── Dockerfile                           # Modified: install extension, add constraints file
│   ├── requirements.txt                     # Modified: add streamlit, jupyter-server-proxy
│   ├── platform-constraints.txt             # New: pins platform packages to protect from custom builds
│   ├── ml_platform_streamlit/               # New: Jupyter server extension
│   │   ├── __init__.py                      # Extension entry point
│   │   └── handlers.py                      # REST handlers for file scan + process mgmt
│   └── visualize/
│       └── sample_dashboard.py              # New: sample Streamlit app

backend/
├── src/main/java/com/mlplatform/service/
│   └── ImageBuildService.java               # Modified: add --constraint flag to pip install
```

**Structure Decision**: Follows the existing web application structure (backend + frontend + infrastructure). The Jupyter server extension is a new Python package within the notebook image build context. No new Helm charts or K8s manifests needed — changes are confined to the notebook image, backend API, and frontend component.

## Architecture

### Request Flow

```
User clicks Visualization tab
  → Angular VisualizationComponent
    → GET /api/v1/analyses/{id}/visualizations/files
      → Spring Boot VisualizationController
        → StreamlitService
          → JupyterHub Proxy WebClient
            → Notebook Pod (Jupyter Server Extension)
              → Scans visualize/ directory
              → Returns file list
    ← File list displayed (dropdown if multiple)

User selects file / auto-selects first
  → POST /api/v1/analyses/{id}/visualizations/start {filePath}
    → Spring Boot → JupyterHub Proxy → Notebook Pod Extension
      → Stops previous Streamlit (if any)
      → Starts: streamlit run visualize/app.py --server.port {port} ...
      → Returns {status: "running", port: 8501, url: "/user/.../proxy/8501/"}
    ← Frontend loads iframe with proxy URL

Streamlit iframe
  → Browser requests /user/{username}/{serverName}/proxy/{port}/
    → nginx → JupyterHub CHP → Notebook Pod → jupyter-server-proxy → Streamlit
  ← Streamlit HTML/JS/WebSocket responses
```

### Platform Package Protection (Custom Image Resilience)

Streamlit and its infrastructure dependencies must survive custom image builds (feature 008). The protection mechanism:

1. **Base image** includes `streamlit`, `jupyter-server-proxy`, and `ml_platform_streamlit` in `requirements.txt`
2. **Constraints file** (`/opt/ml-platform/platform-constraints.txt`) is baked into the base image, pinning all platform-critical packages:
   ```
   # Platform infrastructure — do not override
   jupyterlab==4.3.4
   jupyterhub==5.4.3
   notebook==7.3.2
   jupyter-server-proxy>=4.0.0
   jupyter-iframe-commands==0.3.0
   streamlit>=1.33.0
   mlflow==3.10.0
   pyspark==4.0.1
   # ... all other base requirements
   ```
3. **Kaniko Dockerfile generation** (`ImageBuildService.java`) is modified to use the constraints file:
   ```dockerfile
   FROM ml-platform/notebook-base:python-3.11
   USER root
   COPY requirements.txt /tmp/custom-requirements.txt
   RUN pip install --no-cache-dir \
       --constraint /opt/ml-platform/platform-constraints.txt \
       -r /tmp/custom-requirements.txt
   USER jovyan
   ```
4. **Behavior**: If a user package requires an incompatible version of a platform dependency, the build **fails with a clear pip conflict error** rather than silently breaking platform features. The user can then adjust their package versions.

This protects ALL platform packages (not just Streamlit) and is a cross-feature improvement to the custom image build process.

### Jupyter Server Extension API

Hosted at `{notebook_server_base}/api/streamlit/` within the notebook pod:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/streamlit/files` | GET | Scan `visualize/` for Streamlit `.py` files |
| `/api/streamlit/start` | POST | Start Streamlit on `{filePath}`, return port |
| `/api/streamlit/stop` | POST | Kill running Streamlit process |
| `/api/streamlit/status` | GET | Return process status and port |

### Streamlit Launch Configuration

```bash
streamlit run {file} \
  --server.headless true \
  --server.port {port} \
  --server.address 0.0.0.0 \
  --server.enableCORS false \
  --server.enableXsrfProtection false \
  --browser.gatherUsageStats false
```

Port selection: Start at 8501, increment if occupied. jupyter-server-proxy handles routing to any port.

### Frontend Component Strategy

| Tab | DOM Strategy | Reason |
|-----|-------------|--------|
| Notebooks | Always in DOM (hidden) | Preserve JupyterLab iframe + kernel state |
| Experiments | Destroy/recreate | Fresh MLflow data each visit |
| Visualization | Always in DOM (hidden) | Preserve Streamlit iframe + widget state |

## Complexity Tracking

No constitution violations. No complexity justifications needed.
