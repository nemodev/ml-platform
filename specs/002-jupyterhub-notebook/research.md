# Research: JupyterHub Notebook Embedding

**Feature**: `002-jupyterhub-notebook`
**Date**: 2026-02-16

## Decisions

### D1: JupyterHub Deployment via Z2JH Helm Chart

**Decision**: Deploy JupyterHub using the Zero to JupyterHub (Z2JH)
Helm Chart v3.3.x from `hub.jupyter.org/helm-chart/`.

**Rationale**: Z2JH is the official, production-grade Helm chart for
running JupyterHub on Kubernetes. It bundles KubeSpawner, idle
culling, proxy configuration, and storage management. The prior
project used the same approach successfully.

**Alternatives considered**:
- Raw K8s manifests: Too much manual work for spawner, proxy, culler.
- The Littlest JupyterHub (TLJH): Not designed for Kubernetes.
- Custom operator: Over-engineering; violates Principle VI.

### D2: GenericOAuthenticator for Keycloak SSO

**Decision**: Use `GenericOAuthenticator` configured against the
existing `ml-platform` Keycloak realm. JupyterHub registered as a
confidential OIDC client (`ml-platform-jupyterhub`) with a client
secret.

**Rationale**: GenericOAuthenticator is the standard way to integrate
JupyterHub with any OIDC provider. Since Keycloak is already deployed
by feature 001, JupyterHub can leverage the existing SSO session —
users who are already logged into the portal will not see a second
login prompt because Keycloak recognizes the active session cookie.

**Alternatives considered**:
- `oauthenticator.keycloak.KeycloakOAuthenticator`: Exists but is a
  thin wrapper over GenericOAuthenticator with no significant
  advantages. GenericOAuthenticator is more widely documented.
- Custom authenticator forwarding portal token: Complex, fragile,
  and unnecessary when SSO session sharing works.

### D3: Single "Exploratory" KubeSpawner Profile for MVP

**Decision**: Configure a single spawner profile named "Exploratory"
with 1 CPU request / 2 CPU limit, 2Gi RAM request / 4Gi RAM limit.
No profile selector UI — servers launch directly with these defaults.

**Rationale**: The spec explicitly states GPU profiles are out of
scope. A single profile keeps the UX simple (no selection step) and
aligns with Principle VI (YAGNI). The prior project had 4 profiles
but only "Exploratory" was used during early development.

**Alternatives considered**:
- Multiple profiles from day one: Over-engineering for MVP.
- No resource limits: Unsafe on a shared cluster.

### D4: Custom Notebook Docker Image

**Decision**: Build a custom Docker image based on
`jupyter/scipy-notebook` with additional ML libraries (pytorch,
tensorflow, mlflow, plotly, seaborn) and Spark dependencies. This
image is used for both JupyterHub singleuser servers and (in future
feature 005) Airflow Papermill workers.

**Rationale**: Constitution Principle IV (Environment Parity) requires
the same image for interactive notebooks and pipeline workers. A
custom image ensures all required libraries are pre-installed per
FR-004. The prior project used a multi-stage Dockerfile with the
same pattern.

**Alternatives considered**:
- Stock `jupyter/scipy-notebook` with pip install at startup:
  Slow startup (violates SC-001 cold start target), inconsistent
  across restarts, breaks environment parity.
- Conda environment on PVC: Complex, slow, storage-intensive.

### D5: Dynamic PVC for User Workspace Storage

**Decision**: Use the Z2JH built-in dynamic PVC provisioning with
10Gi per user, mounted at `/home/jovyan`. Storage class uses the
cluster's default provisioner.

**Rationale**: Dynamic PVC provisioning is the standard Z2JH pattern.
10Gi is sufficient for notebooks and small datasets during MVP. The
prior project used the same approach. PVCs survive pod restarts,
satisfying FR-008.

**Alternatives considered**:
- NFS shared volume: Adds infrastructure complexity; not needed for
  user isolation.
- EmptyDir: Data lost on pod restart; violates FR-008.
- Larger PVC (50Gi+): Wasteful for MVP scope.

### D6: Iframe Embedding with CSP frame-ancestors

**Decision**: Embed JupyterLab in the Angular portal using an iframe.
Configure JupyterHub to set
`Content-Security-Policy: frame-ancestors 'self' http://localhost:4200`
(local) or the production portal domain. The backend provides the
per-user JupyterLab URL via an API endpoint.

**Rationale**: Iframe is the simplest embedding mechanism (Principle
VI). The prior project used the same pattern successfully. The
backend constructs the URL as
`{jupyterhubUrl}/user/{username}/lab` and the frontend sanitizes it
via `DomSanitizer.bypassSecurityTrustResourceUrl()`.

**Alternatives considered**:
- Reverse proxy through Spring Boot: Adds complexity, latency, and
  websocket proxying challenges. Not needed when iframe + CSP works.
- Web component embedding: JupyterLab doesn't support this.

### D7: Idle Culler Configuration

**Decision**: Enable the Z2JH idle culler with 1800-second (30-minute)
idle timeout, checking every 300 seconds (5 minutes). No max age
limit for MVP.

**Rationale**: 30-minute idle timeout per spec clarification. The
culler is built into Z2JH and requires only Helm values configuration.
No max age limit keeps things simple for MVP — users can have
long-running sessions as long as they're active.

**Alternatives considered**:
- 60-minute timeout (prior project default): Spec clarification
  explicitly chose 30 minutes.
- Max age limit (12h in prior project): YAGNI for MVP; can be added
  later.

### D8: Backend Workspace API Pattern

**Decision**: The Spring Boot backend exposes workspace management
endpoints that proxy JupyterHub's REST API. The backend uses a
JupyterHub admin API token to create users, spawn servers, check
status, and terminate servers. The frontend never communicates
directly with JupyterHub's API.

**Rationale**: The backend serves as the single entry point for all
platform operations (consistent with feature 001's architecture).
This enforces user isolation, adds logging/auditing capability, and
hides JupyterHub internals from the frontend. The prior project
used this exact pattern.

**Alternatives considered**:
- Frontend direct to JupyterHub API: Exposes JupyterHub's admin API
  token to the browser; security risk.
- JupyterHub auto-spawn on login: Reduces control over server
  lifecycle and doesn't support explicit launch/terminate UX.

### D9: Keycloak Client Configuration for JupyterHub

**Decision**: Register a new confidential OIDC client
`ml-platform-jupyterhub` in the existing `ml-platform` realm. This
is separate from the portal's public client
(`ml-platform-portal`). The client secret is stored as a
Kubernetes Secret.

**Rationale**: JupyterHub's GenericOAuthenticator requires a
confidential client (with a secret) because it exchanges
authorization codes server-side. The portal client is public (PKCE).
Separate clients allow independent redirect URI and scope
configuration.

**Alternatives considered**:
- Reusing the portal's public client: GenericOAuthenticator requires
  a client secret; public clients don't have one.
- Single client with both redirect URIs: Mixing public and
  confidential flows in one client is not supported by OIDC.

### D10: JupyterHub Database

**Decision**: Use the existing PostgreSQL instance (deployed by
feature 001) for JupyterHub's database. Create a separate
`jupyterhub` database on the same server.

**Rationale**: Reuses existing infrastructure; no additional database
deployment needed. JupyterHub's database is lightweight (user records,
server state). The prior project shared PostgreSQL across all
components.

**Alternatives considered**:
- SQLite on PVC: Fine for development but not recommended for
  production with multiple hub replicas.
- Separate PostgreSQL instance: Wasteful for MVP.
