# Feature Specification: JupyterHub Notebook Embedding

**Feature Branch**: `002-jupyterhub-notebook`
**Created**: 2026-02-16
**Status**: Draft
**Input**: User description: "JupyterHub Notebook Embedding"

**Depends on**: `001-keycloak-auth-portal` (authenticated portal shell)

## Clarifications

### Session 2026-02-16

- Q: What is the default idle timeout before a notebook server is shut down? → A: 30 minutes
- Q: What is the scope of pre-installed libraries? → A: Listed 5 + scipy, seaborn, plotly (scikit-learn is sufficient; pytorch/tensorflow not needed for MVP)
- Q: Does the 60-second startup target apply to cold starts? → A: 60s for warm start; cold start up to 120s

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Access Embedded Notebook (Priority: P1)

An authenticated user creates an **Analysis** (a named project context)
and navigates to its "Notebooks" tab. An interactive notebook
environment is embedded directly within the portal page. The user sees
a familiar notebook interface where they can create, open, and edit
notebooks. Each analysis has its own isolated workspace — a single user
can run multiple analyses concurrently, each with an independent
notebook server (JupyterHub named server) and persistent storage.

**Why this priority**: The embedded notebook is the primary workspace
for data scientists. Without it, the platform has no interactive
development capability.

**Independent Test**: Can be verified by logging into the portal,
creating an analysis, navigating to its Notebooks tab, and confirming
the notebook interface loads in the portal with the analysis workspace.

**Acceptance Scenarios**:

1. **Given** an authenticated user in the portal, **When** they create
   an analysis and open its Notebooks tab, **Then** an interactive
   notebook environment is displayed embedded within the portal page.
2. **Given** the notebook environment is loaded, **When** the user
   creates a new notebook, **Then** a new untitled notebook opens in
   the embedded interface.
3. **Given** a user with two analyses, **When** they open notebooks in
   each, **Then** each analysis has its own isolated workspace with
   separate files and independent notebook servers.

---

### User Story 2 - Execute Python Code in Notebook (Priority: P2)

A user opens a notebook and writes Python code in a cell. They execute
the cell and see the output rendered below it. Standard ML libraries
(numpy, pandas, scikit-learn, scipy, seaborn, plotly) are pre-installed
and importable without any setup by the user.

**Why this priority**: Code execution is the core function of the
notebook. Pre-installed libraries eliminate setup friction and let users
start working immediately.

**Independent Test**: Can be verified by opening a notebook, running
`import pandas as pd; pd.DataFrame({'a': [1,2,3]})` and seeing the
dataframe output rendered.

**Acceptance Scenarios**:

1. **Given** an open notebook, **When** the user types Python code in a
   cell and executes it, **Then** the output appears below the cell.
2. **Given** an open notebook, **When** the user imports numpy, pandas,
   scikit-learn, scipy, seaborn, and plotly, **Then** all imports
   succeed without errors.
3. **Given** an open notebook, **When** the user runs code that produces
   a plot (e.g., matplotlib), **Then** the plot is rendered inline.
4. **Given** an open notebook, **When** the user runs code that raises
   an exception, **Then** the error traceback is displayed in the cell
   output.

---

### User Story 3 - SSO Passthrough to Notebook (Priority: P3)

When the user navigates to the Notebooks section, they are NOT prompted
to log in again. The portal's authentication token is automatically
passed through to the notebook server, providing seamless single
sign-on. The user never sees a secondary login screen.

**Why this priority**: SSO passthrough is what makes the notebook feel
like part of the platform rather than a separate tool. Without it, the
user experience is fragmented.

**Independent Test**: Can be verified by logging into the portal once,
navigating to Notebooks, and confirming the notebook loads without any
additional login prompt.

**Acceptance Scenarios**:

1. **Given** an authenticated user in the portal, **When** they
   navigate to the Notebooks section for the first time, **Then** the
   notebook environment loads without any login prompt.
2. **Given** an authenticated user, **When** they refresh the Notebooks
   page, **Then** the notebook reloads without requiring
   re-authentication.
3. **Given** a user whose portal session expires, **When** they try to
   use the notebook, **Then** they are redirected to the portal login
   page (not a separate notebook login).

---

### Edge Cases

- What happens when the user's notebook server is idle for an extended
  period? The server is automatically shut down after 30 minutes of
  inactivity to reclaim resources. When the user next accesses
  Notebooks, a new server is spawned (with a brief loading indicator).
- What happens when the notebook server fails to start? The user sees
  a clear error message within the portal with a retry option.
- What happens when the user's persistent storage is full? The notebook
  shows a disk space warning; the user must delete files to continue
  saving.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The portal MUST embed the notebook environment within the
  Notebooks section using an iframe or equivalent embedding mechanism.
- **FR-002**: The notebook server MUST authenticate users via the same
  identity provider as the portal, with no secondary login required.
- **FR-003**: Each analysis MUST receive its own isolated notebook
  server (JupyterHub named server) with a personal file workspace.
  A single user MAY have multiple concurrent analyses.
- **FR-004**: The notebook environment MUST have pre-installed Python
  ML libraries: numpy, pandas, scikit-learn, matplotlib, mlflow,
  scipy, seaborn, and plotly.
- **FR-005**: The notebook environment MUST allow users to create, edit,
  execute, and save Jupyter notebooks.
- **FR-006**: The embedded notebook MUST be configured to allow framing
  by the portal domain (Content-Security-Policy frame-ancestors).
- **FR-007**: Idle notebook servers MUST be automatically shut down
  after a configurable timeout (default: 30 minutes) to reclaim
  cluster resources.
- **FR-008**: User notebook files MUST persist across server restarts
  (persistent storage per user).

### Key Entities

- **Analysis**: A named project context owned by a user. Scopes
  workspaces and MLflow experiments. One user can have many analyses.
- **Notebook Server**: A per-analysis compute instance (JupyterHub
  named server) running the notebook environment. Has a lifecycle
  (starting, running, idle, stopped).
- **Notebook File**: A .ipynb document stored in the analysis
  workspace. Contains code cells, markdown, and outputs.
- **User Workspace**: A persistent storage volume personal to each
  analysis. Survives server restarts.

### Assumptions

- The notebook server runs as a container on the same Kubernetes
  cluster as the portal.
- Persistent storage is available via the cluster's storage provisioner.
- The "Exploratory" resource profile (small CPU/RAM) is sufficient for
  MVP; GPU profiles are out of scope.
- The mlflow client library is pre-installed to enable experiment
  tracking in the next feature, but tracking server connectivity is
  not required for this feature.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An authenticated user can open a notebook and execute a
  Python cell within 60 seconds of clicking "Notebooks" when the
  server is already running (warm start). Cold starts (server not
  running, must be spawned) MUST complete within 120 seconds.
- **SC-002**: The notebook environment loads within the portal without
  any additional login prompt — zero extra authentication steps.
- **SC-003**: Two analyses for the same user each have isolated
  notebook servers with separate file workspaces; files created in one
  analysis are not visible in the other.
- **SC-004**: Pre-installed libraries (numpy, pandas, scikit-learn,
  matplotlib, mlflow, scipy, seaborn, plotly) are importable without
  errors in a fresh notebook.
- **SC-005**: User notebook files persist after the notebook server is
  stopped and restarted.
