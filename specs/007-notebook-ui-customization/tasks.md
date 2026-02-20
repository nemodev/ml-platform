# Tasks: Customized Notebook UI Embedding

**Input**: Design documents from `/specs/007-notebook-ui-customization/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/workspace-url-api.yaml

**Tests**: Not explicitly requested — test tasks omitted. Each phase has manual verification via demo pages.

**Organization**: Tasks are grouped by user story (P1, P2, P3). Each phase includes a **demo page** with interactive knobs/controls so the user can hands-on experience every available option for that approach before committing to full integration.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Install dependencies, add demo page routing, and prepare shared foundations for all three approaches.

- [ ] T001 Add `jupyter-iframe-commands==0.3.0` to `infrastructure/docker/notebook-image/requirements.txt`
- [ ] T002 Install `jupyter-iframe-commands-host@0.3.0` npm package in `frontend/package.json` by running `cd frontend && npm install jupyter-iframe-commands-host@0.3.0`
- [ ] T003 Add demo routes to `frontend/src/app/app.routes.ts` — add a `notebook-demos` path with child routes for landing (`''`), config (`'config'`), bridge (`'bridge'`), and docmode (`'docmode'`). Each child lazy-loads a standalone component from `frontend/src/app/features/notebooks/demos/`. Protect with existing `authGuard`.
- [ ] T004 Create demo landing page at `frontend/src/app/features/notebooks/demos/demo-landing.component.ts` — standalone component with Angular 17 `@Component`. Renders a card grid with three cards (one per phase): "P1: Configuration-Only (Clean View)", "P2: Command Bridge (Dynamic Control)", "P3: Single-Document Mode (Focused View)". Each card has a brief description and a `routerLink` to its demo page (`./config`, `./bridge`, `./docmode`). Include inline template and styles (no separate HTML/SCSS files).

**Checkpoint**: `npm run build` succeeds; navigating to `/notebook-demos` shows the landing page with three demo cards (demo sub-pages are placeholder stubs until their respective phases).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Notebook image configuration changes that ALL phases depend on — `page_config.json` and `overrides.json` baked into the Docker image.

**CRITICAL**: These changes affect the notebook image used by all phases. Must be complete before user story demos can demonstrate real results.

- [ ] T005 Add `page_config.json` creation to `infrastructure/docker/notebook-image/Dockerfile` — after the `pip install` line (after line 33), add a `RUN` step that writes `/opt/conda/share/jupyter/lab/static/page_config.json` with content: `{"disabledExtensions": {"@jupyterlab/mainmenu-extension": true, "@jupyterlab/statusbar-extension": true, "@jupyterlab/apputils-extension:announcements": true}}`. This permanently hides the menu bar, status bar, and announcements extension. Run as user `jovyan` (current user context after line 31).
- [ ] T006 [P] Add `overrides.json` creation to `infrastructure/docker/notebook-image/Dockerfile` — in the same `RUN` block as T005 (or adjacent), write `/opt/conda/share/jupyter/lab/settings/overrides.json` with content: `{"@jupyterlab/apputils-extension:announcements": {"checkForUpdates": false, "fetchNews": "false"}}`. This is a belt-and-suspenders suppression of update checks.

**Checkpoint**: `docker build -t ml-platform-notebook:latest infrastructure/docker/notebook-image/` succeeds. Running `docker run --rm ml-platform-notebook:latest cat /opt/conda/share/jupyter/lab/static/page_config.json` outputs the expected JSON. Same for `overrides.json`.

---

## Phase 3: User Story 1 — Clean Embedded Notebook View (Priority: P1)

**Goal**: Eliminate redundant JupyterLab chrome (menu bar, status bar, announcements) in the embedded iframe. Zero frontend/backend code changes — configuration only. Demo page showcases the result and provides an info panel explaining what's hidden and why.

**Independent Test**: Launch workspace from portal → verify menu bar, status bar, and announcements are not visible. Verify cell toolbar (Run, Stop, Restart) remains visible. Verify keyboard shortcuts (Shift+Enter, Ctrl+S) still work.

### Implementation for User Story 1

- [ ] T007 [US1] Create P1 demo page at `frontend/src/app/features/notebooks/demos/demo-config.component.ts` — standalone Angular 17 component with inline template. Layout:
  - **Left panel (info)**: A panel listing each disabled extension with its purpose: `@jupyterlab/mainmenu-extension` (hides File/Edit/View/Run menus), `@jupyterlab/statusbar-extension` (hides bottom status bar), `@jupyterlab/apputils-extension:announcements` (suppresses popups). Show the exact `page_config.json` and `overrides.json` content in `<pre>` blocks. Include a "What's preserved" section noting: cell toolbar, keyboard shortcuts (Shift+Enter, Ctrl+S, Ctrl+Shift+V), file browser sidebar (toggleable via P2).
  - **Right panel (iframe)**: Embed the workspace iframe using the same URL loading pattern as `NotebooksComponent` (inject `WorkspaceService`, call `getWorkspaceUrl()`, sanitize with `DomSanitizer`). The iframe should show the clean JupyterLab with config applied. If workspace is not running, show a message directing user to launch from the main Notebooks page first.
  - **Bottom panel (verification checklist)**: Interactive checklist with items: "Menu bar hidden", "Status bar hidden", "No announcement popups", "Cell toolbar visible", "Shift+Enter runs cell", "Ctrl+S saves notebook". Each is a checkbox the user can tick as they verify.

**Checkpoint**: Navigate to `/notebook-demos/config` → see info panel with config details + embedded iframe showing clean JupyterLab (no menu bar, no status bar). Verification checklist is usable.

---

## Phase 4: User Story 2 — Dynamic UI Control from Portal (Priority: P2)

**Goal**: Establish a `jupyter-iframe-commands` bridge between the Angular portal and the embedded JupyterLab, enabling dynamic control of sidebar visibility, theme, notebook operations, and kernel status. Demo page provides a full control panel with knobs for every available bridge command.

**Independent Test**: Open P2 demo → click "Toggle File Browser" → sidebar shows/hides. Click theme switcher → notebook theme changes. Click "Run All" → cells execute. Click "Save" → notebook saves. Kernel status indicator reflects busy/idle transitions.

### Implementation for User Story 2

- [ ] T008 [US2] Create `JupyterBridgeService` at `frontend/src/app/core/services/jupyter-bridge.service.ts` — Angular injectable service wrapping `jupyter-iframe-commands-host`. API:
  - `initialize(iframeId: string): void` — calls `createBridge({ iframeId })`, stores bridge instance, sets `connectionState` signal to `'connecting'`, awaits `bridge.ready` then sets to `'ready'`. On error, sets `'disconnected'`.
  - `connectionState: Signal<'connecting' | 'ready' | 'disconnected'>` — reactive signal for bridge status.
  - `execute(commandId: string, args?: object): Promise<void>` — wraps `bridge.execute()`. On unknown command error, logs warning and doesn't throw.
  - `listCommands(): Promise<string[]>` — wraps `bridge.listCommands()`.
  - `destroy(): void` — cleanup on component teardown.
  - Import `createBridge` and `createProxy` from `'jupyter-iframe-commands-host'`. Handle the case where the bridge is not ready (queue or reject commands).

- [ ] T009 [US2] Create P2 demo page at `frontend/src/app/features/notebooks/demos/demo-bridge.component.ts` — standalone Angular 17 component with inline template. This is the primary interactive demo. Layout:
  - **Top bar (connection status)**: Show bridge connection state as a colored badge: green "Connected" / yellow "Connecting..." / red "Disconnected". Show the iframe element ID being targeted.
  - **Control panel (left side)**: A panel with grouped controls:
    - **Sidebar section**: "Toggle File Browser" button calling `bridge.execute('application:toggle-left-area')`. Show current sidebar state (visible/hidden) as a toggle switch.
    - **Theme section**: Two buttons "Light Theme" and "Dark Theme" calling `bridge.execute('apputils:change-theme', { theme: 'JupyterLab Light' })` and `bridge.execute('apputils:change-theme', { theme: 'JupyterLab Dark' })` respectively. Highlight the active theme.
    - **Header section**: "Toggle Header" button calling `bridge.execute('application:toggle-header')`.
    - **Notebook Operations section**: "Run All Cells" button calling `bridge.execute('notebook:run-all-cells')`. "Save Notebook" button calling `bridge.execute('notebook:save')`. "Restart Kernel" button calling `bridge.execute('notebook:restart-kernel')`.
    - **Kernel Status section**: Display kernel status (Idle/Busy/Disconnected/Unknown) as a colored indicator. Use `createProxy` to register a kernel status callback if available, or fall back to displaying "Unknown" with a note that status push requires the notebook to have an active kernel.
    - **Command Explorer section**: "List All Commands" button that calls `bridge.listCommands()` and displays the full list in a scrollable `<pre>` block. Include a text filter input to search commands by name.
  - **Iframe area (right side)**: Embed the workspace iframe with `id="jupyter-iframe"`. On iframe load event, call `JupyterBridgeService.initialize('jupyter-iframe')`. Full height layout.
  - **Bottom log panel**: A console-style log showing each bridge command sent and its result (success/error), with timestamps. Useful for debugging and understanding the bridge API.

- [ ] T010 [US2] Integrate bridge into production `NotebooksComponent` at `frontend/src/app/features/notebooks/notebooks.component.ts` — inject `JupyterBridgeService`. After iframe URL is loaded and iframe emits a `load` event, initialize the bridge with the iframe's DOM id. Add component properties: `sidebarVisible = false`, `currentTheme: 'light' | 'dark' = 'light'`, `kernelStatus: 'idle' | 'busy' | 'disconnected' | 'unknown' = 'unknown'`, `bridgeConnected = false`. On bridge ready: collapse sidebar (send `application:toggle-left-area` if sidebar is visible by default), sync theme (send `apputils:change-theme`). On component destroy, call `bridge.destroy()`.

- [ ] T011 [US2] Add portal toolbar to production template at `frontend/src/app/features/notebooks/notebooks.component.html` — in the `.running-card` section, replace the existing simple toolbar with a richer toolbar containing:
  - Left group: "Toggle File Browser" icon button, theme toggle (light/dark) icon button
  - Center group: "Run All" button, "Save" button
  - Right group: Kernel status badge (colored dot + text), existing "Terminate Workspace" button
  - Wrap all bridge-dependent buttons in `*ngIf="bridgeConnected"` so they only appear when the bridge is ready. When bridge is disconnected, show a subtle "Bridge unavailable" note and keep the iframe functional (graceful degradation per FR-009).
  - Add `id="jupyter-iframe"` attribute to the `<iframe>` element so the bridge can target it.

- [ ] T012 [US2] Add toolbar styling to `frontend/src/app/features/notebooks/notebooks.component.scss` — style the new toolbar: flex layout, icon buttons with hover states, kernel status badge colors (green for idle, amber for busy, red for disconnected, gray for unknown), button group spacing. Adjust iframe height calculation to account for the taller toolbar. Keep existing `.launcher-card`, `.pending-card`, `.error-card` styles unchanged.

**Checkpoint**: Navigate to `/notebook-demos/bridge` → see full bridge control panel. All buttons send commands to the iframe. Command explorer lists all available JupyterLab commands. On main `/notebooks` page, the production toolbar appears with working sidebar toggle, theme switch, run all, save, and kernel status.

---

## Phase 5: User Story 3 — Focused Single-Notebook View (Priority: P3)

**Goal**: Support opening a specific notebook in single-document mode via `/doc/tree/{path}` URL. Backend extends `GET /api/v1/workspaces/url` with optional `notebookPath` query parameter. Demo page provides a URL mode switcher to compare `/lab` vs `/doc/tree/{path}` views.

**Independent Test**: Open P3 demo → enter a notebook path → click "Open in Single-Document Mode" → iframe loads `/doc/tree/{path}` URL showing only that notebook. Switch back to "Workspace Mode" → iframe loads `/lab` URL with full workspace.

### Implementation for User Story 3

- [ ] T013 [P] [US3] Add `getDocUrl(String username, String notebookPath)` method to `backend/src/main/java/com/mlplatform/service/JupyterHubService.java` — after the existing `getLabUrl` method (line 142). Implementation: `return properties.getUrl().replaceAll("/$", "") + "/user/" + username + "/doc/tree/" + notebookPath;`. Add input validation: reject paths containing `..` (path traversal) or starting with `/` (must be relative). Throw `ResponseStatusException(BAD_REQUEST)` on invalid paths.

- [ ] T014 [US3] Extend `getWorkspaceUrl` in `backend/src/main/java/com/mlplatform/service/WorkspaceService.java` — change method signature from `getWorkspaceUrl(Jwt jwt)` to `getWorkspaceUrl(Jwt jwt, String notebookPath)`. When `notebookPath` is non-null and non-blank, call `jupyterHubService.getDocUrl(username, notebookPath)` instead of `getLabUrl(username)`. For dev profile, return `https://jupyter.org/try-jupyter/doc/tree/intro.ipynb` when path is provided.

- [ ] T015 [US3] Add `@RequestParam` to `WorkspaceController` at `backend/src/main/java/com/mlplatform/controller/WorkspaceController.java` — modify the `url()` method (line 57) to accept `@RequestParam(required = false) String notebookPath` and pass it to `workspaceService.getWorkspaceUrl(jwt, notebookPath)`. This implements the contract from `contracts/workspace-url-api.yaml`.

- [ ] T016 [US3] Extend `WorkspaceService.getWorkspaceUrl()` in `frontend/src/app/core/services/workspace.service.ts` — add optional `notebookPath?: string` parameter. When provided, append `?notebookPath={encoded}` to the API URL: `` `${environment.apiUrl}/workspaces/url?notebookPath=${encodeURIComponent(notebookPath)}` ``. Keep the no-arg version working for backwards compatibility.

- [ ] T017 [US3] Create P3 demo page at `frontend/src/app/features/notebooks/demos/demo-docmode.component.ts` — standalone Angular 17 component with inline template. Layout:
  - **URL Mode Switcher (top)**: Two large toggle buttons: "Workspace Mode (/lab)" and "Single-Document Mode (/doc/tree/)". Active mode is highlighted. Selecting a mode reloads the iframe with the corresponding URL.
  - **Notebook Path Input (visible in single-doc mode)**: A text input for the relative notebook path (e.g., `examples/sample-delta-data.ipynb`). A "Load" button that calls `workspaceService.getWorkspaceUrl(notebookPath)` and updates the iframe. Include a dropdown of known notebook files if `JupyterHubService.listNotebookFiles()` API is available (optional — show a hardcoded list of example paths as fallback: `examples/sample-delta-data.ipynb`, `examples/train-sklearn-model.ipynb`).
  - **URL Display Panel**: Show the actual URL being loaded in the iframe as a readonly text field, so the user can see the `/lab` vs `/doc/tree/` URL pattern difference.
  - **Comparison Info Panel**: Side-by-side comparison table showing what's visible in each mode: Workspace mode (file browser: toggleable, tabs: yes, sidebar: yes, multiple notebooks: yes) vs Single-doc mode (file browser: no, tabs: no, sidebar: no, single notebook: yes).
  - **Iframe area**: The embedded JupyterLab view that switches between modes based on user selection.

**Checkpoint**: Navigate to `/notebook-demos/docmode` → switch between workspace and single-document modes. URL display shows the pattern difference. Backend endpoint `GET /api/v1/workspaces/url?notebookPath=examples/sample-delta-data.ipynb` returns `/doc/tree/` URL.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final integration, edge case handling, and verification across all phases.

- [ ] T018 Add "Demo Pages" link to portal navigation — in `frontend/src/app/app.component.ts`, add a section entry `{ id: 'notebook-demos', name: 'Notebook Demos', path: '/notebook-demos', enabled: true }` to the `sections` array so the demo landing page is accessible from the sidebar. Mark with a comment that this is temporary for feature validation and should be removed before production.
- [ ] T019 [P] Add graceful degradation handling to `JupyterBridgeService` in `frontend/src/app/core/services/jupyter-bridge.service.ts` — when `bridge.execute()` throws (command unknown or bridge disconnected), catch the error, log a warning to console, and emit the error to an observable `bridgeErrors$` that components can subscribe to for displaying user-facing notifications. Do not re-throw — bridge failures must not crash the iframe or portal.
- [ ] T020 [P] Verify Helm values CSP configuration at `infrastructure/helm/jupyterhub/values.yaml` — review `frame-ancestors` and cookie `SameSite` settings. Document in a comment whether any changes are needed for the `jupyter-iframe-commands` postMessage bridge to work through the CSP. If `connect-src` or other CSP directives need updating for the Comlink/postMessage channel, add them.
- [ ] T021 Run quickstart.md validation — follow every step in `specs/007-notebook-ui-customization/quickstart.md` against a running K8s deployment: build Docker image, deploy to JupyterHub, verify P1 (clean view), verify P2 (bridge commands), verify P3 (single-document mode).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on T001 (requirements.txt) — BLOCKS user stories that need the rebuilt image
- **User Story 1 (Phase 3)**: Depends on Phase 2 (Dockerfile changes)
- **User Story 2 (Phase 4)**: Depends on Phase 1 (npm package) + Phase 2 (Dockerfile); T010-T012 depend on T008 (bridge service)
- **User Story 3 (Phase 5)**: Backend tasks (T013-T015) can start after Phase 1. Frontend (T016-T017) depends on T015.
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) — Bridge service is independent; production integration (T010-T012) can reference US1's clean view but doesn't block on it
- **User Story 3 (P3)**: Backend tasks (T013-T015) can start in parallel with US1/US2 (no shared files). Frontend demo (T017) is independent. Integration into production NotebooksComponent is deferred to future work (navigation from pipeline detail page).

### Within Each User Story

- Setup dependencies before story-specific work
- Services/models before UI components
- Demo pages after their prerequisite services exist

### Parallel Opportunities

- T001 and T002 can run in parallel (different package managers)
- T005 and T006 can run in parallel (same Dockerfile but independent JSON files — combinable into one RUN)
- T013, T014, T015 are sequential (model → service → controller) but can run in parallel with all frontend US2 tasks
- T008 (bridge service) and T013-T015 (backend) can run in parallel (different languages, no dependencies)

---

## Parallel Example: User Story 2

```bash
# These can run in parallel (different files):
Task T008: "Create JupyterBridgeService in frontend/src/app/core/services/jupyter-bridge.service.ts"
Task T013: "Add getDocUrl method to backend/.../JupyterHubService.java"

# These are sequential (depend on T008):
Task T009: "Create P2 demo page" (uses JupyterBridgeService)
Task T010: "Integrate bridge into production NotebooksComponent" (uses JupyterBridgeService)
```

---

## Parallel Example: Backend US3

```bash
# Sequential (each builds on previous):
Task T013: "Add getDocUrl to JupyterHubService"
Task T014: "Extend getWorkspaceUrl in WorkspaceService" (uses getDocUrl)
Task T015: "Add @RequestParam to WorkspaceController" (uses updated getWorkspaceUrl)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T004)
2. Complete Phase 2: Foundational (T005-T006)
3. Complete Phase 3: User Story 1 (T007)
4. **STOP and VALIDATE**: Build Docker image, deploy, open `/notebook-demos/config` demo
5. Verify: menu bar hidden, status bar hidden, no announcements, cell toolbar works, shortcuts work

### Incremental Delivery

1. Setup + Foundational → Dependencies installed, image configured
2. Add User Story 1 → Build image → Demo at `/notebook-demos/config` → Validate clean view (MVP!)
3. Add User Story 2 → Demo at `/notebook-demos/bridge` → Validate all bridge commands + integrate into production `/notebooks`
4. Add User Story 3 → Demo at `/notebook-demos/docmode` → Validate single-document mode URLs
5. Polish → Remove demo nav link, final validation with quickstart.md

### Demo Page Strategy

Each demo page serves as both a **validation tool** and a **feature showcase**:

| Demo Page | Route | Controls | What It Proves |
|-----------|-------|----------|---------------|
| P1: Config | `/notebook-demos/config` | Info panel + verification checklist | Extension disabling works, shortcuts preserved |
| P2: Bridge | `/notebook-demos/bridge` | Sidebar toggle, theme switcher, run/save, kernel status, command explorer | Full iframe-to-JupyterLab communication |
| P3: Doc Mode | `/notebook-demos/docmode` | URL mode switcher, notebook path input, URL display | Single-document mode via URL pattern |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Demo pages use inline templates (no separate HTML files) to keep each as a single self-contained file
- Demo pages inject `WorkspaceService` and require a running workspace — they reuse the existing workspace lifecycle, not a separate one
- Demo pages are intended for development/validation and should be removed or hidden behind a feature flag before production
- The `JupyterBridgeService` (T008) is the shared service used by both the P2 demo page and the production NotebooksComponent
- All bridge commands use JupyterLab's built-in command IDs — no custom commands needed
- Commit after each task or logical group
- Stop at any checkpoint to validate independently
