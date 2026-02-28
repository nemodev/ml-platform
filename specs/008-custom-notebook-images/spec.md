# Feature Specification: Custom Notebook Images

**Feature Branch**: `008-custom-notebook-images`
**Created**: 2026-02-27
**Status**: Draft
**Input**: User description: "Custom notebook image builder feature. Users define a Python version and package list to build a custom notebook image from the platform's base image. The platform deploys a lightweight container registry by default but supports external registries via backend configuration. In-cluster image builds use an open-source builder with build progress monitoring and completion notifications."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Define and Build a Custom Notebook Image (Priority: P1)

As a data scientist, I want to define a custom notebook environment by selecting a Python version and specifying a list of packages, so that the platform builds a ready-to-use notebook image tailored to my project's needs without requiring me to manage container tooling.

**Why this priority**: This is the core value proposition. Without the ability to define and build a custom image, no other part of the feature is useful. Users currently have no way to customize their notebook environment — they must use the single built-in image with its fixed set of packages.

**Independent Test**: Can be fully tested by submitting a custom image definition (Python version + package list) through the UI, verifying the build completes successfully, and confirming the resulting image is stored in the registry.

**Acceptance Scenarios**:

1. **Given** a logged-in user on the custom image creation page, **When** they select a Python version (e.g., 3.11) and enter a list of packages (e.g., `scikit-learn==1.4.0, xgboost>=2.0`), **Then** the system validates the inputs and initiates a build job.
2. **Given** a build job has been submitted, **When** the build is in progress, **Then** the user can view real-time build progress (current stage, elapsed time) on the image detail page.
3. **Given** a build job has completed successfully, **When** the user navigates to their image list, **Then** the new image appears with status "Ready" and displays the Python version and package summary.
4. **Given** a build job fails (e.g., invalid package name, dependency conflict), **When** the build finishes, **Then** the user sees a clear error message with the relevant build log excerpt and can retry or edit the definition.

---

### User Story 2 - Launch a Workspace with a Custom Image (Priority: P2)

As a data scientist, I want to select one of my custom notebook images when launching a workspace, so that my notebook environment has exactly the packages and Python version my project requires.

**Why this priority**: Building images without being able to use them provides no value. This story closes the loop — once a custom image is ready, the user must be able to launch a workspace with it. It depends on P1 (images must exist before they can be selected).

**Independent Test**: Can be tested by selecting an already-built custom image during workspace launch and verifying the notebook server starts with the expected Python version and packages installed.

**Acceptance Scenarios**:

1. **Given** a user has at least one custom image with status "Ready", **When** they launch a new workspace for an analysis, **Then** they see an image selector showing the default platform image and their custom images.
2. **Given** a user selects a custom image and launches a workspace, **When** the notebook server starts, **Then** the running environment contains the specified Python version and all requested packages.
3. **Given** a user does not select a custom image, **When** they launch a workspace, **Then** the platform uses the default built-in image (backward compatible with existing behavior).

---

### User Story 3 - Monitor Build Progress and Receive Notifications (Priority: P2)

As a data scientist, I want to monitor the progress of my image build and be notified when it finishes, so that I don't have to keep checking manually and can start working as soon as my environment is ready.

**Why this priority**: Image builds can take several minutes. Without progress visibility and notifications, users are left guessing and repeatedly refreshing. This is a core usability concern that directly impacts user satisfaction, grouped with P2 because it enhances the build experience from P1.

**Independent Test**: Can be tested by triggering a build and verifying that progress updates appear in real-time on the build detail page, and that a notification (in-app) is delivered upon completion or failure.

**Acceptance Scenarios**:

1. **Given** a build is in progress, **When** the user views the image detail page, **Then** they see the current build stage (e.g., "Installing packages", "Finalizing image"), a progress indicator, and elapsed time.
2. **Given** a build completes successfully, **When** the user is on any page in the platform, **Then** they receive an in-app notification that their image is ready.
3. **Given** a build fails, **When** the user is on any page in the platform, **Then** they receive an in-app notification with a summary of the failure and a link to the build details.

---

### User Story 4 - Manage Custom Images (Priority: P3)

As a data scientist, I want to view, rebuild, and delete my custom images, so that I can keep my image library organized and update images when new package versions are needed.

**Why this priority**: Lifecycle management becomes important as users accumulate images. Without it, the image list grows stale and cluttered. This is a natural follow-on once building and launching are working.

**Independent Test**: Can be tested by viewing a list of previously built images, rebuilding one to pick up updated packages, and deleting an obsolete image.

**Acceptance Scenarios**:

1. **Given** a user has multiple custom images, **When** they navigate to the image management page, **Then** they see a list of their images with name, Python version, package count, status, and creation date.
2. **Given** a user selects an existing image, **When** they choose "Rebuild", **Then** the system creates a new build using the same definition (Python version + packages) with the latest available package versions.
3. **Given** a user selects an image that is not currently in use by any active workspace, **When** they choose "Delete", **Then** the system removes the image from the registry and the image list after confirmation.
4. **Given** a user selects an image that is currently in use by an active workspace, **When** they choose "Delete", **Then** the system warns that the image is in use and prevents deletion until the workspace is stopped.

---

### User Story 5 - Configure External Container Registry (Priority: P3)

As a platform administrator, I want to configure the platform to use an existing external container registry instead of the built-in one, so that our organization can leverage its established registry infrastructure, policies, and storage.

**Why this priority**: Many organizations already have a container registry. Forcing them to use a second one creates operational overhead. This is important for enterprise adoption but not required for the core feature to function — the built-in registry works out of the box.

**Independent Test**: Can be tested by updating the backend configuration file with an external registry endpoint and credentials, restarting the backend, building a custom image, and verifying it is pushed to the external registry.

**Acceptance Scenarios**:

1. **Given** the platform is deployed with default settings, **When** no external registry is configured, **Then** the platform uses the built-in container registry for all image operations.
2. **Given** an administrator has configured an external registry endpoint and credentials in the backend configuration file, **When** the backend starts, **Then** all image build and pull operations use the external registry.
3. **Given** an external registry is configured with invalid credentials, **When** the backend starts, **Then** the system logs a clear error indicating registry authentication failed and falls back to the built-in registry with a warning.

---

### Edge Cases

- What happens when a user submits a package list with conflicting dependencies (e.g., `numpy==1.24 tensorflow==2.15` where tensorflow requires a different numpy version)? The build should fail with a clear dependency resolution error from the package manager, shown in the build logs.
- What happens when the in-cluster registry runs out of storage? The build should fail with a clear "insufficient storage" error, and the platform should expose registry storage usage to administrators.
- What happens when a build is still in progress and the user submits another build for the same image definition? The system should queue the new build and inform the user that a build is already running.
- What happens when a user tries to launch a workspace with an image whose registry is unreachable? The workspace launch should fail with a clear error indicating the image could not be pulled, suggesting the user check registry connectivity or use the default image.
- What happens when the selected Python version is no longer supported by the base image? The system should validate Python version availability before starting the build and inform the user of supported versions.
- What happens when the user's package list is empty (only Python version selected)? The build should succeed, producing an image with the selected Python version and the base platform tooling but no additional user packages.
- What happens when a build exceeds 60 minutes? The system automatically cancels the build job, marks it as failed with a "timeout" reason, and notifies the user. The user can retry with a reduced package list or report the issue.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a user interface for creating a custom notebook image definition by selecting a Python version, specifying a list of packages (with optional version constraints), and optionally providing an extra pip index URL for private package sources.
- **FR-002**: System MUST validate the image definition before starting a build — confirming the Python version is supported and the package list syntax is well-formed.
- **FR-003**: System MUST build custom images from the platform's base image, inheriting all platform tooling (Spark support, MLflow integration, Delta Lake libraries, MinIO connectivity).
- **FR-004**: System MUST support at least Python 3.10, 3.11, and 3.12 as selectable versions for custom images.
- **FR-005**: System MUST execute image builds within the cluster without requiring access to an external Docker daemon.
- **FR-006**: System MUST display real-time build progress including current stage, elapsed time, and a progress indicator.
- **FR-007**: System MUST deliver in-app notifications to the user upon build completion (success or failure).
- **FR-008**: System MUST store build logs and make them accessible to the user for both successful and failed builds.
- **FR-009**: System MUST allow users to select a custom image (or the default platform image) when launching a workspace.
- **FR-010**: System MUST deploy a built-in lightweight container registry by default as part of the platform installation.
- **FR-011**: System MUST support configuring an external container registry (endpoint and authentication credentials) via the backend configuration file, bypassing the built-in registry when configured.
- **FR-012**: System MUST allow users to view a list of their custom images with status, Python version, package summary, and creation date.
- **FR-013**: System MUST allow users to rebuild an existing image definition to pick up updated package versions.
- **FR-014**: System MUST allow users to delete custom images that are not in use by any active workspace.
- **FR-015**: System MUST prevent deletion of images that are currently in use by active workspaces, with a clear message explaining why.
- **FR-016**: System MUST handle concurrent build requests by enforcing a limit of 1 active build per user and 3 active builds cluster-wide, queuing additional requests and informing users of their queue position.
- **FR-017**: System MUST ensure custom images used by active workspaces remain available in the registry regardless of image management operations by other users.
- **FR-018**: System MUST enforce a maximum build duration of 60 minutes, automatically cancelling builds that exceed this limit and notifying the user with a timeout error.

### Key Entities

- **Image Definition**: A user-created specification for a custom notebook image. Attributes: name, Python version, package list (with optional version pins), extra pip index URL (optional), owner, creation date, last build date. Images are user-global — available for any of the owner's analyses, not scoped to a specific analysis.
- **Build Job**: A record of an image build attempt. Attributes: associated image definition, status (queued, building, succeeded, failed), progress stage, start time, end time, build logs, resulting image reference.
- **Registry Configuration**: Platform-level configuration for the container registry. Attributes: registry type (built-in or external), endpoint URL, authentication credentials, storage limits.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can define a custom image (select Python version + enter package list) and submit a build in under 2 minutes.
- **SC-002**: 90% of image builds with valid package definitions complete successfully on the first attempt.
- **SC-003**: Users receive build completion notifications within 30 seconds of the build finishing.
- **SC-004**: Users can launch a workspace with a custom image in the same number of steps as launching with the default image (no additional complexity).
- **SC-005**: Custom image workspaces start successfully and contain all specified packages at least 95% of the time.
- **SC-006**: Platform administrators can switch between built-in and external registry by updating a single configuration file and restarting, with no data migration required.
- **SC-007**: The feature is fully backward compatible — existing users who do not create custom images experience no change in their workflow.

## Clarifications

### Session 2026-02-27

- Q: Are custom images scoped to a specific analysis or global to the user? → A: User-global — images belong to the user and can be selected for any analysis.
- Q: What is the maximum allowed build duration before automatic cancellation? → A: 60 minutes.
- Q: Should there be a per-user limit on custom images? → A: No limit — rely on registry storage monitoring and admin intervention.
- Q: How many builds can run concurrently? → A: 1 concurrent build per user, max 3 concurrent builds cluster-wide; additional builds are queued.
- Q: Should users be able to configure a private pip index for package installation? → A: Yes — optional extra index URL per image definition.

## Assumptions

- The platform's base notebook image will serve as the mandatory parent for all custom images. Users cannot provide arbitrary Dockerfiles — they can only customize Python version and installed packages on top of the base image. This ensures all custom images inherit platform tooling (Spark, MLflow, Delta Lake, MinIO).
- The in-cluster image builder (Kaniko) is licensed under Apache 2.0, permitting commercial use without code modification. Note: the upstream GoogleContainerTools/kaniko repository is archived; the Chainguard-maintained fork is the active continuation.
- The built-in container registry is a lightweight, open-source registry suitable for single-cluster deployments. It does not include advanced features like vulnerability scanning or replication — organizations needing those capabilities should configure an external registry.
- Build notifications are in-app only (e.g., toast/banner within the platform UI). Email or external notification channels are out of scope for this feature.
- Image builds are scoped to individual users — a user's custom images are visible only to that user. Shared/team images are out of scope.
- The feature applies to notebook workspaces only. Using custom images for Airflow pipeline workers or Spark executors is out of scope for this feature and may be addressed separately.
- Python version selection offers a curated list maintained by the platform team, not arbitrary versions. The initial set is Python 3.10, 3.11, and 3.12.
- Package installation uses pip. Support for conda or other package managers is out of scope. Users may optionally specify one extra pip index URL per image definition for private/internal packages.
- The platform continues to function with the default notebook image if no custom images have been created. The custom image feature is entirely opt-in.
- There is no per-user limit on the number of custom images. Registry storage is monitored at the infrastructure level, and administrators handle capacity issues as needed.

## Dependencies

- Existing workspace launch flow (Feature 002 - JupyterHub Notebooks) must support image selection override.
- The platform's base notebook image build process (infrastructure/docker/notebook-image/) must be adapted to produce version-specific base images for each supported Python version.
- Cluster must have sufficient resources to run image build jobs alongside normal workloads.
