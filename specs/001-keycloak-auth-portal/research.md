# Research: Keycloak Auth & Portal Shell

**Feature**: `001-keycloak-auth-portal`
**Date**: 2026-02-16

## Decision Log

### D1: Keycloak Version

- **Decision**: Keycloak 26.x (latest stable)
- **Rationale**: The prior project (`data-intelligence`) used Keycloak
  26.0.0/26.1 successfully. Keycloak 26.x supports the required OIDC
  features (authorization code flow with PKCE, realm configuration
  import, JWKS endpoints). It is the current LTS line.
- **Alternatives considered**:
  - Keycloak 25.x — Older, no benefit over 26.x.
  - Non-Keycloak (Auth0, Okta) — Constitution mandates Keycloak.

### D2: Angular OIDC Library

- **Decision**: `angular-auth-oidc-client` (latest compatible with
  Angular 17)
- **Rationale**: The prior project used `angular-auth-oidc-client:17.1.0`
  with Angular 17 successfully. It supports:
  - Authorization Code Flow with PKCE
  - Silent token renewal via refresh tokens
  - Configurable secure routes (auto-attach Bearer token)
  - Angular standalone component compatibility
- **Alternatives considered**:
  - `@auth0/angular-auth0-spa` — Auth0-specific, not for Keycloak.
  - `keycloak-angular` + `keycloak-js` — Heavier, tightly coupled to
    Keycloak JS adapter. Less idiomatic Angular.
  - Manual OIDC implementation — Unnecessary complexity.

### D3: Spring Boot OAuth2 Resource Server

- **Decision**: `spring-boot-starter-oauth2-resource-server` with JWT
  validation via Keycloak JWKS endpoint.
- **Rationale**: The prior project validates this approach. Spring
  Security's OAuth2 Resource Server:
  - Validates JWT signatures using cached JWKS public keys (satisfies
    FR-003 for offline validation during IdP outages).
  - Extracts claims (`sub`, `preferred_username`, `realm_access`) for
    user identity.
  - Stateless — no server-side session store needed (constitution
    Principle III compliance).
- **Alternatives considered**:
  - Spring Session with Redis — Adds stateful component; violates
    stateless architecture preference.
  - Custom JWT filter — Reinvents what spring-security provides.

### D4: Keycloak Realm Configuration Strategy

- **Decision**: Realm configuration imported via ConfigMap mounted into
  Keycloak pod, using `keycloak-config-cli` for automated setup.
- **Rationale**: The prior project used both raw K8s manifests
  (configmap.yaml with realm JSON) and Helm values with
  `keycloakConfigCli`. For MVP simplicity, a K8s ConfigMap with realm
  JSON is sufficient for local dev. Helm values handle production.
- **Alternatives considered**:
  - Manual Keycloak admin UI setup — Not reproducible, violates
    Infrastructure as Code principle.
  - Terraform Keycloak provider — Over-engineered for MVP.

### D5: Frontend Serving Strategy

- **Decision**: Angular SPA built as static files, served by the Spring
  Boot backend as a static resource.
- **Rationale**: Simplest approach for MVP — single deployable artifact.
  The Spring Boot application serves the Angular build output from
  `src/main/resources/static/`. This avoids a separate Nginx container
  or CDN setup. The prior project followed this pattern.
- **Alternatives considered**:
  - Separate Nginx container for frontend — Adds a deployment unit;
    overkill for MVP.
  - Angular SSR — Not needed; the portal is a SPA with client-side
    routing.

### D6: Dev/Local Profile Strategy

- **Decision**: Three Spring profiles: `dev` (H2, mock JWT), `local`
  (real Keycloak via port-forward, real PostgreSQL), default/production
  (K8s internal services).
- **Rationale**: The prior project's three-profile approach worked well:
  - `dev` for rapid iteration without any infrastructure.
  - `local` for integration testing with real Keycloak + PostgreSQL
    via port-forward.
  - Production for K8s deployment with internal service URLs.
- **Alternatives considered**:
  - Single profile with feature flags — Less clear separation.
  - Docker Compose for local — Heavier than port-forward to existing
    K8s cluster.

### D7: Keycloak Client Configuration

- **Decision**: Single public OIDC client `ml-platform-portal` for the
  Angular frontend. Confidential clients for backend services will be
  added in later features.
- **Rationale**: The Angular SPA is a public client (cannot securely
  store a client secret). PKCE provides the security guarantee. The
  prior project used `dataintel-portal` (public) and
  `dataintel-jupyterhub` (confidential) — we only need the portal
  client for feature 001.
- **Alternatives considered**:
  - Confidential client with BFF pattern — Adds a backend-for-frontend
    proxy; over-engineered for MVP.

### D8: Test Users

- **Decision**: Two pre-seeded users in realm config:
  - `user1` / `password1` (default user)
  - `user2` / `password2` (second user for multi-user testing)
- **Rationale**: Spec requires at least two test users (FR-007). Simple
  credentials for local dev. Production would use real credentials.
- **Alternatives considered**:
  - Self-registration — Out of scope for MVP; user management via
    Keycloak admin console per clarification.

### D9: Database for Backend

- **Decision**: PostgreSQL (shared instance with Keycloak) for
  production. H2 in-memory for dev profile.
- **Rationale**: PostgreSQL is already required by Keycloak. Sharing
  the instance (separate database) reduces infrastructure. For feature
  001, the backend database stores minimal data (user profile cache
  from JWT claims). H2 enables zero-dependency dev mode.
- **Alternatives considered**:
  - No backend database — Possible for feature 001 alone, but later
    features (experiments, pipelines) will need it. Better to set up
    now.
  - MongoDB — Not in the tech stack; PostgreSQL is standard.
