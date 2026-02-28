# Data Model: Keycloak Auth & Portal Shell

**Feature**: `001-keycloak-auth-portal`
**Date**: 2026-02-16

## Entities

### User (backend database)

Cached profile from Keycloak JWT claims. Created/updated on first
API request after login.

| Field | Type | Constraints | Source |
|-------|------|-------------|--------|
| id | UUID | PK, auto-generated | Backend |
| oidc_subject | String(255) | UNIQUE, NOT NULL, indexed | JWT `sub` claim |
| username | String(255) | NOT NULL | JWT `preferred_username` |
| display_name | String(255) | Nullable | JWT `name` |
| email | String(255) | Nullable | JWT `email` |
| created_at | Timestamp | NOT NULL, default NOW | Backend |
| last_login | Timestamp | NOT NULL, updated on each request | Backend |

**Notes**:
- `oidc_subject` is the canonical user identifier across the platform.
- No `role` column — all users have equal access per clarification.
- No `password` column — authentication is fully delegated to Keycloak.

### Session (frontend-only, not persisted in backend)

| Attribute | Type | Details |
|-----------|------|---------|
| access_token | JWT | 1-hour expiry, issued by Keycloak |
| refresh_token | Opaque | 24-hour expiry, used for silent renewal |
| id_token | JWT | Contains user profile claims |

**Lifecycle**:

```
[Unauthenticated] → Login → [Authenticated]
    ↑                           |
    |                    Token expires (1h)
    |                           |
    |                    Silent refresh via refresh_token
    |                           |
    |                    Refresh succeeds → [Authenticated]
    |                           |
    |                    Refresh fails (24h) → [Expired]
    |                           |
    └───────── Redirect to login ←──┘
```

### Keycloak Realm Configuration (infrastructure)

| Entity | Details |
|--------|---------|
| Realm | `ml-platform` |
| Client | `ml-platform-portal` (public, PKCE) |
| Redirect URIs | `http://localhost:4200/*`, `http://localhost:8080/*` (local); `https://<portal-domain>/*` (prod) |
| Scopes | `openid profile email` |
| Test User 1 | `user1` / `password1` |
| Test User 2 | `user2` / `password2` |

## Database Schema (PostgreSQL)

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    oidc_subject  VARCHAR(255) NOT NULL UNIQUE,
    username      VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    email         VARCHAR(255),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    last_login    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_oidc_subject ON users(oidc_subject);
```

## Relationships

The `users` table is the root entity referenced by all user-owned tables
added in subsequent features:

- `users` → `analyses` (feature 002/007) — named project contexts
- `users` → `workspaces` (feature 002) — notebook server lifecycle
- `users` → `pipeline_runs` (feature 005) — notebook pipeline executions
- `users` → `model_deployments` (feature 006) — KServe inference endpoints
- `users` → `notebook_images` (feature 008) — custom notebook image definitions
- `notebook_images` → `image_builds` (feature 008) — build attempt history
- `workspaces.notebook_image_id` → `notebook_images` (feature 008, V010 migration)
