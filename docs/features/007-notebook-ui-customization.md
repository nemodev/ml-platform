# Feature 007: Notebook UI Customization

> Introduces the Analysis entity to scope workspaces and experiments, hides redundant JupyterLab chrome, and adds a postMessage command bridge for portal-level notebook control.

## What & Why

Without this feature, the embedded JupyterLab looks like "two applications stitched together" — the portal has its own nav, and JupyterLab has its own menu bar, status bar, and toolbar. Feature 007 eliminates the duplication by hiding JupyterLab's native chrome and replacing it with portal-controlled toolbars. It also introduces the **Analysis entity**, which groups workspaces, experiments, and visualizations under a single named context. This is the architectural pivot point — Features 002, 003, 005, and 009 all reference analysis-scoped URLs (`/api/v1/analyses/{analysisId}/...`), making Analysis the central organizing concept.

## Architecture

The feature operates at three levels:

**1. Analysis entity (backend):**
```
Analysis (user_id, name, description)
    ├── Workspace (analysis_id) — one active per analysis
    ├── Experiments (analysis-prefixed in MLflow)
    └── Visualizations (Streamlit apps in workspace pod)
```

**2. JupyterLab chrome removal (Dockerfile):**
- `page_config.json` disables extensions: main menu, terminal, extension manager, running sessions, announcements
- CSS injection hides `#jp-menu-panel` and `.jp-NotebookPanel-toolbar` via `display:none!important`
- Settings overrides suppress update checks and news

**3. postMessage command bridge (frontend ↔ iframe):**
```
Angular (notebooks.component.ts)
    ↓ JupyterBridgeService.execute('notebook:run-all-cells')
    ↓ Comlink RPC via postMessage
JupyterLab (jupyter-iframe-commands extension)
    ↓ executes command
    ↓ returns result
Angular (bridge receives result)
```

**Key decisions:**

- **Analysis as the scoping entity** — Every workspace and experiment belongs to an Analysis. Users create analyses (e.g., "Housing Price Study") and all work is organized under them. This replaced the previous flat model where workspaces and experiments were user-level.
- **Belt-and-suspenders chrome removal** — Both extension disabling (prevents code from loading) and CSS injection (catches anything that slips through). Extension disabling is more efficient; CSS provides resilience across JupyterLab version changes.
- **postMessage bridge over REST** — The `jupyter-iframe-commands-host` npm library and its JupyterLab counterpart (`jupyter-iframe-commands`) use Comlink for RPC over postMessage. This allows the portal to execute any JupyterLab command (run cells, toggle sidebar, change theme) without going through the backend.
- **Component preservation via display:none** — The analysis layout keeps Notebooks and Visualization components in the DOM (hidden via `[style.display]`) when switching tabs, preserving iframe state. The Experiments component is recreated on each visit for fresh MLflow data.
- **File browser sidebar is toggleable, not removed** — Users need multi-file workflows, so the sidebar is collapsed by default but can be toggled via the portal toolbar.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `model/Analysis.java` | JPA entity: UUID PK, user FK, name, description |
| Backend | `service/AnalysisService.java` | CRUD with user isolation, duplicate name prevention |
| Backend | `controller/AnalysisController.java` | Standard REST: POST, GET list, GET single, DELETE |
| Backend | `service/WorkspaceService.java` | `getWorkspaceUrl()` supports optional `notebookPath` for single-doc mode |
| Backend | `service/JupyterHubService.java` | `getDocUrl()` and `getNamedServerDocUrl()` for focused notebook viewing |
| Frontend | `features/analyses/analysis-layout.component.ts` | Tabbed container: Notebooks, Experiments, Visualization |
| Frontend | `core/services/jupyter-bridge.service.ts` | postMessage bridge lifecycle, command execution, error handling |
| Frontend | `features/notebooks/notebooks.component.ts` | Toolbar commands, bridge init, kernel status, sidebar/theme toggles |
| Frontend | `core/services/analysis.service.ts` | Analysis CRUD client with selected analysis caching |
| Infra | `docker/notebook-image/Dockerfile` | Extension disabling, CSS injection, settings overrides |
| DB | `V008__create_analyses_and_link_workspaces.sql` | Analysis table + workspace.analysis_id FK |

**Bridge lifecycle:** `JupyterBridgeService` wraps `createBridge()` from the library, managing a reactive `connectionState` signal (idle → connecting → ready → disconnected). Commands are only executed when state is `ready`. Unknown command errors are logged as warnings, not thrown — graceful degradation means the notebook stays usable even if the bridge fails. Debug logs are capped at 100 entries.

**Portal toolbar commands:** The notebooks component exposes: run all cells, interrupt kernel, restart kernel, clear outputs, save; cell operations (insert, move, undo/redo); view toggles (sidebar, header, line numbers, theme); and a searchable command palette that lists all available JupyterLab commands.

**Analysis deletion guard:** `AnalysisService.deleteAnalysis()` checks for active workspaces (PENDING/RUNNING/IDLE) and throws 409 CONFLICT if any exist. Users must terminate workspaces before deleting the analysis.

**Single-document mode (P3):** JupyterLab's native `/doc/tree/{path}` URL pattern provides a focused view of a single notebook without tabs or file browser. `WorkspaceController` accepts an optional `notebookPath` query parameter on the URL endpoint, and `JupyterHubService` constructs the appropriate doc-mode URL.

## Challenges & Solutions

- **Keyboard shortcuts survive menu removal** — Removing the menu bar is visual only. JupyterLab shortcuts are registered by the shortcut extension (separate from the menu extension), so Ctrl+S, Ctrl+Shift+Enter, etc. continue to work.
- **Theme synchronization** — The portal theme must be authoritative. On bridge init, the component calls `apputils:change-theme` with `JupyterLab Light` or `JupyterLab Dark` to sync. JupyterLab doesn't maintain an independent theme preference.
- **Bridge failure graceful degradation** — If the bridge library fails to initialize (e.g., JupyterLab version incompatibility), the notebook remains fully usable through JupyterLab's native keyboard shortcuts. Portal toolbar buttons simply don't appear.
- **Iframe 403 on re-auth** — Handled with the same detection pattern from Feature 002: check iframe content, redirect through `/hub/logout`, re-authenticate via Keycloak.

## Limitations

- **CSS injection is fragile** — The `sed` command that injects CSS into JupyterLab's `index.html` assumes a specific file structure. JupyterLab major version upgrades could break it.
- **No collaborative editing** — Each analysis workspace is single-user. Real-time collaboration would require JupyterHub's RTC feature.
- **postMessage bridge requires same-origin** — The bridge works because the portal and JupyterLab are served through the same origin (via nginx proxy). A different hosting architecture would break it.
- **No analysis sharing** — Analyses are strictly user-scoped. No mechanism for sharing analyses or transferring ownership between users.
- **Status bar disabled in Dockerfile but not documented** — The research.md lists `@jupyterlab/statusbar-extension` as disabled, but the actual Dockerfile doesn't include it in the disabled extensions list. The status bar may still be visible.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| Custom JupyterLab extension for chrome removal | Requires maintaining a JupyterLab plugin across versions. CSS + config is simpler. |
| REST API for notebook commands | Slower round-trip through backend. postMessage is direct iframe-to-host communication. |
| Tabs for analyses (not separate page) | Analysis management is a first-class concept; deserves its own route and list view. |
| iFrame sandbox attribute | Blocks necessary features (scripts, same-origin). Not compatible with JupyterLab. |
| Removing JupyterLab entirely (custom notebook UI) | Massive scope. JupyterLab is mature and feature-complete. Embedding it is pragmatic. |

## Potential Improvements

- **JupyterLab extension for chrome removal** — Replace CSS injection with a proper JupyterLab extension that integrates with the settings system, surviving upgrades cleanly.
- **Analysis sharing and collaboration** — Allow inviting other users to analyses with viewer/editor roles.
- **Bridge command telemetry** — Track which bridge commands users execute most frequently to inform toolbar design decisions.
- **Notebook templates per analysis** — Auto-create starter notebooks when an analysis is created, pre-configured with the analysis context.
- **Analysis export/import** — Package an analysis (notebooks, experiment metadata, configurations) for transfer or archival.
