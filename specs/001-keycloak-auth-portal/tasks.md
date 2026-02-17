# Tasks: Keycloak Auth & Portal Shell

**Input**: Design documents from `/specs/001-keycloak-auth-portal/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Not explicitly requested in spec. Integration verification is included as part of implementation tasks.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Web app**: `backend/src/`, `frontend/src/`
- Backend: `backend/src/main/java/com/mlplatform/`
- Frontend: `frontend/src/app/`
- Infrastructure: `infrastructure/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization, Keycloak deployment, and base project scaffolding

- [X] T001 Create Kubernetes namespace manifest in infrastructure/k8s/namespace.yaml defining `ml-platform` namespace
- [X] T002 Create Keycloak deployment manifest in infrastructure/k8s/keycloak/deployment.yaml using `quay.io/keycloak/keycloak:26.1` image with PostgreSQL connection, admin credentials, and readiness probe on `/realms/master`
- [X] T003 [P] Create Keycloak service manifest in infrastructure/k8s/keycloak/service.yaml as ClusterIP on port 8080
- [X] T004 [P] Create Keycloak realm ConfigMap in infrastructure/k8s/keycloak/configmap.yaml with realm `ml-platform`, public client `ml-platform-portal` (PKCE enabled, redirect URIs for localhost:4200 and localhost:8080), scopes `openid profile email`, and two test users (`scientist1`/`password1`, `scientist2`/`password2`)
- [X] T005 [P] Create port-forward script in infrastructure/scripts/port-forward.sh for Keycloak (8180:8080), PostgreSQL (5432:5432), and backend (8080:8080)
- [X] T006 Initialize Spring Boot 3.5.x project with Gradle in backend/ — create build.gradle.kts with dependencies: spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-oauth2-resource-server, spring-boot-starter-data-jpa, spring-boot-starter-actuator, flyway-core, postgresql, h2 (test/dev), springdoc-openapi-starter-webmvc-ui
- [X] T007 [P] Create backend Dockerfile in backend/Dockerfile using eclipse-temurin:21-jdk-alpine base image, copying JAR and setting entrypoint
- [X] T008 Initialize Angular 17 project with standalone components in frontend/ — run `ng new` with routing enabled, SCSS styles, and add `angular-auth-oidc-client` dependency in frontend/package.json

**Checkpoint**: Infrastructure manifests ready, both projects scaffold complete, Keycloak deployable to K8s

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T009 Create Flyway migration V1__create_users.sql in backend/src/main/resources/db/migration/ with `users` table schema (id UUID PK, oidc_subject VARCHAR UNIQUE, username VARCHAR, display_name VARCHAR, email VARCHAR, created_at TIMESTAMP, last_login TIMESTAMP) and index on oidc_subject
- [X] T010 Create User JPA entity in backend/src/main/java/com/mlplatform/model/User.java mapping to the `users` table with all fields from data-model.md
- [X] T011 Create UserRepository interface in backend/src/main/java/com/mlplatform/repository/UserRepository.java extending JpaRepository with `findByOidcSubject(String oidcSubject)` method
- [X] T012 Create SecurityConfig in backend/src/main/java/com/mlplatform/config/SecurityConfig.java — configure OAuth2 Resource Server with JWT validation via Keycloak JWKS endpoint, permit `/api/v1/health` and `/actuator/health/**` without auth, require authentication for all other `/api/v1/**` routes, configure stateless session management
- [X] T013 [P] Create DevSecurityConfig in backend/src/main/java/com/mlplatform/config/DevSecurityConfig.java — active for `dev` profile, permit all requests, inject mock JWT with dev user claims (`sub`, `preferred_username`, `name`, `email`)
- [X] T014 [P] Create CorsConfig in backend/src/main/java/com/mlplatform/config/CorsConfig.java — allow `http://localhost:4200` and `http://localhost:8080` origins for local dev, restrict to portal domain in production
- [X] T015 Create application.yaml in backend/src/main/resources/ with Keycloak issuer-uri (`http://keycloak.ml-platform.svc:8080/realms/ml-platform`), jwk-set-uri, PostgreSQL connection (`postgresql.ml-platform.svc:5432`), Flyway enabled, actuator health endpoint exposed
- [X] T016 [P] Create application-local.yaml in backend/src/main/resources/ overriding Keycloak to `http://localhost:8180/realms/ml-platform`, PostgreSQL to `localhost:5432`, and adding local dev settings
- [X] T017 [P] Create application-dev.yaml in backend/src/main/resources/ with H2 in-memory database, Flyway disabled, and dev Keycloak URI placeholder
- [X] T018 Create MlPlatformApplication.java in backend/src/main/java/com/mlplatform/ as the Spring Boot main class
- [X] T019 Create environment.ts in frontend/src/environments/ with apiUrl (`http://localhost:8080/api/v1`), keycloakUrl (`http://localhost:8180`), keycloakRealm (`ml-platform`), keycloakClientId (`ml-platform-portal`), enableOidc toggle
- [X] T020 [P] Create environment.prod.ts in frontend/src/environments/ with production URLs and enableOidc=true
- [X] T021 Create OIDC auth module in frontend/src/app/core/auth/oidc-auth.module.ts — configure angular-auth-oidc-client with Keycloak authority URL, client ID `ml-platform-portal`, PKCE code flow, scopes `openid profile email`, silent renew enabled with refresh tokens, renew 30 seconds before expiry, secure routes pointing to API URL
- [X] T022 Create auth guard in frontend/src/app/core/guards/auth.guard.ts — canActivateFn that checks `isAuthenticated$` from OidcSecurityService, triggers `authorize()` redirect if not authenticated, bypasses auth when `enableOidc=false`
- [X] T023 [P] Create auth interceptor in frontend/src/app/core/interceptors/auth.interceptor.ts — intercepts HTTP requests to API URL, attaches Bearer token from OIDC library in production, attaches stub token in dev mode
- [X] T024 Update frontend/src/main.ts to bootstrap standalone application with OIDC providers (conditional on `environment.enableOidc`), HTTP client with auth interceptor

**Checkpoint**: Foundation ready — backend validates JWTs, frontend handles OIDC login flow, database migrations run. User story implementation can begin.

---

## Phase 3: User Story 1 - User Login via SSO (Priority: P1) 🎯 MVP

**Goal**: Authenticated user can log in via Keycloak and see the portal dashboard with their identity displayed

**Independent Test**: Navigate to `http://localhost:4200`, complete Keycloak login, confirm dashboard loads with username in header

### Implementation for User Story 1

- [X] T025 [US1] Create UserService in backend/src/main/java/com/mlplatform/service/UserService.java — implement `syncFromJwt(Jwt jwt)` method that extracts `sub`, `preferred_username`, `name`, `email` from JWT claims, creates or updates the User record in database, and updates `last_login` timestamp
- [X] T026 [US1] Create HealthController in backend/src/main/java/com/mlplatform/controller/HealthController.java — implement `GET /api/v1/health` returning `{"status":"UP","timestamp":"..."}` per contracts/api.yaml HealthResponse schema
- [X] T027 [US1] Create AuthController in backend/src/main/java/com/mlplatform/controller/AuthController.java — implement `GET /api/v1/auth/userinfo` that accepts `@AuthenticationPrincipal Jwt jwt`, calls UserService.syncFromJwt(), returns UserInfo response per contracts/api.yaml schema
- [X] T028 [US1] Create ErrorResponse DTO in backend/src/main/java/com/mlplatform/dto/ErrorResponse.java matching contracts/api.yaml ErrorResponse schema, and create global exception handler in backend/src/main/java/com/mlplatform/config/GlobalExceptionHandler.java returning ErrorResponse for 401/403/500 errors
- [X] T029 [US1] Create auth.service.ts in frontend/src/app/core/services/auth.service.ts — wraps OidcSecurityService, exposes `currentUser$` observable (calls `/api/v1/auth/userinfo` after login), `isAuthenticated$`, and username for display
- [X] T030 [US1] Create dashboard.component.ts in frontend/src/app/features/dashboard/dashboard.component.ts — standalone component displaying welcome message with authenticated user's name, serves as the landing page after login
- [X] T031 [US1] Create app.component.ts in frontend/src/app/app.component.ts — portal shell layout with top header bar showing app title ("ML Platform") and authenticated user's username from auth.service, minimal styling with sidebar placeholder
- [X] T032 [US1] Create app.routes.ts in frontend/src/app/app.routes.ts — define routes: default redirect to `/dashboard`, `/dashboard` loading DashboardComponent, all routes protected by authGuard
- [X] T033 [US1] Deploy Keycloak to K8s cluster and verify login flow end-to-end: apply namespace.yaml, deploy PostgreSQL via Helm (`helm install postgresql oci://registry-1.docker.io/bitnamicharts/postgresql -n ml-platform`), apply keycloak/ manifests, start port-forwards, run backend with `local` profile, run frontend with `ng serve`, verify login with `scientist1`/`password1`

**Checkpoint**: User can log in via Keycloak SSO and see their username on the dashboard. US1 acceptance scenarios 1-5 are verifiable.

---

## Phase 4: User Story 2 - Portal Dashboard with Navigation (Priority: P2)

**Goal**: Authenticated user sees navigation sidebar with Notebooks, Experiments, Pipelines sections, each loading a placeholder page

**Independent Test**: Log in, click each navigation item, confirm each loads a distinct placeholder page

### Implementation for User Story 2

- [X] T034 [US2] Create PortalController in backend/src/main/java/com/mlplatform/controller/PortalController.java — implement `GET /api/v1/portal/sections` returning list of PortalSection objects (Notebooks, Experiments, Pipelines) per contracts/api.yaml schema
- [X] T035 [P] [US2] Create notebooks.component.ts in frontend/src/app/features/notebooks/notebooks.component.ts — standalone placeholder component displaying "Notebooks — coming in feature 002"
- [X] T036 [P] [US2] Create experiments.component.ts in frontend/src/app/features/experiments/experiments.component.ts — standalone placeholder component displaying "Experiments — coming in feature 003"
- [X] T037 [P] [US2] Create pipelines.component.ts in frontend/src/app/features/pipelines/pipelines.component.ts — standalone placeholder component displaying "Pipelines — coming in feature 005"
- [X] T038 [US2] Update app.routes.ts in frontend/src/app/app.routes.ts — add lazy-loaded routes for `/notebooks` (NotebooksComponent), `/experiments` (ExperimentsComponent), `/pipelines` (PipelinesComponent), all protected by authGuard
- [X] T039 [US2] Update app.component.ts in frontend/src/app/app.component.ts — add sidebar navigation with links to Dashboard, Notebooks, Experiments, Pipelines; highlight active route; fetch sections from `/api/v1/portal/sections` to render navigation dynamically
- [ ] T040 [US2] Verify navigation end-to-end: log in, click each sidebar item, confirm each placeholder page loads within the portal frame without page reload

**Checkpoint**: User Story 2 complete. Navigation works, all placeholder pages accessible. US2 acceptance scenarios 1-4 verifiable.

---

## Phase 5: User Story 3 - User Logout (Priority: P3)

**Goal**: Authenticated user can log out, terminating both portal and Keycloak sessions

**Independent Test**: Log in, click logout, confirm redirect to login page, confirm re-navigation requires fresh login

### Implementation for User Story 3

- [X] T041 [US3] Add logout endpoint in backend AuthController — implement `POST /api/v1/auth/logout` returning 204 per contracts/api.yaml
- [X] T042 [US3] Add logout method to auth.service.ts in frontend/src/app/core/services/auth.service.ts — call backend `/api/v1/auth/logout`, then call OidcSecurityService `logoff()` which redirects to Keycloak end-session endpoint, clearing all tokens
- [X] T043 [US3] Add logout button to portal header in frontend/src/app/app.component.ts — display "Sign Out" button next to username, wire click to auth.service.logout()
- [ ] T044 [US3] Verify logout end-to-end: log in as scientist1, click "Sign Out", confirm redirect to Keycloak login page, navigate to `http://localhost:4200`, confirm login is required again (no cached session)

**Checkpoint**: All user stories complete. Login, navigation, and logout all functional. US3 acceptance scenarios 1-2 verifiable.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Multi-user verification, edge case handling, and deployment readiness

- [X] T045 [P] Verify multi-user: log in as scientist1 in Chrome, log in as scientist2 in Firefox, confirm each sees their own username and independent sessions (SC-003)
- [ ] T046 [P] Verify token expiry handling: configure Keycloak access token lifespan to 1 minute (temporarily), confirm silent refresh works, then set refresh token to 1 minute, confirm redirect to login after expiry
- [X] T047 Add error handling for Keycloak unreachable scenario in frontend/src/app/app.component.ts — display user-friendly error message when OIDC initialization fails
- [ ] T048 Run quickstart.md validation — execute all steps from specs/001-keycloak-auth-portal/quickstart.md on a fresh cluster and confirm every step succeeds

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - US2 depends on US1 (needs login to test navigation)
  - US3 depends on US1 (needs login to test logout)
  - US2 and US3 can run in parallel after US1
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — no other story dependencies
- **User Story 2 (P2)**: Depends on US1 complete (needs authenticated shell to add navigation to)
- **User Story 3 (P3)**: Depends on US1 complete (needs authenticated shell to add logout to). Can run in parallel with US2.

### Within Each User Story

- Backend endpoints before frontend components that consume them
- Services before controllers
- Core implementation before integration verification
- Story complete before moving to next priority

### Parallel Opportunities

- T003, T004, T005 can run in parallel (Phase 1 — separate files)
- T007 can run in parallel with T006 (Dockerfile vs build.gradle.kts)
- T013, T014 can run in parallel with T012 (separate config files)
- T016, T017 can run in parallel (separate profile files)
- T019, T020 can run in parallel (separate environment files)
- T022, T023 can run in parallel (guard vs interceptor)
- T035, T036, T037 can run in parallel (independent placeholder components)
- T045, T046 can run in parallel (independent verification tasks)

---

## Parallel Example: Phase 1 Setup

```bash
# After T001 (namespace) and T002 (keycloak deployment):
Task: T003 "Create Keycloak service manifest"
Task: T004 "Create Keycloak realm ConfigMap"
Task: T005 "Create port-forward script"

# After T006 (backend init):
Task: T007 "Create backend Dockerfile"

# T008 (frontend init) runs independently of T006-T007
```

## Parallel Example: User Story 2

```bash
# After T034 (PortalController):
Task: T035 "Create notebooks placeholder component"
Task: T036 "Create experiments placeholder component"
Task: T037 "Create pipelines placeholder component"
# Then T038, T039, T040 sequentially
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T008)
2. Complete Phase 2: Foundational (T009-T024)
3. Complete Phase 3: User Story 1 (T025-T033)
4. **STOP and VALIDATE**: Verify login flow end-to-end
5. Deploy/demo if ready — user can log in and see dashboard

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test login flow → Deploy/Demo (MVP!)
3. Add User Story 2 → Test navigation → Deploy/Demo
4. Add User Story 3 → Test logout → Deploy/Demo
5. Polish → Multi-user and edge case verification

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Reference prior project patterns at `~/projects/data-intelligence/` for implementation details (SecurityConfig, OIDC module, AppComponent)
