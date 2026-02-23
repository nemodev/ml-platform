# Data Model: Model Serving & Inference

**Feature**: `006-model-serving-inference` | **Date**: 2026-02-16

## Overview

This feature adds a `model_deployments` table to track user-initiated
model deployments. KServe manages InferenceService CRDs in Kubernetes;
the platform table links user actions to KServe resources. MLflow Model
Registry manages model versions (no platform table needed for that).

## Entity: ModelDeployment

Represents a KServe InferenceService deployment initiated by a user.

### Schema

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| id | UUID | No | Primary key |
| user_id | UUID | No | FK to `users` table |
| model_name | VARCHAR(255) | No | MLflow registered model name |
| model_version | INTEGER | No | MLflow model version number |
| endpoint_name | VARCHAR(255) | No | KServe InferenceService name (unique) |
| status | VARCHAR(20) | No | Current status (see lifecycle below) |
| inference_url | VARCHAR(512) | Yes | Cluster-internal URL when ready |
| storage_uri | VARCHAR(512) | No | S3 URI to model artifacts in MinIO |
| error_message | TEXT | Yes | Error details if failed |
| created_at | TIMESTAMP | No | When deployment was initiated |
| ready_at | TIMESTAMP | Yes | When endpoint became ready |
| deleted_at | TIMESTAMP | Yes | Soft delete timestamp |

### Status Lifecycle

```text
DEPLOYING → READY
          → FAILED
READY → DELETING → DELETED
```

| Status | Description |
|--------|-------------|
| DEPLOYING | InferenceService CRD created, waiting for pod readiness |
| READY | Inference endpoint is live and accepting requests |
| FAILED | Deployment failed (invalid model, resource issues) |
| DELETING | InferenceService deletion in progress |
| DELETED | Endpoint removed (soft delete, record preserved) |

### Indexes

- `idx_model_deployments_user_id` on `user_id`
- `idx_model_deployments_endpoint_name` on `endpoint_name` (unique)
- `idx_model_deployments_status` on `status`

### SQL Migration (Flyway)

```sql
-- V006__create_model_deployments.sql
CREATE TABLE model_deployments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    model_name VARCHAR(255) NOT NULL,
    model_version INTEGER NOT NULL,
    endpoint_name VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'DEPLOYING',
    inference_url VARCHAR(512),
    storage_uri VARCHAR(512) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    ready_at TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_model_deployments_user_id ON model_deployments(user_id);
CREATE INDEX idx_model_deployments_endpoint_name ON model_deployments(endpoint_name);
CREATE INDEX idx_model_deployments_status ON model_deployments(status);
```

## MLflow Model Registry (Managed by MLflow)

MLflow manages model registration internally. Key concepts:

| Concept | Description |
|---------|-------------|
| Registered Model | A named model with one or more versions |
| Model Version | A specific artifact version (1, 2, 3...) |
| Stage | None → Staging → Production → Archived |
| Artifact URI | S3 path to model files in MinIO |

The platform accesses the registry via MLflow REST API — no direct
database access.

## KServe InferenceService (Managed by KServe)

KServe manages InferenceService CRDs in Kubernetes. Example:

```yaml
apiVersion: serving.kserve.io/v1beta1
kind: InferenceService
metadata:
  name: user1-iris-classifier-v1
  namespace: ml-platform-serving
  annotations:
    serving.kserve.io/deploymentMode: RawDeployment
spec:
  predictor:
    model:
      modelFormat:
        name: mlflow
      storageUri: "s3://ml-platform-mlflow/artifacts/1/abc123/artifacts/model"
      resources:
        requests:
          cpu: "1"
          memory: 2Gi
        limits:
          cpu: "1"
          memory: 2Gi
    serviceAccountName: kserve-s3-sa
```

### Endpoint Naming Convention

`{username}-{model-name}-v{version}` (lowercase, hyphens)

Example: `user1-iris-classifier-v1`

### Inference URL

In raw mode, KServe creates a ClusterIP Service:
`http://{endpoint-name}.ml-platform-serving.svc.cluster.local`

V2 inference endpoint:
`http://{endpoint-name}-predictor.ml-platform-serving.svc.cluster.local/v2/models/{endpoint-name}/infer`

## Kubernetes Resources

### Namespace: ml-platform-serving

Dedicated namespace for all InferenceService deployments.

### ServiceAccount: kserve-s3-sa

Used by KServe storage initializer to download model artifacts from
MinIO.

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kserve-s3-sa
  namespace: ml-platform-serving
secrets:
  - name: kserve-s3-secret
```

### Secret: kserve-s3-secret

MinIO credentials for model artifact download.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kserve-s3-secret
  namespace: ml-platform-serving
  annotations:
    serving.kserve.io/s3-endpoint: minio.ml-platform.svc:9000
    serving.kserve.io/s3-usehttps: "0"
    serving.kserve.io/s3-region: us-east-1
type: Opaque
data:
  AWS_ACCESS_KEY_ID: <base64-encoded>
  AWS_SECRET_ACCESS_KEY: <base64-encoded>
```

## Relationships

```text
User ──(1:N)──→ ModelDeployment ──(1:1)──→ KServe InferenceService
                     │
                     └── storageUri → MLflow Model Artifact (MinIO)

MLflow Model Registry:
  RegisteredModel ──(1:N)──→ ModelVersion ──(1:1)──→ Artifact (MinIO)
```
