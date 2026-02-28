# Data Model: Custom Notebook Images

**Feature Branch**: `008-custom-notebook-images`
**Date**: 2026-02-27

## Entities

### NotebookImage

Represents a user-defined custom notebook image definition and its current state.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, not null, immutable | Generated via `@PrePersist` |
| user_id | UUID | FK → users(id), not null | Image owner |
| name | VARCHAR(255) | not null | User-assigned name |
| python_version | VARCHAR(10) | not null | e.g., "3.11" |
| packages | TEXT | nullable | Newline-separated package specs (e.g., `scikit-learn==1.4.0\nxgboost>=2.0`) |
| extra_pip_index_url | VARCHAR(1024) | nullable | Optional private pip index URL |
| status | VARCHAR(20) | not null | Enum: PENDING, BUILDING, READY, FAILED |
| image_reference | VARCHAR(512) | nullable | Full registry reference when READY (e.g., `registry.ml-platform.svc:5000/custom/user1-abc:v3`) |
| error_message | VARCHAR(1000) | nullable | Last build error summary (truncated) |
| created_at | TIMESTAMP WITH TIME ZONE | not null, immutable | Set in `@PrePersist` |
| updated_at | TIMESTAMP WITH TIME ZONE | not null | Updated on status change |

**Indexes**:
- `idx_notebook_images_user_id` on `(user_id)` — list images by user
- `uq_notebook_images_user_name` UNIQUE on `(user_id, name)` — prevent duplicate names per user

**Status transitions**:
```
PENDING → BUILDING → READY
                  → FAILED
READY → BUILDING (rebuild)
FAILED → BUILDING (retry)
```

**Notes**:
- `packages` stored as newline-separated text (not JSON) for simplicity. Follows pip requirements.txt format.
- `image_reference` is null until the first successful build.
- `error_message` capped at 1000 characters (same pattern as ModelDeployment).

---

### ImageBuild

Represents a single build attempt for a NotebookImage.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, not null, immutable | Generated via `@PrePersist` |
| notebook_image_id | UUID | FK → notebook_images(id), not null | Parent image definition |
| status | VARCHAR(20) | not null | Enum: QUEUED, BUILDING, SUCCEEDED, FAILED, CANCELLED |
| progress_stage | VARCHAR(100) | nullable | Current build stage (e.g., "Installing packages") |
| build_logs | TEXT | nullable | Full build output (Kaniko stdout/stderr) |
| image_reference | VARCHAR(512) | nullable | Resulting image reference on success |
| error_message | VARCHAR(1000) | nullable | Error summary on failure |
| k8s_job_name | VARCHAR(255) | nullable | Kubernetes Job name for tracking |
| started_at | TIMESTAMP WITH TIME ZONE | nullable | When build actually started (not queued) |
| completed_at | TIMESTAMP WITH TIME ZONE | nullable | When build finished |
| created_at | TIMESTAMP WITH TIME ZONE | not null, immutable | When build was requested |

**Indexes**:
- `idx_image_builds_notebook_image_id` on `(notebook_image_id)` — list builds per image
- `idx_image_builds_status` on `(status)` — query active builds for concurrency checks

**Status transitions**:
```
QUEUED → BUILDING → SUCCEEDED
                  → FAILED
QUEUED → CANCELLED (timeout in queue or user cancellation)
BUILDING → CANCELLED (timeout after 60 minutes)
```

**Notes**:
- `build_logs` stored as TEXT in PostgreSQL. For typical Kaniko builds (pip installs), logs are 10-500KB. Acceptable for PostgreSQL TEXT columns.
- `k8s_job_name` enables the backend to poll Job status and retrieve pod logs from the Kubernetes API.
- Multiple ImageBuild records per NotebookImage (rebuild history).

---

## Relationships

```
User (1) ──────< NotebookImage (N)
NotebookImage (1) ──────< ImageBuild (N)
```

- A User has zero or more NotebookImages (user-global, not analysis-scoped).
- A NotebookImage has one or more ImageBuilds (build history).
- Cascade delete: Deleting a NotebookImage deletes associated ImageBuild records.

---

## Flyway Migration

**File**: `V009__create_notebook_images_and_builds.sql`

```sql
-- Notebook image definitions
CREATE TABLE notebook_images (
    id              UUID            NOT NULL PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users(id),
    name            VARCHAR(255)    NOT NULL,
    python_version  VARCHAR(10)     NOT NULL,
    packages        TEXT,
    extra_pip_index_url VARCHAR(1024),
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    image_reference VARCHAR(512),
    error_message   VARCHAR(1000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notebook_images_user_id ON notebook_images(user_id);
CREATE UNIQUE INDEX uq_notebook_images_user_name ON notebook_images(user_id, name);

-- Image build attempts
CREATE TABLE image_builds (
    id                  UUID            NOT NULL PRIMARY KEY,
    notebook_image_id   UUID            NOT NULL REFERENCES notebook_images(id) ON DELETE CASCADE,
    status              VARCHAR(20)     NOT NULL DEFAULT 'QUEUED',
    progress_stage      VARCHAR(100),
    build_logs          TEXT,
    image_reference     VARCHAR(512),
    error_message       VARCHAR(1000),
    k8s_job_name        VARCHAR(255),
    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_image_builds_notebook_image_id ON image_builds(notebook_image_id);
CREATE INDEX idx_image_builds_status ON image_builds(status);
```

---

## Configuration Model (application.yaml)

Not a database entity. Externalized via Spring Boot `@ConfigurationProperties`.

```yaml
services:
  notebook-images:
    registry:
      type: builtin          # "builtin" or "external"
      endpoint: registry.ml-platform.svc:5000
      username: ""            # for external registries
      password: ""            # for external registries
      insecure: true          # allow HTTP (no TLS) for in-cluster registry
    build:
      namespace: ml-platform
      timeout-minutes: 60
      max-concurrent-builds: 3
      kaniko-image: gcr.io/kaniko-project/executor:latest
      build-cpu-request: "1"
      build-memory-request: 2Gi
      build-cpu-limit: "2"
      build-memory-limit: 4Gi
    base-image-prefix: ml-platform-notebook-base
    python-versions:
      - "3.10"
      - "3.11"
      - "3.12"
```
