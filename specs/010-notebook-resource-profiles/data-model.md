# Data Model: Notebook Resource Profiles

## Entities

### Resource Profile (Configuration-based, not database)

Profiles are defined in `application.yaml` and loaded via `@ConfigurationProperties`. No database table required.

**Properties class**: `WorkspaceProfileProperties`

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | String | Required, unique | Slug identifier (e.g., "exploratory") |
| name | String | Required | Display name (e.g., "Exploratory") |
| description | String | Required | User-facing description |
| default | boolean | At most one true | Whether this is the default profile |
| cpuRequest | String | K8s resource format | CPU guarantee (e.g., "1", "500m") |
| cpuLimit | String | K8s resource format | CPU maximum (e.g., "2") |
| memoryRequest | String | K8s resource format | Memory guarantee (e.g., "2G") |
| memoryLimit | String | K8s resource format | Memory maximum (e.g., "4G") |

### Workspace (Existing entity — no schema changes)

The `workspaces` table already has a `profile` column (`VARCHAR(50)`, default `'EXPLORATORY'`). No migration needed.

| Field | Type | Notes |
|-------|------|-------|
| id | UUID | PK |
| user_id | UUID | FK → users |
| analysis_id | UUID | FK → analyses |
| **profile** | VARCHAR(50) | **Already exists.** Stores the profile ID used at launch time. |
| status | VARCHAR(20) | PENDING, RUNNING, IDLE, STOPPED, FAILED |
| pod_name | VARCHAR(255) | Kubernetes pod name (needed for metrics query) |
| jupyterhub_username | VARCHAR(255) | JupyterHub user identifier |
| notebook_image_id | UUID | FK → notebook_images (Feature 008) |
| started_at | TIMESTAMP | Server start time |
| last_activity | TIMESTAMP | Last kernel activity |
| created_at | TIMESTAMP | Record creation time |

## Relationships

```
┌──────────────────────┐
│  application.yaml    │
│  workspace.profiles  │─── loaded at startup ──→ WorkspaceProfileProperties
└──────────────────────┘

┌────────────┐    N:1    ┌──────────┐    N:1    ┌──────┐
│  Workspace │──────────→│ Analysis │──────────→│ User │
│            │           └──────────┘           └──────┘
│ profile    │── matches → profile.id in config
│ pod_name   │── identifies → K8s pod for metrics query
└────────────┘
```

## State Transitions

Profile selection and switching follow the existing workspace lifecycle:

```
User selects profile + clicks Launch
    → Workspace created (status=PENDING, profile=selected_id)
    → JupyterHub spawn with resource overrides
    → status=RUNNING

User switches profile (while RUNNING/IDLE)
    → Frontend confirms restart warning
    → Workspace terminated (status=STOPPED)
    → New workspace created (status=PENDING, profile=new_id)
    → JupyterHub spawn with new resource overrides
    → status=RUNNING
```

## Spawn Options (JupyterHub user_options)

The spawn request body combines image and resource overrides:

```json
{
  "image": "registry/custom-image:tag",
  "cpu_guarantee": 2,
  "cpu_limit": 4,
  "mem_guarantee": "4G",
  "mem_limit": "8G"
}
```

Fields are optional — omitted fields use JupyterHub profileList defaults. The `image` field continues to work as in Feature 008. Resource fields are new for Feature 010.

## Metrics Response (Kubernetes Metrics API)

Pod metrics are queried on-demand from `metrics.k8s.io/v1beta1`:

```json
{
  "cpuUsage": "1.2",
  "memoryUsageBytes": 2254857728,
  "cpuLimit": "2",
  "memoryLimit": "4G",
  "profileId": "exploratory",
  "profileName": "Exploratory"
}
```
