# Data Model: JupyterHub Notebook Embedding

**Feature**: `002-jupyterhub-notebook`
**Date**: 2026-02-16
**Updated**: 2026-02-20 (added Analysis entity, analysis-scoped workspaces)

## Entities

### Analysis (backend database)

A named project context owned by a user. Each analysis scopes its own
workspace (notebook server) and MLflow experiments. A user can have
multiple analyses running concurrently.

| Field | Type | Constraints | Source |
|-------|------|-------------|--------|
| id | UUID | PK, auto-generated | Backend |
| user_id | UUID | FK → users(id), NOT NULL | Backend (from JWT) |
| name | String(255) | NOT NULL, unique per user | User input |
| description | Text | Nullable | User input |
| created_at | Timestamp | NOT NULL, default NOW | Backend |

**Notes**:
- One analysis per user+name combination (enforced by unique index on
  `(user_id, name)`).
- The analysis UUID is used as the JupyterHub named server name and as
  part of the MLflow experiment prefix.

### Workspace (backend database)

Tracks the JupyterHub notebook server lifecycle for each analysis.
Created when a user launches a notebook within an analysis, updated on
status changes.

| Field | Type | Constraints | Source |
|-------|------|-------------|--------|
| id | UUID | PK, auto-generated | Backend |
| user_id | UUID | FK → users(id), NOT NULL | Backend (from JWT) |
| analysis_id | UUID | FK → analyses(id), Nullable | Backend |
| profile | String(50) | NOT NULL, default 'EXPLORATORY' | User selection |
| status | String(20) | NOT NULL, default 'PENDING' | JupyterHub API |
| pod_name | String(255) | Nullable | JupyterHub API |
| jupyterhub_username | String(255) | NOT NULL | JWT preferred_username |
| started_at | Timestamp | Nullable | Backend |
| last_activity | Timestamp | Nullable | JupyterHub API |
| created_at | Timestamp | NOT NULL, default NOW | Backend |

**Status values**: `PENDING`, `RUNNING`, `IDLE`, `STOPPED`, `FAILED`

**Notes**:
- One active workspace per analysis (enforced by unique constraint on
  `analysis_id` where `status` IN (`PENDING`, `RUNNING`, `IDLE`)).
- The `jupyterhub_username` maps to JupyterHub's user namespace and
  is derived from the Keycloak `preferred_username` claim.
- `pod_name` is populated once the KubeSpawner assigns a pod.
- The workspace uses JupyterHub **named servers**: the server name is
  the analysis UUID (`analysis.id.toString()`). This allows a single
  JupyterHub user to have multiple concurrent servers.

### Compute Profile (application configuration, not persisted)

| Attribute | Value |
|-----------|-------|
| Name | Exploratory |
| CPU Request | 1 |
| CPU Limit | 2 |
| Memory Request | 2Gi |
| Memory Limit | 4Gi |
| GPU | None |
| Description | Interactive data exploration and small experiments |

**Notes**:
- Single profile for MVP. Additional profiles (Data Engineering,
  ML Training, Deep Learning) are out of scope per Principle VI.
- Profile is defined in Helm values and exposed via the backend API.

### Notebook Server Lifecycle

```
[No Server] → Launch (within analysis) → [PENDING]
                           |
                    KubeSpawner creates named server pod
                           |
                    Pod ready → [RUNNING]
                           |
                    No activity for 30 min
                           |
                    Idle culler terminates named server → [STOPPED]
                           |
                    User navigates to analysis Notebooks again
                           |
                    [PENDING] → [RUNNING] (re-spawn)
```

**Error path**:
```
[PENDING] → Pod fails to start → [FAILED]
                                      |
                              User clicks Retry
                                      |
                              [PENDING] (re-attempt)
```

### User Workspace Storage (Kubernetes PVC)

| Attribute | Value |
|-----------|-------|
| Provisioner | Cluster default StorageClass |
| Size | 10Gi per user |
| Mount Path | /home/jovyan |
| Access Mode | ReadWriteOnce |
| Reclaim Policy | Retain |

**Notes**:
- PVCs are created by KubeSpawner on first server launch.
- PVCs survive pod restarts and server shutdowns (FR-008).
- PVCs are NOT deleted when a server is stopped.

### JupyterHub Named Servers

| Attribute | Value |
|-----------|-------|
| `allow_named_servers` | `True` |
| `named_server_limit_per_user` | `10` |
| Server name | Analysis UUID (`analysis.id.toString()`) |
| URL pattern | `/user/{username}/{serverName}/lab` |

**Notes**:
- Named servers allow one JupyterHub user to have multiple concurrent
  notebook servers, one per analysis.
- The idle culler's `removeNamedServers: true` ensures stopped named
  servers are cleaned up.
- A `pre_spawn_hook` injects `ANALYSIS_ID` and `ANALYSIS_S3_PATH`
  environment variables into each spawned pod.

### Keycloak Client Configuration (infrastructure)

| Entity | Details |
|--------|---------|
| Client ID | `ml-platform-jupyterhub` |
| Client Type | Confidential (has client secret) |
| Protocol | openid-connect |
| Standard Flow | Enabled |
| Redirect URIs | `http://localhost:8180/hub/oauth_callback` (local), `https://<portal>/hub/oauth_callback` (prod) |
| Scopes | `openid profile email` |
| Token Endpoint Auth | Client ID + Secret |

## Database Schema (PostgreSQL)

```sql
CREATE TABLE analyses (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id),
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_analyses_user_id ON analyses(user_id);
CREATE UNIQUE INDEX ux_analyses_user_id_name ON analyses(user_id, name);

CREATE TABLE workspaces (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES users(id),
    analysis_id           UUID REFERENCES analyses(id),
    profile               VARCHAR(50) NOT NULL DEFAULT 'EXPLORATORY',
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    pod_name              VARCHAR(255),
    jupyterhub_username   VARCHAR(255) NOT NULL,
    started_at            TIMESTAMP,
    last_activity         TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_workspaces_user_id ON workspaces(user_id);
CREATE INDEX idx_workspaces_analysis_id ON workspaces(analysis_id);
CREATE INDEX idx_workspaces_status ON workspaces(status);
CREATE UNIQUE INDEX ux_workspaces_analysis_active
    ON workspaces(analysis_id) WHERE status IN ('PENDING', 'RUNNING', 'IDLE');
```

## Relationships

- `analyses.user_id` → `users.id` (many-to-one; each user can have
  many analyses)
- `workspaces.user_id` → `users.id` (many-to-one)
- `workspaces.analysis_id` → `analyses.id` (many-to-one; each analysis
  has at most one active workspace)
- `users` table created by feature 001 (V1__create_users.sql)
