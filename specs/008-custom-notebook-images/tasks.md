# Tasks: Custom Notebook Images

**Input**: Design documents from `/specs/008-custom-notebook-images/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api.yaml, quickstart.md

**Tests**: Not explicitly requested in the feature specification. Test tasks are omitted.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `backend/src/main/java/com/mlplatform/`
- **Frontend**: `frontend/src/app/`
- **Infrastructure**: `infrastructure/`
- **Resources**: `backend/src/main/resources/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Deploy the in-cluster container registry and prepare base images for multiple Python versions. This infrastructure is required before any custom image can be built.

- [x] T001 Create Docker Distribution registry Deployment manifest in infrastructure/k8s/platform/base/registry-deployment.yaml — single-pod deployment using `registry:2` image with MinIO S3 storage backend (`s3://ml-platform/registry/`), resource requests (256Mi/250m), liveness probe on `/v2/`
- [x] T002 [P] Create registry ClusterIP Service manifest in infrastructure/k8s/platform/base/registry-service.yaml — expose port 5000 targeting the registry pod
- [x] T003 [P] Create imagePullSecret template in infrastructure/k8s/platform/base/registry-credentials-secret.yaml — type `kubernetes.io/dockerconfigjson` for registry authentication, with placeholder values for deployment script substitution
- [x] T004 [P] Create Kaniko RBAC resources in infrastructure/k8s/platform/base/kaniko-rbac.yaml — ServiceAccount `kaniko-builder`, Role with permissions to create/get/delete Jobs and Pods, read ConfigMaps and Secrets, RoleBinding in `ml-platform` namespace
- [x] T005 Update Kustomize base to include new resources in infrastructure/k8s/platform/base/kustomization.yaml — add registry-deployment.yaml, registry-service.yaml, registry-credentials-secret.yaml, kaniko-rbac.yaml
- [x] T006 Parameterize notebook Dockerfile for Python version in infrastructure/docker/notebook-image/Dockerfile — add `ARG PYTHON_VERSION=3.11` and update base image to `jupyter/scipy-notebook:python-${PYTHON_VERSION}`, ensure all subsequent layers are compatible with the version argument
- [x] T007 Update build-images.sh to support multi-Python base image builds in infrastructure/installer/build-images.sh — add `--python-versions` flag that iterates over 3.10, 3.11, 3.12 and builds/pushes `<registry>/ml-platform/notebook-base:python-X.XX` for each version; default notebook image (`ml-platform-notebook:latest`) remains Python 3.11 for backward compatibility
- [x] T008 Add registry deployment step to deploy-full-stack.sh in infrastructure/scripts/deploy-full-stack.sh — deploy registry manifests, create registry-credentials Secret from config variables, wait for registry pod readiness
- [x] T009 [P] Add registry configuration variables to installer config in infrastructure/installer/config.env — add `REGISTRY_TYPE`, `REGISTRY_ENDPOINT`, `REGISTRY_USERNAME`, `REGISTRY_PASSWORD`, `REGISTRY_INSECURE` with built-in defaults

**Checkpoint**: Registry is deployed, base images for Python 3.10/3.11/3.12 are built and pushed to the registry.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Backend entities, repositories, DTOs, configuration, and exception handling that ALL user stories depend on. Must complete before any story work begins.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T010 Create Flyway migration V009 in backend/src/main/resources/db/migration/V009__create_notebook_images_and_builds.sql — create `notebook_images` and `image_builds` tables with indexes per data-model.md, including cascade delete on image_builds FK
- [x] T011 [P] Create NotebookImageStatus enum in backend/src/main/java/com/mlplatform/model/NotebookImageStatus.java — values: PENDING, BUILDING, READY, FAILED
- [x] T012 [P] Create ImageBuildStatus enum in backend/src/main/java/com/mlplatform/model/ImageBuildStatus.java — values: QUEUED, BUILDING, SUCCEEDED, FAILED, CANCELLED
- [x] T013 Create NotebookImage JPA entity in backend/src/main/java/com/mlplatform/model/NotebookImage.java — UUID PK with @PrePersist, @ManyToOne(fetch=LAZY) to User, @Enumerated(EnumType.STRING) status, all fields per data-model.md, @PrePersist sets id/createdAt/updatedAt, follow existing entity patterns (Analysis.java, ModelDeployment.java)
- [x] T014 [P] Create ImageBuild JPA entity in backend/src/main/java/com/mlplatform/model/ImageBuild.java — UUID PK with @PrePersist, @ManyToOne(fetch=LAZY) to NotebookImage, @Enumerated(EnumType.STRING) status, all fields per data-model.md including k8sJobName, buildLogs as TEXT
- [x] T015 [P] Create NotebookImageRepository in backend/src/main/java/com/mlplatform/repository/NotebookImageRepository.java — extend JpaRepository<NotebookImage, UUID>, add: findByUserIdOrderByCreatedAtDesc, findByIdAndUserId, findByUserIdAndName, existsByUserIdAndName
- [x] T016 [P] Create ImageBuildRepository in backend/src/main/java/com/mlplatform/repository/ImageBuildRepository.java — extend JpaRepository<ImageBuild, UUID>, add: findByNotebookImageIdOrderByCreatedAtDesc, findByIdAndNotebookImageId, findByStatusIn, countByNotebookImage_UserIdAndStatusIn, countByStatusIn, findFirstByStatusOrderByCreatedAtAsc
- [x] T017 [P] Create all request/response DTOs as Java records in backend/src/main/java/com/mlplatform/dto/ — CreateNotebookImageRequest.java, UpdateNotebookImageRequest.java, NotebookImageDto.java, NotebookImageDetailDto.java, ImageBuildDto.java, ImageBuildDetailDto.java (with elapsedSeconds computation), NotificationDto.java — all per contracts/api.yaml schemas
- [x] T018 [P] Create NotebookImageConfig with @ConfigurationProperties in backend/src/main/java/com/mlplatform/config/NotebookImageConfig.java — bind to `services.notebook-images.*`, nested classes for RegistryProperties (type, endpoint, username, password, insecure) and BuildProperties (namespace, timeoutMinutes, maxConcurrentBuilds, kanikoImage, resource limits), pythonVersions list, baseImagePrefix string
- [x] T019 [P] Create ImageBuildUnavailableException in backend/src/main/java/com/mlplatform/exception/ImageBuildUnavailableException.java — extend RuntimeException, follow pattern of existing KServeUnavailableException
- [x] T020 Add ImageBuildUnavailableException handler to GlobalExceptionHandler in backend/src/main/java/com/mlplatform/config/GlobalExceptionHandler.java — map to 503 SERVICE_UNAVAILABLE, follow existing pattern for KServeUnavailableException
- [x] T021 Add notebook-images configuration block to application.yaml in backend/src/main/resources/application.yaml — add full `services.notebook-images` section per data-model.md configuration model, with built-in registry defaults
- [x] T022 [P] Create ContainerRegistryService in backend/src/main/java/com/mlplatform/service/ContainerRegistryService.java — health check via GET /v2/ on registry endpoint, getImageReference() to build full image references, deleteImage() for registry API image deletion; handle both built-in and external registry modes from config; use RestTemplate; handle connection failures gracefully with logging

**Checkpoint**: Foundation ready — database schema deployed, entities mapped, config bound, registry service operational. User story implementation can now begin.

---

## Phase 3: User Story 1 — Define and Build a Custom Notebook Image (Priority: P1) 🎯 MVP

**Goal**: Users can create a custom image definition (Python version + packages), trigger a build, and see the build complete with the resulting image stored in the registry.

**Independent Test**: Create an image definition via the UI, trigger a build, verify build completes and image is stored in the registry. Check build logs are accessible.

### Implementation for User Story 1

- [x] T023 [US1] Implement NotebookImageService in backend/src/main/java/com/mlplatform/service/NotebookImageService.java — createImage (validate pythonVersion against config list, validate package syntax, check unique name per user, save with PENDING status), getImage (by id + userId), listImages (by userId), getImageWithLatestBuild (join latest ImageBuild); use @Transactional, throw ResponseStatusException for validation failures (400) and duplicates (409)
- [x] T024 [US1] Implement ImageBuildService in backend/src/main/java/com/mlplatform/service/ImageBuildService.java — triggerBuild (check per-user limit 1 active build, check cluster-wide limit 3, create ImageBuild with QUEUED status, return 202; throw 409 for per-user conflict, 429 for cluster capacity), createKanikoJob (generate Dockerfile as ConfigMap content: FROM base:python-X.XX + RUN pip install packages + optional --extra-index-url, create K8s Job via BatchV1Api with Kaniko executor, activeDeadlineSeconds=3600, mount registry credentials), refreshBuildStatus (poll K8s Job status, read pod logs, parse Kaniko output for progress stage, update ImageBuild entity, update parent NotebookImage status on completion), getBuild, listBuilds, getBuildLogs; follow KServeService patterns for K8s API interaction
- [x] T025 [US1] Implement ImageBuildScheduler in backend/src/main/java/com/mlplatform/service/ImageBuildScheduler.java — @Scheduled(fixedDelay=10000) processQueue: find QUEUED builds in FIFO order, promote to BUILDING if cluster slots available, call ImageBuildService.createKanikoJob; @Scheduled(fixedDelay=30000) refreshActiveBuilds: find all BUILDING builds, call refreshBuildStatus for each; @Scheduled(fixedDelay=60000) enforceTimeouts: find BUILDING builds where startedAt + 60min < now, cancel K8s Job, mark as CANCELLED with timeout message
- [x] T026 [US1] Implement NotebookImageController in backend/src/main/java/com/mlplatform/controller/NotebookImageController.java — POST /api/v1/notebook-images (create, return 201), GET /api/v1/notebook-images (list), GET /api/v1/notebook-images/{imageId} (get detail with latest build), PUT /api/v1/notebook-images/{imageId} (update definition), DELETE /api/v1/notebook-images/{imageId} (delete), GET /api/v1/notebook-images/python-versions (return config list), POST /api/v1/notebook-images/{imageId}/builds (trigger, return 202), GET /api/v1/notebook-images/{imageId}/builds (list), GET /api/v1/notebook-images/{imageId}/builds/{buildId} (get detail), GET /api/v1/notebook-images/{imageId}/builds/{buildId}/logs (return text/plain); all endpoints use @AuthenticationPrincipal Jwt, extract userId via UserService
- [x] T027 [US1] Create notebook-image.service.ts in frontend/src/app/core/services/notebook-image.service.ts — injectable service with HttpClient methods: listImages(), createImage(request), getImage(imageId), updateImage(imageId, request), deleteImage(imageId), listPythonVersions(), triggerBuild(imageId), listBuilds(imageId), getBuild(imageId, buildId), getBuildLogs(imageId, buildId); all targeting /api/v1/notebook-images/* endpoints, return typed Observables
- [x] T028 [US1] Create create-image-dialog component in frontend/src/app/features/notebook-images/create-image-dialog/ — standalone component with @Input/@Output pattern (like deploy-dialog), form fields: name (text input), pythonVersion (select dropdown populated from listPythonVersions()), packages (textarea for requirements.txt format), extraPipIndexUrl (optional text input); validate required fields; on submit call createImage() then triggerBuild(); emit close/created events; custom CSS following existing dialog patterns (fixed positioning, backdrop, .error styling)
- [x] T029 [US1] Create notebook-images.component (list page) in frontend/src/app/features/notebook-images/ — standalone component (.ts, .html, .scss), display table/cards of user's images with columns: name, Python version, package count, status (with color-coded badge), created date; "New Image" button opens create-image-dialog; status badge shows PENDING/BUILDING/READY/FAILED with appropriate colors; auto-refresh every 5s when any image has BUILDING status (timer-based polling like deployments.component.ts); empty state message when no images exist
- [x] T030 [US1] Create build-detail component in frontend/src/app/features/notebook-images/build-detail/ — standalone component with @Input buildId and imageId, display build status, progress stage, elapsed time (computed from startedAt), build logs in scrollable pre/code block; poll getBuild() every 5s while status is QUEUED or BUILDING; show error message prominently when FAILED; "View Logs" expandable section calling getBuildLogs(); back navigation to image list
- [x] T031 [US1] Add /notebook-images route to app.routes.ts in frontend/src/app/app.routes.ts — lazy-loaded route with authGuard: `{ path: 'notebook-images', canActivate: [authGuard], loadComponent: () => import('./features/notebook-images/notebook-images.component').then(m => m.NotebookImagesComponent) }`; add sub-route for build detail: `/notebook-images/:imageId/builds/:buildId`
- [x] T032 [US1] Add "Custom Images" navigation link to the app sidebar/header in frontend/src/app/app.component.html — add navigation item pointing to /notebook-images, positioned after existing nav items (Dashboard, Analyses, Models, Pipelines)

**Checkpoint**: User Story 1 complete. Users can create image definitions, trigger builds, see build progress and logs, and view completed images in a list. The full build pipeline works: API → K8s Job (Kaniko) → Registry → status tracking.

---

## Phase 4: User Story 2 — Launch a Workspace with a Custom Image (Priority: P2)

**Goal**: Users can select a custom image when launching a workspace, and JupyterHub spawns the notebook server with that image.

**Independent Test**: Select a READY custom image during workspace launch, verify the notebook server starts with the correct Python version and packages.

**Dependencies**: Requires US1 (images must exist to select them).

### Implementation for User Story 2

- [x] T033 [US2] Update JupyterHub Helm values for image override support in infrastructure/helm/jupyterhub/values.yaml — add `imagePullSecrets: [{name: "registry-credentials"}]` under `singleuser`, add hub extraConfig to enable KubeSpawner image override: `c.KubeSpawner.image_pull_secrets = ['registry-credentials']` and allow image field override in spawn options
- [x] T034 [US2] Modify JupyterHubService.spawnNamedServer() in backend/src/main/java/com/mlplatform/service/JupyterHubService.java — add optional `String imageReference` parameter, when non-null include `{"image": imageReference}` in the POST body to JupyterHub's spawn endpoint; preserve backward compatibility (null imageReference = use default image)
- [x] T035 [US2] Modify WorkspaceService.launchWorkspace() in backend/src/main/java/com/mlplatform/service/WorkspaceService.java — accept optional `UUID notebookImageId` parameter, if provided: look up NotebookImage by id and userId, verify status is READY (throw 400 if not), extract imageReference, pass to JupyterHubService.spawnNamedServer(); if null, use default behavior
- [x] T036 [US2] Modify WorkspaceController LaunchWorkspaceRequest in backend/src/main/java/com/mlplatform/controller/WorkspaceController.java — add optional `UUID notebookImageId` field to the LaunchWorkspaceRequest record, pass it to WorkspaceService.launchWorkspace()
- [x] T037 [US2] Add image selector to notebooks.component in frontend/src/app/features/notebooks/notebooks.component.ts and .html — before the "Launch Workspace" button, add a dropdown/select that shows "Default Platform Image" as first option plus all user's READY custom images (fetched from notebook-image.service.listImages()); pass selected notebookImageId to workspace launch API call; show image name and Python version in dropdown options

**Checkpoint**: User Story 2 complete. Users can launch workspaces with custom images. Backward compatibility preserved — default image is selected when no custom image is chosen.

---

## Phase 5: User Story 3 — Monitor Build Progress and Receive Notifications (Priority: P2)

**Goal**: Users see real-time build progress on the detail page and receive in-app notifications (toast/banner) on build completion or failure, even when on other pages.

**Independent Test**: Trigger a build, navigate to a different page, wait for completion, verify a notification banner appears.

**Dependencies**: Requires US1 (builds must exist to monitor them).

### Implementation for User Story 3

- [x] T038 [US3] Implement NotificationController in backend/src/main/java/com/mlplatform/controller/NotificationController.java — GET /api/v1/notifications with optional `since` query parameter (ISO-8601 timestamp, default 60s ago), query ImageBuild records for the authenticated user where completedAt > since and status IN (SUCCEEDED, FAILED), map to NotificationDto with type BUILD_SUCCEEDED/BUILD_FAILED, message, resourceId (notebookImageId), resourceName (image name), timestamp (completedAt)
- [x] T039 [US3] Create notification.service.ts in frontend/src/app/core/services/notification.service.ts — injectable service, maintains lastPolledAt timestamp, pollNotifications() method calls GET /api/v1/notifications?since={lastPolledAt}, exposes notifications$ Observable (BehaviorSubject) with new notification events, clearNotification(id) method
- [x] T040 [US3] Create notification-banner component in frontend/src/app/shared/notification-banner/ — standalone component (.ts, .html, .scss), subscribes to notification.service.notifications$, renders notification banners with type-appropriate styling (green for success, red for failure), message text, link to image detail, auto-dismiss after 10 seconds or manual close; position fixed top-right with z-index above content; follow existing error styling patterns (#fff1f2 for error, add #f0fdf4 for success)
- [x] T041 [US3] Integrate notification polling in app.component in frontend/src/app/app.component.ts and .html — inject NotificationService, start polling with timer(5000, 30000) in ngOnInit (first poll after 5s, then every 30s), stop on destroy; include `<app-notification-banner>` in app.component.html template
- [x] T042 [US3] Enhance build-detail component with progress indicator in frontend/src/app/features/notebook-images/build-detail/ — add a visual progress bar or step indicator showing build stages (Queued → Building Base → Installing Packages → Pushing Image → Complete), highlight current stage based on progressStage field; show animated spinner for active stage; display elapsed time as "Xm Ys" format updated every second via timer

**Checkpoint**: User Story 3 complete. Users see real-time build progress on the detail page and receive toast notifications on any page when builds complete or fail.

---

## Phase 6: User Story 4 — Manage Custom Images (Priority: P3)

**Goal**: Users can view their image list with full details, rebuild images for updated packages, and delete images no longer needed (with in-use protection).

**Independent Test**: View image list, rebuild an existing image, delete an image not in use, attempt to delete an image in use and see the warning.

**Dependencies**: Requires US1 (images must exist to manage).

### Implementation for User Story 4

- [x] T043 [US4] Add update endpoint logic to NotebookImageService in backend/src/main/java/com/mlplatform/service/NotebookImageService.java — updateImage method: find by id+userId, validate updated fields (pythonVersion, name uniqueness), update entity, save; return updated DTO
- [x] T044 [US4] Add deletion with in-use guard to NotebookImageService in backend/src/main/java/com/mlplatform/service/NotebookImageService.java — deleteImage method: find by id+userId, check if image is referenced by any active workspace (query WorkspaceRepository for workspaces with status IN (PENDING, RUNNING, IDLE) that use this image's reference), throw 409 with clear message if in use, otherwise delete from DB (cascade deletes builds) and call ContainerRegistryService.deleteImage() to remove from registry
- [x] T045 [US4] Add notebookImageId field to Workspace entity in backend/src/main/java/com/mlplatform/model/Workspace.java — add optional UUID notebookImageId column (nullable), create Flyway migration V010 to add the column: `ALTER TABLE workspaces ADD COLUMN notebook_image_id UUID REFERENCES notebook_images(id)`; update WorkspaceService to persist the selected image ID when launching with a custom image
- [x] T046 [US4] Add rebuild functionality — ensure the existing POST /api/v1/notebook-images/{imageId}/builds endpoint in NotebookImageController already supports triggering a new build for an existing image; no new endpoint needed — the triggerBuild in ImageBuildService creates a new ImageBuild with the current definition, producing a fresh image with latest package versions
- [x] T047 [US4] Enhance notebook-images.component with management actions in frontend/src/app/features/notebook-images/notebook-images.component.ts and .html — add "Rebuild" button per image row (calls triggerBuild, shows building state), add "Delete" button per image row (shows confirmation dialog, calls deleteImage, handles 409 error with in-use message), add "Edit" link to open create-image-dialog in edit mode with pre-populated fields; disable delete button while status is BUILDING

**Checkpoint**: User Story 4 complete. Users can fully manage their image library — view, edit definitions, rebuild for updates, and delete with in-use protection.

---

## Phase 7: User Story 5 — Configure External Container Registry (Priority: P3)

**Goal**: Administrators can point the platform to an external container registry via backend configuration, bypassing the built-in registry.

**Independent Test**: Set external registry config in application.yaml, restart backend, trigger a build, verify image is pushed to the external registry.

**Dependencies**: None beyond foundational phase (independent of other user stories).

### Implementation for User Story 5

- [x] T048 [US5] Implement external registry mode in ContainerRegistryService in backend/src/main/java/com/mlplatform/service/ContainerRegistryService.java — on @PostConstruct: read registry type from config, if `external`: validate endpoint reachability via GET /v2/ with credentials, log success or warning on failure; getImageReference() must prefix with external endpoint when configured; ensure Kaniko Jobs receive the correct `--destination` and registry credentials for external target
- [x] T049 [US5] Update ImageBuildService for external registry credentials in backend/src/main/java/com/mlplatform/service/ImageBuildService.java — when creating Kaniko Job, if registry type is external: create or reference a K8s Secret with the external registry's docker config.json (username/password from NotebookImageConfig), mount at /kaniko/.docker/config.json; for built-in registry: mount the existing registry-credentials Secret
- [x] T050 [US5] Update JupyterHub imagePullSecrets for external registry in infrastructure/scripts/deploy-full-stack.sh — when REGISTRY_TYPE=external in config.env, create the registry-credentials K8s Secret from REGISTRY_ENDPOINT/USERNAME/PASSWORD instead of the built-in defaults; JupyterHub singleuser.imagePullSecrets already references "registry-credentials" (from T033)
- [x] T051 [US5] Add registry configuration section to installer in infrastructure/installer/install.sh — add deployment logic: if REGISTRY_TYPE=builtin, deploy registry manifests; if REGISTRY_TYPE=external, skip registry deployment but still create imagePullSecret from external credentials; update config.env.example with documented variables

**Checkpoint**: User Story 5 complete. Administrators can switch between built-in and external registries via configuration. No data migration required.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Final integration, edge case handling, and deployment verification

- [x] T052 Add dev profile mock mode to NotebookImageService and ImageBuildService in backend/src/main/java/com/mlplatform/service/ — follow existing pattern (environment.matchesProfiles("dev")) to return mock data when no K8s cluster is available, enabling frontend development without infrastructure
- [x] T053 Verify backend builds successfully by running `cd backend && ./gradlew build` — ensure all new entities, services, controllers compile without errors and Flyway migration applies cleanly
- [x] T054 Verify frontend builds successfully by running `cd frontend && npm run build` — ensure all new components, services, routes compile without errors
- [ ] T055 End-to-end deployment verification — deploy full stack with deploy-full-stack.sh, verify: registry pod is running, base images are in registry, create an image definition via API, trigger build, monitor to completion, launch workspace with custom image, verify Python version and packages in running notebook

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3-7)**: All depend on Foundational phase completion
  - US1 (Phase 3): Can start after Foundational
  - US2 (Phase 4): Depends on US1 (needs built images to select)
  - US3 (Phase 5): Depends on US1 (needs builds to monitor)
  - US4 (Phase 6): Depends on US1 (needs images to manage)
  - US5 (Phase 7): Can start after Foundational (independent of other stories)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

```
Phase 2 (Foundational) ──┬──→ US1 (P1, MVP) ──┬──→ US2 (P2)
                          │                     ├──→ US3 (P2)
                          │                     └──→ US4 (P3)
                          └──→ US5 (P3) ────────────→ Polish
```

- **US1**: Gateway story — all other stories (except US5) depend on it
- **US2, US3, US4**: Can proceed in parallel once US1 is complete
- **US5**: Fully independent — can proceed in parallel with US1

### Within Each User Story

- Services before controllers
- Controllers before frontend services
- Frontend services before frontend components
- Backend complete before frontend integration

### Parallel Opportunities

**Phase 1**: T002, T003, T004, T009 can all run in parallel with T001
**Phase 2**: T011-T012, T013-T014, T015-T016, T017, T018, T019 can all run in parallel after T010
**Phase 3**: T027 (frontend service) can start in parallel once T026 (controller) is done
**Phase 3**: T028, T029, T030 (frontend components) can be built in parallel
**After US1**: US2, US3, US4 can proceed in parallel; US5 is already independent

---

## Parallel Example: User Story 1

```bash
# After Phase 2 foundational is complete, launch backend services in sequence:
Task T023: "NotebookImageService (CRUD + validation)"
Task T024: "ImageBuildService (K8s Job orchestration)"
Task T025: "ImageBuildScheduler (queue + timeouts)"
Task T026: "NotebookImageController (REST endpoints)"

# Then launch frontend tasks in parallel:
Task T027: "notebook-image.service.ts (HTTP client)"  # must be first
# Then in parallel:
Task T028: "create-image-dialog component"
Task T029: "notebook-images.component (list page)"
Task T030: "build-detail component"
# Then:
Task T031: "app.routes.ts update"
Task T032: "Navigation link in app.component"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (registry + base images)
2. Complete Phase 2: Foundational (entities, config, DTOs)
3. Complete Phase 3: User Story 1 (define + build)
4. **STOP and VALIDATE**: Create an image, trigger build, verify it completes and appears in registry
5. Deploy/demo if ready — this is the core value proposition

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready
2. Add US1 (Define & Build) → Test independently → Deploy/Demo (MVP!)
3. Add US2 (Launch Workspace) → Test independently → Deploy/Demo
4. Add US3 (Notifications) → Test independently → Deploy/Demo
5. Add US4 (Image Management) → Test independently → Deploy/Demo
6. Add US5 (External Registry) → Test independently → Deploy/Demo
7. Each story adds value without breaking previous stories

### Suggested Order for Solo Developer

1. Phase 1 (Setup) → Phase 2 (Foundational)
2. US1 (must-have MVP)
3. US2 (close the loop — launch with custom image)
4. US3 (notifications while US2 testing runs)
5. US4 (lifecycle management)
6. US5 (external registry — can be deferred if not needed immediately)
7. Phase 8 (Polish)

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks in same phase
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable (after its dependencies)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Backend dev profile mock mode (T052) enables frontend development without K8s cluster
- Total: 55 tasks across 8 phases
