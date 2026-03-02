# Feature 001: Keycloak Auth & Portal

> Single sign-on via Keycloak OIDC with a unified Angular portal, establishing the authentication foundation for all platform components.

## What & Why

The ML Platform needs a single authentication boundary across six independently-developed systems (JupyterHub, MLflow, Airflow, KServe, MinIO, the portal itself). We chose Keycloak as the OIDC provider because it's open source, supports realm-based multi-tenancy, and has native integrations with all our downstream components. This feature establishes the login flow, user synchronization, and the portal shell that hosts all other features. Every subsequent feature depends on the JWT tokens and user identity that 001 provides.

## Architecture

The authentication flow uses Authorization Code + PKCE (the recommended SPA pattern):

```
Browser → Angular SPA → Keycloak (PKCE) → JWT issued
                ↓
        Spring Boot Backend (validates JWT via cached JWKS)
                ↓
        User synced to PostgreSQL (oidc_subject as canonical ID)
```

**Key decisions:**

- **Stateless JWT validation** — Backend caches Keycloak's JWKS public keys and validates tokens locally. No session state, no Keycloak dependency per request.
- **User sync on first API call** — `UserService.syncFromJwt()` creates or updates the user record from JWT claims (`sub`, `preferred_username`, `email`). The `oidc_subject` column (Keycloak's `sub` claim / user UUID) is the canonical identifier — username changes in Keycloak don't break identity.
- **Three Spring profiles** — `dev` (mock JWT, H2 database, no infrastructure needed), `local` (real Keycloak via port-forward), default (in-cluster K8s DNS).
- **Dev profile mocking** — `DevSecurityConfig` injects a fabricated JWT on every request, allowing full feature development without running Keycloak. This pattern repeats across all features — every external-service integration has an `isDevProfile()` fallback. Introduced here, referenced from 002–010.

**Token propagation to downstream systems:**

| Component | Auth Method |
|-----------|-------------|
| Backend API | JWT Bearer (validated against JWKS) |
| JupyterHub | GenericOAuthenticator (Keycloak OIDC) |
| MLflow | No direct auth; proxied through backend |
| Airflow | Basic auth, proxied through backend |
| MinIO | AWS credentials (K8s secrets, not user tokens) |
| KServe | Inference proxied through backend |

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `config/SecurityConfig.java` | OAuth2 resource server, JWKS endpoint, stateless sessions |
| Backend | `config/DevSecurityConfig.java` | Mock JWT filter for dev profile |
| Backend | `service/UserService.java` | JWT → user sync with per-subject locking |
| Backend | `controller/AuthController.java` | `/api/v1/auth/userinfo` and `/api/v1/auth/logout` |
| Backend | `config/CorsConfig.java` | Profile-specific allowed origins |
| Frontend | `core/auth/oidc-auth.module.ts` | OIDC client config (PKCE with WebCrypto detection) |
| Frontend | `core/services/auth.service.ts` | Auth state management, login/logout orchestration |
| Frontend | `core/guards/auth.guard.ts` | Route guard with OIDC callback detection |
| Frontend | `core/interceptors/auth.interceptor.ts` | JWT Bearer attachment, dev stub token |
| Infra | `k8s/keycloak/configmap.yaml` | Realm config: 3 clients, 2 test users |
| Infra | `k8s/keycloak/deployment.yaml` | Keycloak 26.1 with `--import-realm` |

**OIDC flow details:** The Angular frontend uses `angular-auth-oidc-client` with conditional PKCE — it checks for `window.crypto.subtle` availability and falls back to implicit flow on older browsers. Silent token refresh keeps sessions alive. The `auth.guard.ts` detects active OIDC callbacks (both code flow `?code=...&state=...` and implicit flow `#id_token=...`) to avoid blocking the redirect.

**User sync concurrency:** `UserService` uses a `ConcurrentHashMap` with per-OIDC-subject locks to handle simultaneous logins from multiple tabs. The sync runs in `PROPAGATION_REQUIRES_NEW` to ensure atomic user creation.

**Keycloak realm pre-configuration:** The ConfigMap defines three clients: `ml-platform-portal` (public, PKCE), `ml-platform-jupyterhub` (confidential, for Feature 002), and `ml-platform-cli` (public, direct grants, for future CLI). Redirect URIs cover all deployment contexts — localhost, K8s NodePort, r1 cluster IP, production domain.

## Challenges & Solutions

- **Keycloak 26 read-only usernames** — Keycloak 26 made `username` a read-only user attribute, even for admins. Renaming test users required direct PostgreSQL updates plus Keycloak restart. Documented in `docs/TROUBLESHOOTING.md` items 4–5.
- **User identity after username change** — If `oidc_subject` in the `users` table doesn't match the Keycloak user UUID, `syncFromJwt()` creates a duplicate user record and existing analyses become invisible. Fix: ensure `oidc_subject` always matches the Keycloak `sub` claim.
- **CORS across three profiles** — Each profile needs different allowed origins. Externalized to `app.cors.allowed-origins` in profile-specific YAML files.

## Limitations

- **No RBAC** — All authenticated users have equal access. No role claims are extracted from JWT. Adding roles later requires extracting `realm_access.roles` from the token.
- **No idle session timeout** — Tokens remain valid for 24 hours after browser close. Logout only happens when the user clicks the logout button.
- **Hardcoded credentials in K8s manifests** — Keycloak admin password (`admin/admin`) and the JupyterHub client secret are in ConfigMaps, not Secrets.
- **No audit logging** — Login/logout events are not logged. `syncFromJwt()` updates `last_login` but doesn't produce audit trail entries.
- **Permissive redirect URIs** — The Keycloak ConfigMap allows redirects from multiple IP ranges to support all deployment contexts, which would need tightening for production.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| OAuth2 Proxy (sidecar) | Adds infrastructure complexity; each component would need a sidecar. Backend proxy pattern is simpler for PoC. |
| Spring Session (stateful) | Server-side sessions add state management burden and complicate scaling. Stateless JWT is simpler. |
| Dex / Auth0 | External services add cost or complexity. Keycloak is self-hosted and feature-complete. |
| Shared cookie auth | Cookies don't work across different service domains/ports. JWT Bearer tokens work everywhere. |

## Potential Improvements

- **Kubernetes Secrets for credentials** — Move Keycloak admin password and client secrets from ConfigMaps to proper K8s Secrets.
- **Role-based access control** — Extract Keycloak realm roles from JWT claims for admin/viewer distinction.
- **Idle session management** — Implement frontend session timeout warning and automatic re-authentication.
- **Audit trail** — Log authentication events (login, logout, failed attempts) for security compliance.
- **Token refresh monitoring** — Surface token expiry warnings in the portal UI rather than silent refresh only.
