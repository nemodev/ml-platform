# Feature Specification: Streamlit Visualization

**Feature Branch**: `009-streamlit-visualization`
**Created**: 2026-02-28
**Status**: Draft
**Input**: User description: "I need a new feature that visualizes ML related data using Streamlit integration. If user has one or more Streamlit Python files in the workspace of notebook, a new tab 'Visualization' next to Experiments tab will be enabled. If no Streamlit usage found, the tab will still be opened but showing a guide message that it supports Streamlit. Also need to support multiple Streamlit files like having dropdown or sub tabs. Include sample, yet realistic example in the default notebook."

## Clarifications

### Session 2026-02-28

- Q: When the user leaves the Visualization tab and returns later, should the Streamlit app resume instantly or restart? → A: Keep alive during workspace session — the Streamlit process stays running while the workspace is active, providing instant return to the app.
- Q: Should file detection scan the entire workspace recursively or be limited to a specific scope? → A: Dedicated folder — scan only `.py` files in a top-level `visualize/` directory within the workspace.
- Q: When switching between Streamlit files, should the system run one app at a time or keep multiple running? → A: One at a time — stop the current Streamlit process and start the newly selected file.

## User Scenarios & Testing

### User Story 1 - View Streamlit Visualization in Analysis (Priority: P1)

A data scientist has written a Streamlit Python script in the `visualize/` folder of their notebook workspace (e.g., `visualize/dashboard.py`) to create interactive charts and dashboards for their ML experiment results. They navigate to the "Visualization" tab within their analysis and see their Streamlit app rendered inline, allowing them to interact with visualizations without leaving the platform.

**Why this priority**: Core value proposition — embedding Streamlit visualizations inside the analysis workflow is the primary reason for this feature. Without this, nothing else matters.

**Independent Test**: Can be fully tested by creating a single `.py` file containing Streamlit code in the workspace's `visualize/` folder, navigating to the Visualization tab, and verifying the app renders interactively within the platform.

**Acceptance Scenarios**:

1. **Given** a running workspace with a Python file that uses Streamlit in the `visualize/` folder (e.g., `visualize/dashboard.py`), **When** the user clicks the "Visualization" tab, **Then** the system detects the Streamlit file, starts serving it, and displays the Streamlit app embedded within the tab.
2. **Given** a Streamlit app is loaded in the Visualization tab, **When** the user interacts with widgets (sliders, dropdowns, checkboxes), **Then** the app responds in real time, updating charts and data displays without page reload.
3. **Given** the Streamlit app encounters a Python error, **When** the error occurs, **Then** the Visualization tab displays the Streamlit error message inline (standard Streamlit error display) rather than crashing the tab.

---

### User Story 2 - Guided Onboarding When No Streamlit Files Exist (Priority: P2)

A user navigates to the Visualization tab for the first time before creating any Streamlit files. Instead of an empty or broken state, they see a helpful guide explaining what Streamlit is, how to create a visualization file, and a quick-start code snippet they can copy into their workspace.

**Why this priority**: Ensures discoverability and a smooth first-time experience. Users who haven't used Streamlit yet should understand the capability and know exactly how to get started.

**Independent Test**: Can be tested by opening the Visualization tab in an analysis that has no Streamlit Python files, and verifying the guide content is displayed.

**Acceptance Scenarios**:

1. **Given** a workspace with no Python files that use Streamlit in the `visualize/` folder, **When** the user clicks the "Visualization" tab, **Then** the system displays a guide message explaining Streamlit support, the `visualize/` folder convention, a code example, and instructions.
2. **Given** the guide message is displayed, **When** the user creates a Streamlit file in the `visualize/` folder and returns to the Visualization tab, **Then** the guide is replaced by the running Streamlit app.

---

### User Story 3 - Switch Between Multiple Streamlit Apps (Priority: P2)

A data scientist has created multiple Streamlit scripts in their workspace's `visualize/` folder — for example, `visualize/eda_dashboard.py` for exploratory data analysis and `visualize/model_evaluation.py` for model performance metrics. They can select which app to view using a dropdown selector in the Visualization tab header.

**Why this priority**: Real-world ML workflows often involve multiple visualization perspectives (EDA, training progress, model comparison). Supporting this from the start avoids a single-file limitation that would frustrate power users.

**Independent Test**: Can be tested by creating two or more Streamlit Python files in a workspace's `visualize/` folder, opening the Visualization tab, and switching between them via the dropdown.

**Acceptance Scenarios**:

1. **Given** a workspace with multiple Streamlit Python files, **When** the user opens the Visualization tab, **Then** the system displays a dropdown selector listing all detected Streamlit files, with the first file loaded by default.
2. **Given** the dropdown is visible with multiple files, **When** the user selects a different file from the dropdown, **Then** the previously displayed app is replaced by the newly selected Streamlit app within a few seconds.
3. **Given** a workspace with exactly one Streamlit file, **When** the user opens the Visualization tab, **Then** no dropdown is shown — the single app loads directly.

---

### User Story 4 - Sample Streamlit Visualization in Default Notebook (Priority: P3)

When a new analysis is created, the default notebook workspace includes a `visualize/` folder with a sample Streamlit visualization file that demonstrates realistic ML data visualization patterns using the platform's existing sample data (California Housing dataset). This gives users a working example to learn from and modify.

**Why this priority**: Reduces the learning curve by providing a concrete, working example. Users can run the sample immediately and iterate from there rather than starting from scratch.

**Independent Test**: Can be tested by creating a new analysis, opening the workspace, verifying the sample Streamlit file exists, and loading it in the Visualization tab.

**Acceptance Scenarios**:

1. **Given** a newly created analysis with a fresh workspace, **When** the user opens the Visualization tab, **Then** the sample Streamlit file is detected and its visualization loads automatically.
2. **Given** the sample visualization is loaded, **When** the user interacts with it (e.g., adjusts filters, selects features), **Then** the charts and data displays update accordingly using the California Housing sample data.
3. **Given** the sample Streamlit file exists in the workspace, **When** the user opens it in the Notebooks tab, **Then** they can read and modify the code to understand how Streamlit integrations work.

---

### Edge Cases

- What happens when a Streamlit app takes longer than 30 seconds to start? The tab shows a loading indicator with a timeout message after 60 seconds, offering a retry option.
- What happens when the user's notebook workspace is not running (stopped/terminated)? The Visualization tab displays a message indicating the workspace must be running, with a link/button to navigate to the Notebooks tab to start it.
- What happens when a Streamlit file has syntax errors that prevent startup? The tab displays the Streamlit startup error output so the user can diagnose the issue.
- What happens when a Streamlit file is deleted from the workspace while its app is displayed? On the next tab visit or refresh, the system re-scans the workspace and updates the file list; the deleted file is removed from the dropdown.
- What happens when the user renames a Streamlit file? The updated filename appears in the dropdown on the next scan/refresh.
- What happens when a Python file imports Streamlit but is not a runnable Streamlit app (e.g., a utility module)? The system attempts to run it; if Streamlit produces no output, the tab shows an informational message suggesting the file may not be a standalone Streamlit app.

## Requirements

### Functional Requirements

- **FR-001**: The analysis layout MUST include a "Visualization" tab positioned after the "Experiments" tab.
- **FR-002**: The Visualization tab MUST always be accessible regardless of whether Streamlit files exist in the workspace.
- **FR-003**: The system MUST detect Python files in the `visualize/` directory at the workspace root that use Streamlit (files containing `import streamlit` or `from streamlit`). Files outside `visualize/` are not scanned.
- **FR-004**: When one or more Streamlit files are detected, the system MUST serve the selected file as a running Streamlit application and display it embedded within the Visualization tab.
- **FR-004a**: The Streamlit process MUST remain running when the user navigates away from the Visualization tab (e.g., to Notebooks or Experiments) and MUST resume display instantly when the user returns, without restarting the application. The process lifecycle is tied to the workspace session — it stops only when the workspace itself stops.
- **FR-005**: When no Streamlit files are detected, the system MUST display a guide message with an explanation of Streamlit support, a quick-start code example, and instructions for creating a visualization file.
- **FR-006**: When multiple Streamlit files are detected, the system MUST display a dropdown selector allowing users to choose which app to view. When only one file exists, the dropdown MUST be hidden.
- **FR-007**: The system MUST automatically load the first detected Streamlit file when the Visualization tab is opened.
- **FR-008**: Users MUST be able to switch between Streamlit apps by selecting a different file from the dropdown without navigating away from the tab.
- **FR-008a**: When switching files, the system MUST stop the previously running Streamlit process before starting the newly selected one (one app at a time).
- **FR-009**: The Visualization tab MUST display a loading indicator while a Streamlit app is starting up.
- **FR-010**: If the workspace is not running, the Visualization tab MUST inform the user and provide navigation to start the workspace.
- **FR-011**: The default notebook workspace MUST include a `visualize/` folder with a sample Streamlit visualization file that uses the California Housing dataset to demonstrate interactive ML data visualization (e.g., feature distributions, correlation heatmaps, model prediction scatter plots).
- **FR-012**: The embedded Streamlit app MUST support full interactivity — widgets, charts, data tables, and re-runs MUST work as they do in standalone Streamlit.
- **FR-013**: The system MUST re-scan the `visualize/` folder for Streamlit files each time the Visualization tab is opened to reflect any files added, removed, or renamed since the last visit.
- **FR-014**: Streamlit and its infrastructure dependencies MUST be available in all workspace environments regardless of custom image selection. Platform-critical packages MUST be protected from being overridden or broken by user-installed packages in custom notebook images.

### Key Entities

- **Streamlit App File**: A Python file in the `visualize/` folder of the user's notebook workspace that contains Streamlit imports and can be run as a Streamlit application. Key attributes: file name, file path within `visualize/`, detection status.
- **Visualization Session**: A running instance of a Streamlit app served from the user's workspace. Key attributes: associated workspace, active file, serving status (starting, running, errored, stopped).

## Success Criteria

### Measurable Outcomes

- **SC-001**: Users can view a Streamlit visualization within the platform in under 10 seconds after clicking the Visualization tab (for apps that start quickly).
- **SC-002**: The Visualization tab correctly detects and lists 100% of Python files containing Streamlit imports within the `visualize/` folder.
- **SC-003**: Users can switch between multiple Streamlit apps in under 5 seconds via the dropdown selector.
- **SC-004**: The sample Streamlit visualization loads successfully in newly created analyses on first attempt without user modification.
- **SC-005**: All standard Streamlit widgets (sliders, selectboxes, text inputs, buttons, file uploaders) function correctly within the embedded view.
- **SC-006**: Users who have never used Streamlit can create and view their first visualization within 5 minutes, guided by the onboarding message and sample file.

## Assumptions

- Streamlit is pre-installed in the notebook image, making it available in all workspaces without additional setup.
- The notebook workspace must be running for Streamlit apps to be served (Streamlit runs as a process within the workspace pod).
- The Streamlit app is served through the same reverse-proxy infrastructure used for JupyterHub, maintaining the same-origin policy for seamless embedding.
- File detection scans only the `visualize/` directory at the workspace root, using a simple text scan for Streamlit imports rather than executing the files, keeping detection fast and safe.
- The California Housing sample dataset (already available in the platform via MinIO) is used for the sample Streamlit visualization, maintaining consistency with existing examples.
- The dropdown selector approach (rather than sub-tabs) is used for multiple Streamlit files, as it scales cleanly to any number of files and keeps the UI compact.

## Scope Boundaries

### In Scope

- Visualization tab in the analysis layout
- Streamlit file detection in workspace
- Embedded Streamlit app serving and display
- Multiple file selector (dropdown)
- Onboarding guide for empty state
- Sample Streamlit visualization file in default workspace
- Loading and error states

### Out of Scope

- Deployment of Streamlit apps as standalone services (apps only run within the notebook workspace)
- Sharing Streamlit visualizations between users or analyses
- Version history or persistence of Streamlit app state across sessions
- Custom Streamlit components or themes beyond standard Streamlit capabilities
- Real-time collaboration on Streamlit apps
- Scheduling or automation of Streamlit reports
