# Implementation Plan: Customized Notebook UI Embedding

**Branch**: `007-notebook-ui-customization` | **Date**: 2026-02-17 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/007-notebook-ui-customization/spec.md`

## Summary

Customize the embedded JupyterLab UI to eliminate redundant chrome (menu bar, status bar, announcements) and provide seamless visual integration within the Angular portal. The approach uses a tiered strategy:

- **Phase 1 (P1)**: Configuration-only changes — `page_config.json` disables menu bar and status bar extensions; `overrides.json` suppresses announcements. Zero code changes to frontend/backend.
- **Phase 2 (P2)**: Command bridge integration — `jupyter-iframe-commands` v0.3.0 (PyPI + npm) enables the Angular portal toolbar to toggle the file browser sidebar, sync themes, execute notebook operations (Run All, Save), and display kernel status.
- **Phase 3 (P3)**: Single-document mode — extend `GET /api/v1/workspaces/url` with optional `notebookPath` parameter to construct `/doc/tree/{path}` URLs for focused notebook viewing from pipeline detail pages.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.4+ (frontend)
**Primary Dependencies**: Spring Boot 3.5.x, Angular 17.3, JupyterLab 4.3.4, Notebook 7.3.2, JupyterHub 5.4.3 (Z2JH 4.3.2), jupyter-iframe-commands 0.3.0
**Storage**: N/A — no new database entities; all state is static config or ephemeral browser state
**Testing**: Manual K8s deployment verification (per constitution Principle V), Angular component tests
**Target Platform**: Kubernetes cluster (existing ML Platform deployment)
**Project Type**: Web application (frontend + backend + infrastructure)
**Performance Goals**: Bridge commands execute within 2 seconds; kernel status reflects within 3 seconds
**Constraints**: Must preserve all JupyterLab keyboard shortcuts; must gracefully degrade without bridge
**Scale/Scope**: Single-user embedded iframe — no multi-user concurrency concerns for bridge state

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. MVP-First Incremental Delivery | PASS | Feature 007 follows speckit workflow. Dependencies (001, 002) already completed and verified. Three internal phases (P1→P2→P3) are independently deployable. |
| II. Infrastructure as Code | PASS | Dockerfile changes, Helm values, and page_config.json are all version-controlled under `infrastructure/`. No manual kubectl operations. |
| III. Unified Authentication | PASS | No auth changes. CSP `frame-ancestors` already configured. Bridge uses postMessage (same-origin validated), not new auth tokens. |
| IV. Environment Parity | PASS | Notebook image config changes apply identically to interactive JupyterHub servers and any Airflow Papermill workers using the same image. |
| V. Testing at System Boundaries | PASS | Primary verification is end-to-end: deploy to K8s, launch workspace, verify UI chrome hidden and bridge functional. Angular component tests supplement. |
| VI. Simplicity & YAGNI | PASS | Uses existing JupyterLab config mechanisms (page_config.json, overrides.json). Bridge library is a prebuilt pip/npm install — no custom JupyterLab extension development. Single-document mode is a URL change, not a new component. |

**Post-Phase 1 Re-check**: All principles remain satisfied. No new database entities, no custom operators, no message buses. The `jupyter-iframe-commands` library is the simplest path to iframe command bridging (alternative: custom postMessage implementation would be more complex).

## Project Structure

### Documentation (this feature)

```text
specs/007-notebook-ui-customization/
├── plan.md              # This file
├── research.md          # Phase 0: 7 decisions on JupyterLab config, bridge lib, theme sync, etc.
├── data-model.md        # Phase 1: No DB changes — static config + ephemeral browser state
├── quickstart.md        # Phase 1: Step-by-step verification guide
├── contracts/
│   └── workspace-url-api.yaml  # Phase 1: Extended GET /api/v1/workspaces/url with notebookPath
├── checklists/
│   └── requirements.md  # Spec quality validation (all passed)
└── tasks.md             # Phase 2 output (NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
infrastructure/
├── docker/notebook-image/
│   ├── Dockerfile                    # MODIFY: Add page_config.json, overrides.json creation; add jupyter-iframe-commands
│   └── requirements.txt              # MODIFY: Add jupyter-iframe-commands==0.3.0
└── helm/jupyterhub/
    └── values.yaml                   # REVIEW: Verify CSP and cookie config (likely no changes needed)

backend/
└── src/main/java/com/mlplatform/
    ├── service/
    │   ├── JupyterHubService.java    # MODIFY: Add getDocUrl(username, notebookPath) method
    │   └── WorkspaceService.java     # MODIFY: Accept optional notebookPath parameter
    └── controller/
        └── WorkspaceController.java  # MODIFY: Add @RequestParam for notebookPath

frontend/
├── package.json                      # MODIFY: Add jupyter-iframe-commands-host@0.3.0
└── src/app/features/notebooks/
    ├── notebooks.component.ts        # MODIFY: Add CommandBridge integration, toolbar state management
    ├── notebooks.component.html      # MODIFY: Add portal toolbar (sidebar toggle, theme, run all, save, kernel status)
    └── notebooks.component.scss      # MODIFY: Toolbar styling, iframe layout adjustments
```

**Structure Decision**: Web application structure — follows existing repo layout with `frontend/`, `backend/`, and `infrastructure/` directories. No new directories or modules needed; all changes modify existing files.

## Complexity Tracking

> No constitution violations. All changes use existing patterns and simplest available mechanisms.

No entries needed.
