# Data Model: JupyterHub Notebook Embedding

**Feature**: `002-jupyterhub-notebook`
**Date**: 2026-02-16

## Entities

### Workspace (backend database)

Tracks the JupyterHub notebook server lifecycle for each user.
Created when a user first launches a notebook, updated on status
changes.

| Field | Type | Constraints | Source |
|-------|------|-------------|--------|
| id | UUID | PK, auto-generated | Backend |
| user_id | UUID | FK → users(id), NOT NULL | Backend (from JWT) |
| profile | String(50) | NOT NULL, default 'EXPLORATORY' | User selection |
| status | String(20) | NOT NULL, default 'PENDING' | JupyterHub API |
| pod_name | String(255) | Nullable | JupyterHub API |
| jupyterhub_username | String(255) | NOT NULL | JWT preferred_username |
| started_at | Timestamp | Nullable | Backend |
| last_activity | Timestamp | Nullable | JupyterHub API |
| created_at | Timestamp | NOT NULL, default NOW | Backend |

**Status values**: `PENDING`, `RUNNING`, `IDLE`, `STOPPED`, `FAILED`

**Notes**:
- One active workspace per user (enforced by unique constraint on
  `user_id` where `status` IN (`PENDING`, `RUNNING`, `IDLE`)).
- The `jupyterhub_username` maps to JupyterHub's user namespace and
  is derived from the Keycloak `preferred_username` claim.
- `pod_name` is populated once the KubeSpawner assigns a pod.

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
[No Server] → Launch → [PENDING]
                           |
                    KubeSpawner creates pod
                           |
                    Pod ready → [RUNNING]
                           |
                    No activity for 30 min
                           |
                    Idle culler terminates pod → [STOPPED]
                           |
                    User navigates to Notebooks again
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
CREATE TABLE workspaces (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES users(id),
    profile               VARCHAR(50) NOT NULL DEFAULT 'EXPLORATORY',
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    pod_name              VARCHAR(255),
    jupyterhub_username   VARCHAR(255) NOT NULL,
    started_at            TIMESTAMP,
    last_activity         TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_workspaces_user_id ON workspaces(user_id);
CREATE INDEX idx_workspaces_status ON workspaces(status);
```

## Relationships

- `workspaces.user_id` → `users.id` (many-to-one; each user has
  at most one active workspace)
- `users` table created by feature 001 (V1__create_users.sql)
