# Data Model: Sample Delta Lake Data

**Feature**: `004-sample-delta-data` | **Date**: 2026-02-16

## Overview

This feature introduces no new backend database entities. The data model
describes the Delta table stored in MinIO object storage and the MinIO
configuration required to serve it.

## Delta Table: California Housing

**Location**: `s3://ml-platform-sample-data/california-housing/`
**Format**: Delta Lake (Parquet + `_delta_log/` transaction log)
**Access**: Read-only from notebook servers

### Schema

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| MedInc | DOUBLE | No | Median income in block group |
| HouseAge | DOUBLE | No | Median house age in block group |
| AveRooms | DOUBLE | No | Average rooms per household |
| AveBedrms | DOUBLE | No | Average bedrooms per household |
| Population | DOUBLE | No | Block group population |
| AveOccup | DOUBLE | No | Average household members |
| Latitude | DOUBLE | No | Block group latitude |
| Longitude | DOUBLE | No | Block group longitude |
| MedHouseVal | DOUBLE | No | Median house value in $100k (target) |

**Row count**: ~20,640
**Approximate size**: ~2MB (Parquet compressed)

### Feature / Target Split

- **Features** (8 columns): MedInc, HouseAge, AveRooms, AveBedrms,
  Population, AveOccup, Latitude, Longitude
- **Target** (1 column): MedHouseVal

## MinIO Configuration

### Buckets

| Bucket | Purpose | Created By |
|--------|---------|------------|
| `ml-platform-mlflow` | MLflow artifacts (feature 003) | MinIO Helm |
| `ml-platform-sample-data` | Sample Delta tables (this feature) | MinIO Helm |

Both buckets are created by the official MinIO Helm chart (`charts.min.io`)
via the `buckets` list in values.yaml.

### Access Control

| Principal | Bucket | Permissions |
|-----------|--------|-------------|
| `minioadmin` | All | Full access (admin) |
| `sample-data-readonly` | `ml-platform-sample-data` | `s3:GetObject`, `s3:ListBucket` |

The `sample-data-readonly` MinIO user is created by the provisioning
Job. Its credentials are injected into notebook pods via JupyterHub
KubeSpawner environment variables.

### Read-Only Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::ml-platform-sample-data",
        "arn:aws:s3:::ml-platform-sample-data/*"
      ]
    }
  ]
}
```

## Object Storage Layout

```text
s3://ml-platform-sample-data/
└── california-housing/
    ├── _delta_log/
    │   └── 00000000000000000000.json    # Initial commit
    ├── part-00000-*.parquet             # Data file(s)
    └── (additional parquet parts if partitioned)
```

## Environment Variables (Notebook Pods)

These environment variables are added to JupyterHub KubeSpawner
`extraEnv` configuration to enable `deltalake` S3 access:

| Variable | Value | Purpose |
|----------|-------|---------|
| `AWS_ENDPOINT_URL` | `http://minio.ml-platform.svc:9000` | MinIO endpoint |
| `AWS_ACCESS_KEY_ID` | `<from K8s Secret>` | Read-only MinIO user |
| `AWS_SECRET_ACCESS_KEY` | `<from K8s Secret>` | Read-only MinIO password |
| `AWS_ALLOW_HTTP` | `true` | Allow non-HTTPS for in-cluster MinIO |

## No Database Migration

This feature does not add any tables to PostgreSQL. All data resides
in MinIO as Delta Lake files. No Flyway migration script is needed.
