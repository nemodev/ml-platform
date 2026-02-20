# Quickstart: Customized Notebook UI Embedding

**Feature**: 007-notebook-ui-customization
**Branch**: `007-notebook-ui-customization`
**Worktree**: `/private/tmp/ml-platform-notebook-embed`

## Prerequisites

- Existing ML Platform running (Features 001-006)
- Docker (for rebuilding notebook image)
- Node.js 18+ (for Angular frontend)
- Java 21 (for Spring Boot backend)
- Access to Kubernetes cluster with JupyterHub deployed

## Phase 1: Notebook Image Configuration (P1 - Clean View)

### 1. Add JupyterLab configuration files to Dockerfile

Add `page_config.json` and `overrides.json` to the notebook image:

```bash
# In infrastructure/docker/notebook-image/Dockerfile, after pip install line:
# Create page_config.json to disable menu bar, status bar, announcements
# Create overrides.json to suppress update checks
```

### 2. Rebuild and deploy notebook image

```bash
docker build -t ml-platform-notebook:latest infrastructure/docker/notebook-image/
# Restart JupyterHub pods to pick up new image
```

### 3. Verify

- Launch workspace from portal → menu bar, status bar, announcements should be hidden
- Notebook cell toolbar (Run, Stop, Restart) should remain visible
- Keyboard shortcuts (Shift+Enter, Ctrl+S) should work

## Phase 2: Command Bridge Integration (P2 - Dynamic Control)

### 1. Add bridge extension to notebook image

```bash
# Add to requirements.txt:
jupyter-iframe-commands==0.3.0
```

### 2. Add host library to Angular frontend

```bash
cd frontend && npm install jupyter-iframe-commands-host@0.3.0
```

### 3. Implement bridge in notebooks component

- Initialize `createBridge({ iframeId: 'jupyter-iframe' })` after iframe loads
- Wire portal toolbar buttons to bridge commands
- Register kernel status callback via `createProxy`

### 4. Verify

- Toggle File Browser button → sidebar shows/hides
- Theme switcher → notebook theme changes
- Run All / Save buttons → execute correctly
- Kernel status indicator → shows Busy/Idle

## Phase 3: Single-Document Mode (P3 - Focused View)

### 1. Extend backend workspace URL endpoint

Add optional `notebookPath` query parameter to `GET /api/v1/workspaces/url`.

### 2. Update frontend navigation

Pipeline run detail page passes notebook path when navigating to embedded view.

### 3. Verify

- Click notebook link from pipeline detail → opens in single-document mode
- Navigate to Notebooks page normally → opens full lab workspace

## Key File Locations

| Component | Path |
|-----------|------|
| Notebook Dockerfile | `infrastructure/docker/notebook-image/Dockerfile` |
| Python requirements | `infrastructure/docker/notebook-image/requirements.txt` |
| JupyterHub Helm values | `infrastructure/helm/jupyterhub/values.yaml` |
| Angular notebooks component | `frontend/src/app/features/notebooks/notebooks.component.ts` |
| Backend workspace service | `backend/src/main/java/com/mlplatform/service/WorkspaceService.java` |
| Backend JupyterHub service | `backend/src/main/java/com/mlplatform/service/JupyterHubService.java` |
| Workspace controller | `backend/src/main/java/com/mlplatform/controller/WorkspaceController.java` |
