# Feature Specification: MLflow Experiment Tracking

**Feature Branch**: `003-mlflow-experiment-tracking`
**Created**: 2026-02-16
**Status**: Draft
**Input**: User description: "MLflow Experiment Tracking"

**Depends on**: `001-keycloak-auth-portal` (portal shell),
`002-jupyterhub-notebook` (notebook environment)

## Clarifications

### Session 2026-02-16

- Q: How should per-user experiment isolation be implemented in a shared MLflow server? → A: Backend proxy filters by user-and-analysis-prefixed experiment names (e.g., `username/analysisId/experiment-name`)
- Q: What S3-compatible storage should be used for MLflow artifacts? → A: MinIO deployed on K8s (no cloud dependency)
- Q: How should the MLflow UI be protected when it has no built-in auth? → A: Network-level isolation only; MLflow accessible only within the cluster, iframe trusts portal auth

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Log Experiment from Notebook (Priority: P1)

A data scientist opens a notebook and writes a training script. The
script uses the experiment tracking client to log parameters (e.g.,
learning rate, number of estimators), metrics (e.g., accuracy, RMSE),
and the trained model as an artifact. After the cell executes, the run
is recorded in the tracking server and can be retrieved programmatically.

**Why this priority**: Experiment tracking from notebooks is the core
capability. Without it, there is no experiment history or model
versioning.

**Independent Test**: Can be verified by running a notebook cell that
logs a run with parameters, metrics, and a model, then querying the
tracking server to confirm the run exists.

**Acceptance Scenarios**:

1. **Given** an authenticated user in a notebook, **When** they run a
   training script that logs parameters and metrics to the tracking
   server, **Then** the run is recorded successfully.
2. **Given** a logged run, **When** the user queries the tracking
   server from the notebook, **Then** the run's parameters, metrics,
   and artifacts are retrievable.
3. **Given** a training script that logs a model artifact, **When** the
   script completes, **Then** the model artifact is stored and
   downloadable from the tracking server.
4. **Given** a user who runs the same experiment multiple times, **When**
   they query their experiment, **Then** all runs are listed with their
   respective metrics for comparison.

---

### User Story 2 - View Experiments in Embedded UI (Priority: P2)

A user navigates to the "Experiments" tab within an analysis. The
experiment tracking UI is embedded within the portal. The user can
browse their experiments scoped to the current analysis, see a list of
runs, compare metrics across runs, and inspect model artifacts — all
without leaving the portal.

**Why this priority**: The visual UI makes experiment results accessible
without writing code. It enables comparing runs, identifying the best
model, and sharing results with team members.

**Independent Test**: Can be verified by navigating to the Experiments
section after logging at least one run, and confirming the run is
visible with correct parameters and metrics.

**Acceptance Scenarios**:

1. **Given** an authenticated user in the portal, **When** they click
   "Experiments" in the navigation, **Then** the experiment tracking UI
   loads embedded within the portal page.
2. **Given** the experiment UI is loaded, **When** the user selects
   an experiment, **Then** they see a list of runs with their metrics.
3. **Given** multiple runs in an experiment, **When** the user selects
   two or more runs, **Then** they can compare metrics side by side.
4. **Given** a run with a logged model artifact, **When** the user
   clicks on the run, **Then** they can view artifact details.

---

### User Story 3 - SSO Passthrough to Experiment Tracking UI (Priority: P3)

When the user navigates to the Experiments section, they are NOT
prompted to log in again. The portal's authentication is passed through
to the experiment tracking UI seamlessly. Users see only their own
experiments by default.

**Why this priority**: Consistent SSO across all embedded tools is a
constitution requirement. Without it, each tool feels like a separate
product.

**Independent Test**: Can be verified by logging into the portal once,
navigating to Experiments, and confirming no additional login is
required.

**Acceptance Scenarios**:

1. **Given** an authenticated user in the portal, **When** they
   navigate to the Experiments section, **Then** the tracking UI loads
   without any additional login prompt.
2. **Given** two different authenticated users, **When** each views
   the Experiments section, **Then** each sees their own experiments
   (not the other user's).

---

### Edge Cases

- What happens when the tracking server is down while a user logs a
  run from a notebook? The mlflow client raises an error in the
  notebook cell output; the training code still completes but the run
  is not recorded. The user can retry.
- What happens when a user tries to log a very large model artifact
  (e.g., >1GB)? The upload proceeds but may take time; a timeout
  error is raised if it exceeds the configured limit.
- What happens when the artifact storage is full? The tracking server
  returns an error when attempting to store the artifact; the user
  sees the error in their notebook output.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide an experiment tracking server
  that accepts and stores run data (parameters, metrics, artifacts).
- **FR-002**: The mlflow client pre-installed in notebooks MUST be
  configured to connect to the tracking server automatically (no
  manual URL configuration by users).
- **FR-003**: The experiment tracking server MUST store model artifacts
  in object storage with the server acting as a proxy for artifact
  access.
- **FR-004**: The portal MUST embed the experiment tracking UI within
  the Experiments section.
- **FR-005**: The MLflow tracking server MUST be accessible only within
  the Kubernetes cluster (ClusterIP service, no external Ingress). The
  portal iframe loads the MLflow UI via port-forward (local dev) or
  in-cluster routing (production). Authentication is enforced at the
  portal level; the MLflow UI itself has no auth layer.
- **FR-006**: The tracking UI MUST be configured to allow framing by
  the portal domain (Content-Security-Policy frame-ancestors).
- **FR-007**: Experiment data MUST be isolated per user and per
  analysis — the backend proxy creates experiments with
  user-and-analysis-prefixed names (e.g.,
  `username/analysisId/experiment-name`) and filters API responses to
  show only the current user's experiments within the active analysis.
- **FR-008**: The tracking server MUST use a persistent database for
  metadata (not a local file store).

### Key Entities

- **Experiment**: A named container grouping related training runs.
  Owned by a user.
- **Run**: A single training execution that records parameters, metrics,
  and artifacts. Belongs to an experiment.
- **Artifact**: A file (model, dataset, plot) stored as part of a run.
  Physically stored in object storage.
- **Model**: A special artifact type representing a trained ML model.
  Can be registered for versioning via the MLflow Model Registry
  (feature 006).

### Assumptions

- The tracking server runs on the same Kubernetes cluster.
- MinIO deployed on the Kubernetes cluster provides S3-compatible
  object storage for artifact storage, with proxied access through
  the tracking server.
- A persistent database (e.g., PostgreSQL) is available for tracking
  server metadata.
- The mlflow client is already pre-installed in notebooks from feature
  002.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can log a training run (parameters + metrics +
  model artifact) from a notebook and see it in the experiment
  tracking UI within 30 seconds of the cell completing.
- **SC-002**: The experiment tracking UI loads embedded in the portal
  without any additional login prompt.
- **SC-003**: Two analyses for the same user have fully isolated
  experiments — experiments created in one analysis are not visible
  in the other.
- **SC-004**: A user can compare metrics across multiple runs within
  the same experiment using the embedded UI.
