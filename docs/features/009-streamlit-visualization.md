# Feature 009: Streamlit Visualization

> Runs Streamlit dashboards inside notebook pods via jupyter-server-proxy, embedded in the analysis layout as a third tab with file auto-discovery and startup polling.

## What & Why

Notebooks are great for exploration but poor for presenting results to stakeholders. Streamlit turns Python scripts into interactive web dashboards with zero frontend code. Rather than deploying Streamlit as a separate service, we run it inside the existing notebook pod using `jupyter-server-proxy` — the same infrastructure that already serves JupyterLab. Users create `.py` files in a `visualize/` directory, and the platform auto-discovers them by scanning for `import streamlit`. A "Visualization" tab appears in the analysis layout, users select a file from a dropdown, and the Streamlit app loads in an iframe. This is the third iframe pattern: JupyterLab uses postMessage bridge (Feature 007), MLflow uses CSS injection (Feature 003), Streamlit uses **polling startup detection**.

## Architecture

```
Analysis Layout (Visualization tab)
    ↓ GET /api/v1/analyses/{id}/visualizations/files
Backend (StreamlitService)
    ↓ proxy to notebook pod
Jupyter Server Extension (ml_platform_streamlit)
    ↓ GET /api/streamlit/files → scan visualize/ for import streamlit
    ↓ POST /api/streamlit/start → subprocess.Popen(streamlit run ...)
    ↓ GET /api/streamlit/status → check if port is listening
    ↓
jupyter-server-proxy
    ↓ /user/{username}/{server}/proxy/{port}/
Angular iframe (Streamlit dashboard)
```

**Key decisions:**

- **jupyter-server-proxy for serving** — Standard JupyterHub mechanism for proxying arbitrary web services from notebook pods. Handles WebSocket support, authentication (via JupyterHub session), and URL routing. No infrastructure changes needed.
- **Lightweight Jupyter server extension** — `ml_platform_streamlit` is a custom extension with four Tornado handlers (files, start, stop, status). It manages a single Streamlit subprocess per pod using module-level state. Process lifecycle is simple: one app at a time, kill previous before starting new.
- **Text-based file detection** — Scans for `import streamlit` or `from streamlit` strings in `.py` files under `visualize/`. No code execution — safe and fast.
- **Polling startup detection** — After starting Streamlit, the frontend polls `/api/streamlit/status` every 2 seconds until the port is listening (status transitions from `starting` to `running`). 60-second timeout before showing an error.
- **Keep process alive across tab switches** — Streamlit stays running when the user switches to Notebooks or Experiments tabs. The component is preserved in the DOM via `display:none` (same pattern as notebooks in Feature 007), maintaining the WebSocket connection and app state.
- **Platform package protection** — `platform-constraints.txt` prevents custom image builds (Feature 008) from downgrading `streamlit` or `jupyter-server-proxy` via pip's `--constraint` flag.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `controller/VisualizationController.java` | Files, start, stop, status endpoints (analysis-scoped) |
| Backend | `service/StreamlitService.java` | Proxies requests to notebook pod's extension API |
| Frontend | `features/visualization/visualization.component.ts` | State machine, file loading, startup polling, iframe embedding |
| Frontend | `core/services/visualization.service.ts` | HTTP client for visualization endpoints |
| Frontend | `features/analyses/analysis-layout.component.ts` | Visualization tab integration, component preservation |
| Extension | `ml_platform_streamlit/__init__.py` | Extension registration, localhost IPv6 fix |
| Extension | `ml_platform_streamlit/handlers.py` | Tornado handlers: files, start, stop, status |
| Infra | `docker/notebook-image/Dockerfile` | Extension install, streamlit in requirements |
| Infra | `docker/notebook-image/visualize/sample_dashboard.py` | Sample California Housing dashboard |
| Infra | `docker/notebook-image/platform-constraints.txt` | Pinned platform packages |

**Extension handlers in detail:** `StreamlitFilesHandler` walks `visualize/` and checks each `.py` file for Streamlit imports. `StreamlitStartHandler` validates the file path (rejects `..`, requires `visualize/` prefix), kills any running process, finds an available port (starting at 8501), and spawns `streamlit run` with flags: `--server.headless true`, `--server.enableCORS false`, `--server.enableXsrfProtection false`, `--browser.gatherUsageStats false`. `StreamlitStatusHandler` calls `_check_process_status()` which transitions from `starting` to `running` when the port is in use (socket probe), or to `errored` if the process died (reads last 20 lines of stderr).

**localhost IPv6 fix:** `_patch_proxy_localhost()` monkeypatches `jupyter_server_proxy.LocalProxyHandler.proxy()` to use `127.0.0.1` instead of `localhost`, which may resolve to `::1` on some systems, breaking the connection to Streamlit.

**Frontend state machine:** The visualization component manages states: `loading-files` → `no-files` (shows guide) or `starting` → `running` (iframe) or `errored`. When no Streamlit files exist, a guide card explains how to create one with a code example. The dropdown for file selection only appears when multiple files are found.

**Backend proxying:** `StreamlitService` resolves the workspace context (username, server name from analysis ID), checks that the workspace is RUNNING or IDLE, then proxies requests to the notebook pod's extension API via `JupyterHubService`'s WebClient. Dev profile returns mock file lists.

**Sample dashboard:** `sample_dashboard.py` is a realistic interactive dashboard for the California Housing dataset with sidebar filters (income range, house age), feature distribution charts, scatter plots, correlation matrix, geographic map, and raw data viewer. Uses `@st.cache_data` for efficient data loading.

## Challenges & Solutions

- **Streamlit CORS and XSRF in iframe** — Streamlit's built-in CORS and XSRF protection breaks when served through jupyter-server-proxy in an iframe. Solution: disable both (`--server.enableCORS false`, `--server.enableXsrfProtection false`). Security is provided by JupyterHub's authentication boundary.
- **Port finding race condition** — Between `_find_available_port()` and subprocess bind, another process could claim the port. Mitigated by the fact that only one Streamlit process runs at a time per pod.
- **Streamlit startup delay** — Streamlit can take 3-10 seconds to start. The 2-second polling interval with 60-second timeout provides reliable startup detection without user frustration.
- **Process orphaning** — If the extension reloads without calling `_kill_process()`, a Streamlit process could be orphaned. Mitigated by killing any existing process before starting a new one.

## Limitations

- **One Streamlit process per workspace** — Only one app runs at a time. Selecting a different file kills the previous one.
- **No persistence of app selection** — Switching to another tab and back re-scans files and may restart the app.
- **File detection requires `visualize/` directory** — Files outside this directory are not discovered. This is by design (clear convention) but not configurable.
- **No Streamlit app logging** — Subprocess stdout/stderr is not captured to a file, making debugging difficult.
- **Module-level state in extension** — `_process_state` is a module-level dict, not thread-safe. Tornado's event loop is single-threaded, but concurrent async requests could theoretically race.
- **Fixed 2-second polling interval** — No exponential backoff. Under high load, polling could add unnecessary requests.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| Standalone Streamlit deployment | Separate pod per app adds infrastructure complexity. jupyter-server-proxy reuses existing notebook pod. |
| Panel / Dash / Voila | Streamlit has the simplest developer experience (no callbacks, no layout code). The real project team prefers it. |
| WebSocket for startup detection | Adds complexity. Simple HTTP polling is reliable and matches the platform's existing patterns. |
| Sub-tabs per Streamlit file | More UI complexity. A dropdown is simpler and scales better with many files. |
| Auto-scan entire workspace | Too noisy. The `visualize/` convention provides clear intent and prevents false positives. |

## Potential Improvements

- **Multi-app support** — Allow multiple Streamlit processes on different ports, one per file.
- **App log viewer** — Capture Streamlit stdout/stderr and surface in the portal for debugging.
- **App pinning** — Remember the user's last-selected Streamlit file per analysis.
- **Hot reload** — Detect file changes and suggest restarting the app (Streamlit supports `--server.runOnSave`).
- **Adaptive polling** — Use VisibilityAPI to reduce polling when the browser tab is inactive.
