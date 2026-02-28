# Feature Specification: Customized Notebook UI Embedding

**Feature Branch**: `007-notebook-ui-customization`
**Created**: 2026-02-17
**Status**: Implemented
**Input**: User description: "Customize the embedded JupyterLab notebook UI to hide redundant chrome (menu bar, header, status bar, sidebar) and provide seamless visual integration within the Angular portal, using a tiered approach from configuration-only quick wins to a command bridge for dynamic iframe control."

## Clarifications

### Session 2026-02-17

- Q: Should the portal auto-sync its theme to the notebook on load, or let the notebook keep its own theme? → A: Portal auto-syncs theme on load (portal theme is authoritative).
- Q: Should keyboard shortcuts remain functional when the menu bar is hidden? → A: Yes — keyboard shortcuts MUST remain functional; menu bar removal is visual-only.
- Q: Should hidden UI elements be permanently removed or configurable/toggleable? → A: Configurable. Elements like the file browser sidebar must be toggleable (hidden by default but user can show it), since users need multi-file workflows. Only truly redundant chrome (menu bar, status bar, announcements) is permanently hidden.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Clean Embedded Notebook View (Priority: P1)

A data user navigates to the Notebooks section of the ML Platform portal and launches a workspace. When JupyterLab loads in the embedded view, they see only the notebook editing area — the JupyterLab menu bar, status bar, and announcement popups are hidden. The notebook toolbar (Run, Stop, Restart buttons) remains visible so the user can interact with the notebook. The experience feels like a native part of the portal rather than a separate application embedded in an iframe.

**Why this priority**: This is the core value proposition — eliminating the "two applications stitched together" perception. It delivers immediate visual improvement with the least implementation effort (configuration-only changes to the notebook image).

**Independent Test**: Can be fully tested by launching a workspace and visually verifying that the JupyterLab menu bar, status bar, and announcement popups are not visible in the embedded iframe, while the notebook toolbar and cell editing area remain functional.

**Acceptance Scenarios**:

1. **Given** a user has a running workspace, **When** the JupyterLab iframe loads in the portal, **Then** the JupyterLab main menu bar (File, Edit, View, Run, etc.) is not visible.
2. **Given** a user has a running workspace, **When** the JupyterLab iframe loads in the portal, **Then** the bottom status bar is not visible.
3. **Given** a user has a running workspace, **When** the JupyterLab iframe loads in the portal, **Then** no announcement popups or update-check notifications appear.
4. **Given** a user has a running workspace, **When** the JupyterLab iframe loads, **Then** the in-notebook toolbar is hidden (the portal's own toolbar provides Run, Save, Restart Kernel, and other actions via the command bridge).
5. **Given** a user accesses JupyterLab directly (not via the portal iframe), **When** JupyterLab loads, **Then** the same clean configuration applies (acceptable trade-off for embedded-first deployment).

---

### User Story 2 - Dynamic UI Control from Portal (Priority: P2)

A data user is working in the embedded notebook. The portal provides a toolbar above the iframe with controls to toggle the JupyterLab sidebar (file browser) on or off, switch between light and dark themes to match the portal appearance, and trigger notebook-wide operations (Run All Cells, Save). The portal can also detect and display the current kernel status (Idle, Busy, Disconnected) in its own toolbar, keeping the user informed without relying on JupyterLab's hidden status bar.

**Why this priority**: Adds significant usability by giving the portal meaningful control over the embedded environment, making it a true integration rather than just a styled iframe. Depends on Story 1 being complete.

**Independent Test**: Can be tested by verifying that clicking portal toolbar buttons successfully toggles JupyterLab sidebar visibility, changes the notebook theme, and executes notebook operations. Kernel status indicator can be verified by running a long computation and confirming the portal shows "Busy" then "Idle".

**Acceptance Scenarios**:

1. **Given** a running notebook in the embedded view, **When** the user clicks a "Toggle File Browser" button in the portal toolbar, **Then** the JupyterLab left sidebar toggles visibility.
2. **Given** a running notebook, **When** the embedded view first loads, **Then** the notebook theme automatically matches the portal's current theme (light or dark). When the user subsequently selects a different theme in the portal toolbar, the notebook switches to match.
3. **Given** a running notebook, **When** the user clicks "Run All" in the portal toolbar, **Then** all notebook cells execute sequentially.
4. **Given** a running notebook, **When** the user clicks "Save" in the portal toolbar, **Then** the notebook is saved.
5. **Given** a running notebook executing a computation, **When** the kernel is busy, **Then** the portal toolbar displays a "Busy" indicator. When execution completes, the indicator changes to "Idle".
6. **Given** the embedded notebook loses its kernel connection, **When** the kernel becomes disconnected, **Then** the portal toolbar displays a warning indicator.

---

### User Story 3 - Focused Single-Notebook View (Priority: P3)

A data user opens a specific notebook from a pipeline run detail page or from a file listing in the portal. Instead of seeing the full JupyterLab workspace (with file browser, tabs, and multiple open documents), they see a focused single-document view showing only that notebook. This eliminates distractions and makes the portal feel like it manages the notebook context, not JupyterLab.

**Why this priority**: Enhances the integration for notebook-specific navigation flows (e.g., viewing a pipeline output notebook). Lower priority because the current `/lab` URL already works and the file browser can be toggled off via Story 2.

**Independent Test**: Can be tested by navigating to a notebook via a direct link from the portal and verifying JupyterLab opens in single-document mode without tabs, sidebar, or file browser.

**Acceptance Scenarios**:

1. **Given** a user clicks a notebook link from the pipeline run detail page, **When** the notebook iframe loads, **Then** JupyterLab opens in single-document mode showing only that notebook.
2. **Given** a user is in single-document mode, **When** they interact with the notebook, **Then** all standard notebook operations (run cells, edit, save) work normally.
3. **Given** a user is in the main Notebooks page (not coming from a specific notebook link), **When** their workspace loads, **Then** the full JupyterLab workspace view loads (with file browser accessible via toggle) so they can browse and open files.

---

### Edge Cases

- What happens when the command bridge fails to connect to the JupyterLab extension inside the iframe? The portal toolbar should show a "disconnected" state and gracefully degrade to the basic embedded view without bridge controls.
- What happens when JupyterLab is updated and internal command IDs change? The bridge should handle unknown command errors gracefully and log warnings rather than crashing.
- What happens when a user has multiple browser tabs open to the same workspace? Each tab should independently establish its own bridge connection without interfering with other tabs.
- What happens when the notebook image is used outside the portal (e.g., direct JupyterHub access for debugging)? The UI chrome removal via configuration is acceptable since direct access is an admin/debug scenario, not the primary workflow.
- What happens when the iframe loads slowly or the kernel takes time to start? The portal should continue showing its existing loading spinner until the iframe is ready and the bridge reports connectivity.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST hide the JupyterLab main menu bar (File, Edit, View, Run, Kernel, Settings, Help) in the embedded notebook view. This removal is permanent (not toggleable) since the portal toolbar replaces its functionality.
- **FR-002**: System MUST hide the JupyterLab bottom status bar in the embedded notebook view. This removal is permanent since kernel status is displayed in the portal toolbar.
- **FR-003**: System MUST suppress JupyterLab announcement popups and update-check notifications in the embedded notebook view.
- **FR-004**: System MUST hide the in-notebook toolbar (`.jp-NotebookPanel-toolbar`) via CSS (`display:none!important`) since the portal toolbar replaces its functionality. All JupyterLab keyboard shortcuts (e.g., Shift+Enter to run cell, Ctrl+S to save) MUST remain functional — toolbar removal is visual-only and MUST NOT disable the underlying shortcut system.
- **FR-005**: System MUST provide a portal toolbar above the embedded iframe with controls for: toggling the file browser sidebar, switching theme (light/dark), running all cells, saving the notebook, interrupting/restarting kernel, clearing outputs, inserting/moving cells, undo/redo, toggling line numbers/header, and opening the command palette.
- **FR-012**: System MUST treat the file browser sidebar as a toggleable element (hidden by default on load, user can show/hide via portal toolbar) rather than permanently removed, since users require multi-file workflows.
- **FR-006**: System MUST display the current kernel status (Idle, Busy, Disconnected) in the portal toolbar.
- **FR-007**: System MUST support opening a specific notebook in single-document mode via a URL path, bypassing the full JupyterLab workspace view.
- **FR-008**: System MUST establish a communication bridge (using the `jupyter-iframe-commands-host` library) between the Angular portal and the embedded JupyterLab instance for sending commands and receiving status events.
- **FR-009**: System MUST gracefully degrade when the communication bridge is unavailable — the embedded notebook should remain usable without portal toolbar controls.
- **FR-010**: System MUST ensure that the communication bridge validates message origins to prevent cross-origin security issues.
- **FR-011**: System MUST auto-sync the portal's current theme (light/dark) to the embedded notebook when the iframe first loads. The portal theme is authoritative; the notebook does not maintain an independent theme preference.

### Key Entities

- **Notebook Embed Configuration**: Settings that control which JupyterLab UI elements are visible in the embedded view. Categorized as: permanently hidden (menu bar, status bar, announcements — redundant chrome replaced by the portal toolbar) and toggleable (file browser sidebar — hidden by default, user can show/hide at runtime).
- **Command Bridge Session**: A communication channel between the portal and a specific embedded JupyterLab instance — connection state, available commands, message origin validation.
- **Portal Notebook Toolbar**: A set of controls rendered by the Angular portal above the iframe — sidebar toggle state, theme selection, kernel status, action buttons (Run All, Save).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users see no duplicate navigation chrome (menu bars, status bars, in-notebook toolbar) when viewing an embedded notebook — the only visible toolbar is the portal's own toolbar.
- **SC-002**: Portal toolbar commands (toggle sidebar, change theme, run all, save) execute successfully within 2 seconds of the user clicking the control.
- **SC-003**: Kernel status indicator in the portal reflects actual kernel state changes within 3 seconds of the state transition.
- **SC-004**: The embedded notebook remains fully functional (cell execution, editing, saving) even when the command bridge is unavailable.
- **SC-005**: Single-document mode loads the targeted notebook without displaying file browser, tabs, or unrelated UI elements.
- **SC-006**: User satisfaction with the notebook embedding integration improves as measured by the elimination of "two-app" perception — users perceive the notebook as a native part of the portal.
