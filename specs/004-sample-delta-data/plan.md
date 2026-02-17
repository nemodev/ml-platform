# Implementation Plan: Sample Delta Lake Data

**Branch**: `004-sample-delta-data` | **Date**: 2026-02-16 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/004-sample-delta-data/spec.md`

## Summary

Provision a sample Delta Lake table (California Housing dataset) on
MinIO object storage and make it accessible from notebooks via the
`deltalake` pure Python library. A Kubernetes Job generates the Delta
table from scikit-learn's built-in dataset and writes it to a dedicated
MinIO bucket. Notebook servers receive read-only S3 credentials via
JupyterHub KubeSpawner configuration. A sample notebook demonstrates
data loading and ML training.

Key technical decisions from research:
- `deltalake` pure Python library (no Spark dependency) for reading
  Delta tables into pandas DataFrames
- California Housing dataset (~20,640 rows, 9 columns) from
  scikit-learn
- New MinIO bucket `ml-platform-sample-data` (reuses MinIO from
  feature 003)
- Kubernetes Job for idempotent Delta table provisioning
- Read-only MinIO user/policy for notebook access
- Sample notebook mounted at `/home/jovyan/examples/` via ConfigMap

## Technical Context

**Language/Version**: Python 3.11 (provisioning script + notebooks)
**Primary Dependencies**: deltalake >= 0.22.0, scikit-learn, pandas,
  MinIO (official Helm chart from `charts.min.io`, deployed in feature 003)
**Storage**: MinIO (`ml-platform-sample-data` bucket for Delta table)
**Testing**: Notebook-based verification (load data, check schema,
  train model)
**Target Platform**: Kubernetes (all components)
**Project Type**: Infrastructure + data provisioning (no backend/
  frontend code changes)
**Performance Goals**: Delta table loads in < 10s from notebook (SC-001)
**Constraints**: Dataset < 100MB, read-only access from notebooks
**Scale/Scope**: Single dataset, ~20,640 rows, all authenticated users

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. MVP-First Incremental Delivery | PASS | Feature 004 depends on 002+003; independently deployable via K8s Job |
| II. Infrastructure as Code | PASS | Provisioning Job, MinIO config, JupyterHub values all version-controlled |
| III. Unified Authentication | PASS | No new auth; notebook access uses existing Keycloak SSO from feature 002 |
| IV. Environment Parity | PASS | Same notebook image reads Delta tables in dev and prod |
| V. Testing at System Boundaries | PASS | Verification tests notebook → deltalake → MinIO S3 connectivity |
| VI. Simplicity & YAGNI | PASS | Single dataset, pure Python reader, no Spark, no partitioning |

**Post-Phase 1 re-check**: All gates still PASS. No violations
introduced during design.

## Project Structure

### Documentation (this feature)

```text
specs/004-sample-delta-data/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
infrastructure/
├── docker/
│   └── notebook-image/
│       ├── Dockerfile                    # Update: add deltalake
│       └── requirements.txt             # Update: add deltalake
├── helm/
│   └── minio/
│       └── local-values.yaml            # Update: add sample-data bucket
├── k8s/
│   └── sample-data/
│       ├── provision-job.yaml           # K8s Job for Delta table creation
│       ├── provision-script-configmap.yaml  # Python provisioning script
│       ├── sample-notebook-configmap.yaml   # Sample .ipynb notebook
│       └── read-only-secret.yaml        # MinIO read-only credentials
└── scripts/
    └── port-forward.sh                  # No changes needed
```

**Structure Decision**: This feature is purely infrastructure — no
backend Java or frontend TypeScript changes. All artifacts are
Kubernetes manifests (Job, ConfigMap, Secret) and Docker image updates.
The provisioning script runs as a K8s Job using the notebook image.
The sample notebook is mounted into user pods via a ConfigMap volume.

## Complexity Tracking

No constitution violations to justify. All design choices align with
Principle VI (Simplicity & YAGNI):
- Single dataset (no dataset catalog or registry)
- Pure Python deltalake (no Spark for reading)
- K8s Job for provisioning (no custom operator or CRD)
- ConfigMap-mounted sample notebook (no notebook management service)
- Read-only MinIO user (no RBAC or IAM integration)
