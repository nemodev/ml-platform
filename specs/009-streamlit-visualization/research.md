# Research: Streamlit Visualization

**Feature Branch**: `009-streamlit-visualization`
**Date**: 2026-02-28

## Decision 1: Streamlit Serving Architecture

**Decision**: Use `jupyter-server-proxy` to proxy Streamlit running inside the notebook pod, managed by a lightweight Jupyter server extension.

**Rationale**: jupyter-server-proxy is the standard mechanism for proxying arbitrary ports from JupyterHub notebook servers. The existing platform nginx config already forwards `/user/` routes with WebSocket support and 24h timeout — no infrastructure changes needed. Streamlit's WebSocket-based protocol is fully supported.

**Alternatives considered**:
- **K8s exec to start Streamlit**: Fragile, no process lifecycle tracking, requires K8s API calls for each operation.
- **Jupyter Terminals API**: Hard to track process state, no clean start/stop semantics.
- **Sidecar container**: Over-engineered for a per-workspace process; adds deployment complexity.
- **Standalone K8s Deployment per user**: Out of scope per spec (apps only run within workspace pod).

**Key configuration**:
```
streamlit run app.py \
  --server.headless true \
  --server.port {port} \
  --server.address 0.0.0.0 \
  --server.enableCORS false \
  --server.enableXsrfProtection false \
  --browser.gatherUsageStats false
```

---

## Decision 2: Jupyter Server Extension for Process Management

**Decision**: Create a minimal Jupyter server extension (`ml_platform_streamlit`) that provides REST endpoints to list Streamlit files, start/stop the Streamlit process, and report status. This runs inside the notebook pod alongside JupyterLab.

**Rationale**: The extension runs where the files and processes live (the notebook pod), can manage subprocesses directly, and is automatically accessible through the JupyterHub proxy chain. The backend proxies requests to these endpoints following the existing pattern where all API calls flow through the Spring Boot backend.

**Alternatives considered**:
- **Frontend calls notebook server directly**: Breaks the architectural pattern where all API calls go through the backend. No authorization checks.
- **Backend scans files and manages processes remotely**: Requires K8s exec or terminal API. More fragile than local process management.

**Extension endpoints**:
- `GET /api/streamlit/files` — Scan `visualize/` for Python files with Streamlit imports
- `POST /api/streamlit/start` — Start Streamlit on a specified file, return port
- `POST /api/streamlit/stop` — Kill running Streamlit process
- `GET /api/streamlit/status` — Return running state, active file, port

---

## Decision 3: Iframe Embedding via jupyter-server-proxy

**Decision**: Embed Streamlit in the Visualization tab iframe using the jupyter-server-proxy URL pattern: `/user/{username}/{serverName}/proxy/{port}/`.

**Rationale**: This URL is same-origin with the existing JupyterLab iframe, so no CSP changes are needed. The existing `frame-ancestors` policy in JupyterHub's tornado settings already allows embedding. WebSocket proxying is supported. No nginx config changes required.

**Key settings for embedding**:
- `server.enableCORS=false` — Auth handled by JupyterHub; CORS unnecessary
- `server.enableXsrfProtection=false` — Breaks in iframe context; JupyterHub provides auth boundary
- `absolute_url=False` (jupyter-server-proxy default) — Strips proxy prefix, Streamlit sees requests at `/`
- No `baseUrlPath` needed when proxy prefix is stripped

**Known gotchas**:
- Streamlit `>=1.33.0` required for iframe download button fix (Issue #8524)
- jupyter-server-proxy `>=4.0.0` for reliable WebSocket support
- Default 5-second proxy timeout is too low for Streamlit startup; increase to 30s

---

## Decision 4: File Detection Strategy

**Decision**: Scan only the `visualize/` directory at workspace root. Read each `.py` file and check for `import streamlit` or `from streamlit` patterns via simple text search.

**Rationale**: A dedicated folder avoids scanning virtualenvs, cache directories, or utility modules. Text-based detection is fast and safe (no code execution). The `visualize/` convention provides clear organization for users.

**Alternatives considered**:
- **Recursive workspace scan**: Risk of false positives in `.venv/`, `__pycache__/`, installed packages. Slow for large workspaces.
- **File extension convention (e.g., `*.st.py`)**: Non-standard, would confuse users and break IDE support.
- **AST parsing**: Over-engineered for import detection. Slower than text search.

---

## Decision 5: Frontend Tab Preservation Strategy

**Decision**: Keep the Visualization component in the DOM (hidden via `display:none`) when switching to other tabs, same as the Notebooks component. This preserves the Streamlit iframe state.

**Rationale**: Per the spec clarification, the Streamlit process stays alive during the workspace session. Keeping the iframe in DOM means the user returns to exactly where they left off — no reconnection delay, no state loss. This follows the existing Notebooks tab pattern.

**Alternatives considered**:
- **Destroy and recreate on each visit** (like Experiments tab): Would force Streamlit to reconnect WebSocket and potentially re-render the entire app. Poor UX for interactive dashboards.

---

## Decision 6: Package Dependencies

**Decision**: Add to notebook image `requirements.txt`:
- `streamlit>=1.33.0` — Core visualization framework (>=1.33.0 for iframe download fix)
- `jupyter-server-proxy>=4.0.0` — Port proxying from notebook pods

**Rationale**: Both are well-maintained, widely-used packages. Streamlit pulls in its own dependencies (altair, plotly, etc.) which complement the existing data science stack. jupyter-server-proxy is the standard JupyterHub extension for this purpose.

**Size impact**: Streamlit adds ~100MB to the notebook image (mostly due to bundled frontend assets and transitive dependencies). Acceptable given the base image is already ~2GB.

---

## Decision 7: Platform Package Protection for Custom Images

**Decision**: Introduce a pip constraints file (`/opt/ml-platform/platform-constraints.txt`) in the base notebook image and modify the Kaniko Dockerfile generation to use `pip install --constraint` when building custom images.

**Rationale**: Streamlit and jupyter-server-proxy are foundational platform packages that must survive custom image builds (feature 008). Without protection, a user's package could pull in conflicting transitive dependencies that break Streamlit or other platform tooling. The constraints file makes custom builds fail loudly on conflict rather than silently breaking platform features.

**Alternatives considered**:
- **Re-install platform packages after user packages**: Fragile — can still leave broken transitive dependencies. Doesn't make conflicts visible.
- **Separate virtualenv for platform packages**: Over-engineered, breaks import paths for user code that needs to access both Streamlit and user packages (e.g., `import streamlit` + `import my_custom_lib`).
- **Volume-mount Streamlit separately**: Same import path issues; adds deployment complexity.
- **No protection (current state)**: Risky — user packages can silently break platform features.

**Implementation**:
1. Generate `platform-constraints.txt` from `requirements.txt` in the base image Dockerfile
2. Copy to `/opt/ml-platform/platform-constraints.txt` in the image
3. Modify `ImageBuildService.java` to add `--constraint /opt/ml-platform/platform-constraints.txt` to the pip install command in the generated Dockerfile
4. This is a cross-feature improvement that protects all existing platform packages too
