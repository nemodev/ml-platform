# Data Model: Customized Notebook UI Embedding

**Feature**: 007-notebook-ui-customization
**Date**: 2026-02-17

## Overview

This feature introduces **no new database entities or schema changes**. All state is either:
- Static configuration baked into the notebook Docker image (`page_config.json`, `overrides.json`)
- Ephemeral client-side state in the Angular frontend (bridge connection, toolbar state)
- An extension to an existing API response (workspace URL with optional notebook path)

## Entities

### Notebook Embed Configuration (Static — Docker Image)

Not a runtime entity. Configuration files embedded in the notebook Docker image at build time.

| File | Location in Image | Purpose |
|------|------------------|---------|
| `page_config.json` | `/opt/conda/share/jupyter/lab/static/page_config.json` | Disables menu bar, status bar, announcements extensions |
| `overrides.json` | `/opt/conda/share/jupyter/lab/settings/overrides.json` | Suppresses update checks and news fetching |

**Lifecycle**: Created at Docker image build time. Immutable at runtime. Changed only by rebuilding the image.

### Command Bridge Session (Ephemeral — Browser)

Client-side only. Lives in the Angular component's memory during the iframe session.

| Attribute | Type | Description |
|-----------|------|-------------|
| bridgeInstance | object | Comlink proxy wrapping the iframe's JupyterLab command registry |
| connectionState | enum | `connecting` / `ready` / `disconnected` |
| availableCommands | string[] | List of JupyterLab command IDs (populated after `bridge.ready`) |
| iframeElementId | string | DOM ID of the iframe element (e.g., `'jupyter-iframe'`) |

**Lifecycle**: Created when iframe loads → `connecting` during handshake → `ready` when bridge resolves → `disconnected` on error or iframe unload. Destroyed on component teardown.

### Portal Notebook Toolbar State (Ephemeral — Browser)

Client-side only. Reactive state driving the Angular toolbar UI.

| Attribute | Type | Description |
|-----------|------|-------------|
| sidebarVisible | boolean | Whether the JupyterLab file browser sidebar is shown (default: `false`) |
| currentTheme | enum | `light` / `dark` (synced from portal's theme) |
| kernelStatus | enum | `idle` / `busy` / `disconnected` / `unknown` |
| bridgeConnected | boolean | Whether the command bridge is ready |

**Lifecycle**: Initialized on component creation. Updated reactively via bridge callbacks and user interactions. Destroyed on component teardown.

### WorkspaceUrlDto (Existing — Extended)

Existing DTO returned by `GET /api/v1/analyses/{analysisId}/workspaces/url`. Extended with an optional `notebookPath` query parameter on the request side.

| Field | Type | Existing/New | Description |
|-------|------|-------------|-------------|
| url | string | Existing | Full URL to the JupyterLab instance |

**Change**: The backend `getWorkspaceUrl()` method accepts an optional `notebookPath` query parameter. When present, the URL uses `/doc/tree/{path}` instead of `/lab`. The URL includes the JupyterHub named server segment (analysis UUID).

## State Transitions

### Bridge Connection State

```
[iframe loading] → connecting → ready → (user navigates away) → destroyed
                      ↓
                  disconnected → (retry on next load)
```

### Kernel Status

```
unknown → idle → busy → idle → ... (repeats during execution)
           ↓
       disconnected → (kernel restart) → idle
```

## Database Impact

**None.** No new tables, columns, or migrations. No changes to existing entities (User, Workspace, PipelineRun, ModelDeployment).
