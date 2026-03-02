# Feature Specification: Notebook Resource Profiles

**Feature Branch**: `010-notebook-resource-profiles`  
**Created**: 2026-03-01  
**Status**: Implemented  
**Input**: User description: "allow user to select predefined resource profiles when starting notebook, or switching when working on."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Select a Resource Profile When Launching a Workspace (Priority: P1)

A data scientist creates or opens an analysis and navigates to the Notebooks tab to launch a workspace. Before launching, they see a resource profile selector alongside the existing image selector. The selector shows a list of predefined profiles — for example, "Exploratory" (small CPU/memory for light work), "Standard" (moderate CPU/memory for typical ML workflows), and "Compute-Intensive" (high CPU/memory for large dataset processing). Each profile displays a human-readable name and a brief description of its intended use case, along with the allocated CPU and memory. The user selects the profile that matches their workload and launches the workspace. If the user does not explicitly choose a profile, the system uses a sensible default (e.g., "Exploratory").

**Why this priority**: This is the core capability. Without profile selection at launch time, users are locked into a single fixed resource allocation regardless of their workload. This causes either resource waste (over-provisioned for light work) or performance issues (under-provisioned for heavy computation).

**Independent Test**: Can be fully tested by opening the workspace launch flow, verifying the resource profile selector is visible with at least two profiles, selecting a non-default profile, launching the workspace, and confirming the running notebook server has the expected resource allocation.

**Acceptance Scenarios**:

1. **Given** a user is launching a workspace for an analysis, **When** the launch dialog appears, **Then** a resource profile selector is displayed showing all available profiles with their names, descriptions, and resource allocations (CPU and memory).
2. **Given** a user selects the "Standard" profile and launches, **When** the notebook server starts, **Then** the running server has the CPU and memory limits corresponding to the "Standard" profile.
3. **Given** a user launches a workspace without explicitly selecting a profile, **When** the notebook server starts, **Then** the default profile ("Exploratory") is applied automatically.
4. **Given** a user has a custom notebook image selected, **When** they also select a resource profile, **Then** both the custom image and the chosen resource profile are applied to the workspace.

---

### User Story 2 - Switch Resource Profile on an Active Workspace (Priority: P2)

A data scientist is working in their notebook and realizes they need more (or fewer) resources than their current profile provides — for example, they started with "Exploratory" but now need to train a model that requires more memory. From the portal, they can change the resource profile for their running workspace. The system informs them that switching requires a server restart, which will interrupt their running kernels. After the user confirms, the workspace restarts with the new resource allocation. Open notebooks and files are preserved (they persist on storage), but any in-memory state (running variables, loaded data) is lost.

**Why this priority**: Without switching, users must stop their workspace, re-launch with a different profile, and re-open their notebooks manually. This friction discourages users from right-sizing their resources and leads to either waste or performance bottlenecks. Depends on Story 1 (profiles must exist).

**Independent Test**: Can be tested by launching a workspace with a small profile, switching to a larger profile via the portal, confirming the restart warning, and verifying the restarted server has the new resource allocation while files are preserved.

**Acceptance Scenarios**:

1. **Given** a user has a running workspace with the "Exploratory" profile, **When** they select "Change Resource Profile" in the portal, **Then** they see the profile selector showing all available profiles with the current profile highlighted.
2. **Given** a user selects a different profile (e.g., "Compute-Intensive"), **When** they confirm the switch, **Then** the system displays a warning that the server will restart and in-memory state will be lost, and asks for confirmation.
3. **Given** the user confirms the profile switch, **When** the workspace restarts, **Then** the notebook server runs with the new resource allocation and all previously saved notebooks and files are intact.
4. **Given** a user selects the same profile that is already active, **When** they attempt to confirm, **Then** the system informs them no change is needed and does not restart the server.

---

### User Story 3 - View Current Resource Profile and Usage (Priority: P3)

A data scientist working in the portal wants to know which resource profile their workspace is currently using and how much of the allocated resources they are consuming. The portal displays the active profile name and a summary of resource utilization (e.g., "Using 2.1 GB of 4 GB memory, 1.2 of 2 CPUs") so users can make informed decisions about whether to switch profiles.

**Why this priority**: Visibility into current resource allocation and usage helps users make informed switching decisions. Without it, users switch profiles based on guesswork. Lower priority because users can still launch and switch profiles without this information — it enhances the experience rather than enabling core functionality.

**Independent Test**: Can be tested by launching a workspace, checking that the portal displays the active profile name and resource utilization metrics, running a memory-intensive computation, and verifying that the usage indicators update to reflect increased consumption.

**Acceptance Scenarios**:

1. **Given** a user has a running workspace, **When** they view the workspace in the portal, **Then** the active resource profile name is displayed.
2. **Given** a user has a running workspace, **When** they view the workspace details, **Then** current CPU and memory utilization are displayed alongside the allocated limits from the active profile.
3. **Given** a user's workspace is stopped, **When** they view the workspace in the portal, **Then** the last-used profile is shown (for reference when re-launching) but no utilization metrics are displayed.

---

### Edge Cases

- What happens when a user tries to switch profiles while a kernel is actively executing code? The system displays a warning that the kernel will be interrupted and in-memory state lost. The user must explicitly confirm before the switch proceeds.
- What happens when the cluster does not have enough resources to fulfill the requested profile? The workspace launch or switch fails with a clear message indicating insufficient cluster resources, suggesting the user try a smaller profile or wait for resources to free up.
- What happens when a platform administrator changes the available profiles while users have running workspaces? Running workspaces continue with their current resource allocation. The user sees the updated profile list only when they next launch or switch profiles. If a user's active profile is removed, the workspace continues running; on next restart, the user must select from the current profiles.
- What happens when a user has a workspace running on a profile that has been removed by an administrator? The workspace keeps running normally. The portal shows the profile name as it was when launched. When the workspace is stopped and the user tries to re-launch, they see only the currently available profiles and must select one.
- What happens when the resource usage display is temporarily unavailable (e.g., metrics service is down)? The portal shows the profile name but displays "Usage unavailable" for the utilization metrics, rather than failing entirely.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST display a resource profile selector during workspace launch, showing all available profiles with their names, descriptions, and resource allocations (CPU cores, memory).
- **FR-002**: System MUST apply the selected resource profile's CPU and memory limits to the notebook server when launching a workspace.
- **FR-003**: System MUST use a default resource profile ("Exploratory") when the user does not explicitly select one, ensuring backward compatibility with existing workspace launch behavior.
- **FR-004**: System MUST allow users to switch the resource profile of a running workspace, triggering a server restart with the new resource allocation.
- **FR-005**: System MUST warn users before a profile switch that the server will restart, in-memory kernel state will be lost, and require explicit confirmation before proceeding.
- **FR-006**: System MUST preserve all saved notebooks and files across profile switches (only in-memory state is lost).
- **FR-007**: System MUST display the active resource profile name in the portal for running workspaces.
- **FR-008**: System MUST display current CPU and memory utilization alongside the profile's allocated limits for running workspaces.
- **FR-009**: System MUST provide at least three predefined resource profiles out of the box, ranging from lightweight exploration to compute-intensive workloads.
- **FR-010**: System MUST allow platform administrators to define, modify, and remove resource profiles via a configuration file. Changes take effect after a backend restart without requiring code changes or full redeployment.
- **FR-011**: System MUST prevent launching a workspace if the requested profile's resources exceed available cluster capacity, providing a clear error message.
- **FR-012**: System MUST record which resource profile was used each time a workspace is launched, for auditing and usage analysis.

### Key Entities

- **Resource Profile**: A named, predefined resource configuration. Attributes: name, display description, CPU allocation (cores), memory allocation, default flag (at most one profile is the default), sort order. Profiles are platform-wide — defined by administrators and available to all authenticated users without role-based restrictions.
- **Workspace-Profile Association**: A record linking a workspace session to the resource profile it was launched with. Tracks which profile is active for the current or most recent session.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can select a resource profile and launch a workspace in the same number of steps as the current launch flow (profile selection adds no more than one additional interaction).
- **SC-002**: 95% of profile switches complete (server restart with new allocation) within 120 seconds.
- **SC-003**: All saved notebooks and files survive a profile switch with zero data loss.
- **SC-004**: Users can identify their current resource profile and see utilization metrics within 5 seconds of viewing the workspace.
- **SC-005**: The feature is fully backward compatible — users who do not interact with the profile selector experience no change in their workflow or resource allocation.
- **SC-006**: Platform administrators can add or modify resource profiles by editing a configuration file and restarting the backend, without requiring code changes or full redeployment.

## Clarifications

### Session 2026-03-01

- Q: Should access to resource profiles be restricted by user role, or are all profiles available to all users? → A: All profiles are visible and selectable by all authenticated users. Admins govern resource availability by curating which profiles exist.
- Q: How do administrators manage resource profiles — configuration file or portal admin UI? → A: Configuration file (YAML/properties) loaded at backend startup. Changes require a backend restart only, not a full redeployment.

## Assumptions

- Resource profiles are defined and managed by platform administrators, not by individual users. Users choose from the available set of profiles but cannot create custom resource configurations.
- The initial set of profiles is defined by the platform team and includes at least: "Exploratory" (lightweight), "Standard" (moderate), and "Compute-Intensive" (high resources). Exact CPU and memory values are an infrastructure concern and will be determined during planning.
- GPU-backed profiles are out of scope for this feature. Resource profiles cover CPU and memory only. GPU support may be addressed in a future feature.
- Profile switching requires a full notebook server restart. Hot-resizing of container resources without restart is not supported.
- The resource utilization display (Story 3) relies on cluster-level metrics being available. If the metrics infrastructure is not deployed, the utilization display gracefully degrades to showing only the profile name and allocated limits without live usage data.
- This feature is compatible with the custom notebook image feature (008). Users can independently select both a resource profile and a custom image for their workspace.
- The "Exploratory" profile matches the current default resource allocation, ensuring that existing workspaces and users who do not interact with the profile selector experience no change.
