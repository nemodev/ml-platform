# Feature Specification: Keycloak Auth & Portal Shell

**Feature Branch**: `001-keycloak-auth-portal`
**Created**: 2026-02-16
**Status**: Draft
**Input**: User description: "Keycloak Auth and Portal Shell"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - User Login via SSO (Priority: P1)

A data scientist opens the platform URL in their browser. They are
redirected to a centralized login page where they enter their
credentials (username and password). After successful authentication,
they are redirected back to the platform portal and land on the
dashboard. The user's identity is carried as a token for all subsequent
requests to the backend.

**Why this priority**: Authentication is the absolute foundation. No
other platform feature can function without verified user identity.

**Independent Test**: Can be verified by navigating to the platform URL,
completing the login flow, and confirming the portal dashboard loads
with the user's identity displayed.

**Acceptance Scenarios**:

1. **Given** a registered user, **When** they navigate to the platform
   URL, **Then** they are redirected to the identity provider login
   page.
2. **Given** valid credentials entered on the login page, **When** the
   user submits, **Then** they are redirected back to the portal
   dashboard and their username is displayed.
3. **Given** invalid credentials, **When** the user submits, **Then**
   an error message is shown and access is denied.
4. **Given** an authenticated user, **When** they make requests to the
   backend, **Then** the backend validates the token and returns
   authorized responses.
5. **Given** a user with an expired token, **When** they attempt any
   action, **Then** they are redirected to the login page.

---

### User Story 2 - Portal Dashboard with Navigation (Priority: P2)

After logging in, the user sees a minimal portal dashboard with a
navigation sidebar or header. The navigation contains placeholders for
future sections: Notebooks, Experiments, and Pipelines. Each navigation
item leads to a dedicated area within the portal. For this feature, the
content areas display placeholder pages confirming the section is
accessible.

**Why this priority**: The portal shell provides the container into
which all subsequent features (notebooks, MLflow, Airflow) will be
embedded. Without navigation structure, there is nowhere to place
embedded tools.

**Independent Test**: Can be verified by logging in and clicking each
navigation item, confirming each loads a distinct placeholder page
within the portal.

**Acceptance Scenarios**:

1. **Given** an authenticated user on the dashboard, **When** they view
   the navigation, **Then** they see menu items for Notebooks,
   Experiments, and Pipelines.
2. **Given** the navigation is visible, **When** the user clicks
   "Notebooks", **Then** a placeholder page for the Notebooks section
   loads within the portal frame.
3. **Given** the navigation is visible, **When** the user clicks
   "Experiments", **Then** a placeholder page for the Experiments
   section loads.
4. **Given** the navigation is visible, **When** the user clicks
   "Pipelines", **Then** a placeholder page for the Pipelines section
   loads.

---

### User Story 3 - User Logout (Priority: P3)

An authenticated user clicks a logout button in the portal. They are
logged out from the platform and the identity provider session is
terminated. Navigating back to the platform URL requires a fresh login.

**Why this priority**: Logout is essential for multi-user security,
especially on shared workstations. Lower priority than login and
navigation because the system is functional without it initially.

**Independent Test**: Can be verified by logging in, clicking logout,
and confirming that accessing the platform URL requires
re-authentication.

**Acceptance Scenarios**:

1. **Given** an authenticated user, **When** they click the logout
   button, **Then** they are redirected to the login page.
2. **Given** a logged-out user, **When** they navigate to the platform
   URL, **Then** they are required to log in again (no cached session).

---

### Edge Cases

- What happens when the identity provider is unreachable? The portal
  displays a clear error message indicating the authentication service
  is unavailable.
- What happens when a user's account is disabled between sessions? On
  the next request requiring token validation, the backend rejects the
  token and the user is redirected to login where they see an account
  disabled message.
- What happens when multiple browser tabs are open and the user logs
  out in one? The other tabs redirect to login on the next backend
  request.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST redirect unauthenticated users to a
  centralized identity provider login page.
- **FR-002**: The system MUST complete the OIDC authorization code flow
  and issue a session token to the frontend upon successful login.
- **FR-003**: The backend MUST validate tokens on every API request and
  reject requests with invalid or expired tokens. Token validation MUST
  use locally cached identity provider public keys so that requests
  succeed even during brief identity provider outages.
- **FR-004**: The frontend MUST display a portal shell with navigation
  to Notebooks, Experiments, and Pipelines sections.
- **FR-005**: The frontend MUST display the authenticated user's name
  or username in the portal header.
- **FR-006**: The system MUST provide a logout action that terminates
  both the portal session and the identity provider session.
- **FR-007**: The identity provider MUST be pre-configured with at
  least two test user accounts for multi-user verification.
- **FR-008**: The backend MUST expose a health check endpoint that does
  not require authentication.

### Key Entities

- **User**: An authenticated person with a username and display name.
  Identified by a token issued by the identity provider. All
  authenticated users have equal access (single role). User management
  and admin tasks are handled in the identity provider's admin console,
  not in the platform portal.
- **Session**: A frontend-held token representing the authenticated
  state. Access token expires after 1 hour; refresh token expires after
  24 hours. Tokens are silently refreshed in the background. Users MUST
  re-login after the refresh token expires.
- **Portal Section**: A named area in the navigation (Notebooks,
  Experiments, Pipelines) that will host embedded tools in later
  features.

### Assumptions

- The identity provider runs on the same Kubernetes cluster as the
  rest of the platform.
- For MVP, only username/password login is required (no social login
  or MFA).
- Two pre-seeded test users are sufficient for multi-user verification.
- The portal frontend is a single-page application served via the
  backend or a static file server.

## Clarifications

### Session 2026-02-16

- Q: What should the token lifetime and refresh behavior be? → A: 1-hour access token, 24-hour refresh token, silent background refresh, hard re-login after refresh expires.
- Q: What user roles are needed for MVP? → A: Single role — all authenticated users have equal access. Admin tasks handled in identity provider console.
- Q: How should the backend handle token validation when the identity provider is briefly unavailable? → A: Cache identity provider's public keys locally for offline JWT validation; refresh keys on schedule.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can complete the full login flow (URL → login
  page → portal dashboard) in under 30 seconds.
- **SC-002**: Token validation adds less than 100ms overhead to
  backend API responses.
- **SC-003**: Two different users can log in simultaneously in separate
  browsers and see their own identity displayed.
- **SC-004**: After logout, accessing any portal URL requires
  re-authentication with zero cached access.
