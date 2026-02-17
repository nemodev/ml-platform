# Data Model: Airflow Notebook Pipeline

**Feature**: `005-airflow-notebook-pipeline` | **Date**: 2026-02-16

## Overview

This feature adds a `pipeline_runs` table to track user-triggered
pipeline executions. Airflow manages its own metadata internally
(DAG runs, task instances) — the platform's `pipeline_runs` table
provides a user-facing abstraction that links a user's trigger request
to an Airflow DAG run.

## Entity: PipelineRun

Represents a single notebook pipeline execution triggered by a user.

### Schema

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| id | UUID | No | Primary key |
| user_id | UUID | No | FK to `users` table |
| notebook_name | VARCHAR(255) | No | Original notebook filename |
| input_path | VARCHAR(512) | No | S3 path to input notebook snapshot |
| output_path | VARCHAR(512) | Yes | S3 path to executed output notebook |
| status | VARCHAR(20) | No | Current status (see lifecycle below) |
| airflow_dag_run_id | VARCHAR(255) | Yes | Airflow DAG run ID for status polling |
| parameters | JSONB | Yes | Papermill parameters (key-value pairs) |
| enable_spark | BOOLEAN | No | Whether Spark is enabled for this run |
| started_at | TIMESTAMP | Yes | When execution began |
| completed_at | TIMESTAMP | Yes | When execution finished |
| error_message | TEXT | Yes | Error details if failed |
| created_at | TIMESTAMP | No | When the run was triggered |

### Status Lifecycle

```text
PENDING → RUNNING → SUCCEEDED
                  → FAILED
```

| Status | Description |
|--------|-------------|
| PENDING | Run created, Airflow DAG triggered, waiting for pod |
| RUNNING | Airflow task pod is executing the notebook |
| SUCCEEDED | Notebook executed successfully, output available |
| FAILED | Execution failed, error_message populated |

### Indexes

- `idx_pipeline_runs_user_id` on `user_id` (filter by user)
- `idx_pipeline_runs_status` on `status` (filter active runs)
- `idx_pipeline_runs_created_at` on `created_at` (sort by recency)

### SQL Migration (Flyway)

```sql
-- V005__create_pipeline_runs.sql
CREATE TABLE pipeline_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    notebook_name VARCHAR(255) NOT NULL,
    input_path VARCHAR(512) NOT NULL,
    output_path VARCHAR(512),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    airflow_dag_run_id VARCHAR(255),
    parameters JSONB,
    enable_spark BOOLEAN NOT NULL DEFAULT false,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_pipeline_runs_user_id ON pipeline_runs(user_id);
CREATE INDEX idx_pipeline_runs_status ON pipeline_runs(status);
CREATE INDEX idx_pipeline_runs_created_at ON pipeline_runs(created_at);
```

## Object Storage Layout

```text
s3://ml-platform-pipelines/
├── {username}/
│   └── {run-uuid}/
│       ├── input.ipynb          # Snapshot of triggered notebook
│       └── output.ipynb         # Executed notebook with cell outputs
└── airflow-logs/
    └── notebook_runner/
        └── run_notebook/
            └── {date}/
                └── {attempt}.log  # Airflow task logs
```

## MinIO Buckets (Updated)

| Bucket | Purpose | Feature |
|--------|---------|---------|
| `ml-platform-mlflow` | MLflow artifacts | 003 |
| `ml-platform-sample-data` | Sample Delta tables | 004 |
| `ml-platform-pipelines` | Pipeline notebooks + Airflow logs | 005 |

## Airflow Metadata (Managed by Airflow)

Airflow manages its own metadata in the `airflow` PostgreSQL database.
Key tables (created by Airflow, not by the platform):
- `dag`: Registered DAGs
- `dag_run`: DAG execution instances
- `task_instance`: Individual task executions
- `log`: Task execution logs

The platform does NOT read from Airflow's database directly. All
interaction is via the Airflow REST API.

## DAG Template: notebook_runner

A single reusable DAG deployed as a ConfigMap:

```python
from airflow import DAG
from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator
from datetime import datetime

with DAG(
    dag_id="notebook_runner",
    schedule_interval=None,  # Triggered via REST API only
    start_date=datetime(2024, 1, 1),
    catchup=False,
) as dag:
    run_notebook = KubernetesPodOperator(
        task_id="run_notebook",
        image="{{ var.value.notebook_image }}",
        cmds=["papermill"],
        arguments=[
            "{{ dag_run.conf['input_path'] }}",
            "{{ dag_run.conf['output_path'] }}",
        ],
        namespace="ml-platform",
        service_account_name="airflow-spark-sa",
        env_vars={
            "AWS_ENDPOINT_URL": "http://minio.ml-platform.svc:9000",
            "MLFLOW_TRACKING_URI": "http://mlflow.ml-platform.svc:5000",
        },
    )
```

## Kubernetes RBAC

### ServiceAccount: airflow-spark-sa

Used by Airflow task pods to create Spark executor pods.

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: spark-executor-role
  namespace: ml-platform
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["create", "get", "list", "watch", "delete"]
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get"]
  - apiGroups: [""]
    resources: ["services"]
    verbs: ["create", "get", "delete"]
```

## Relationships

```text
User ──(1:N)──→ PipelineRun ──(1:1)──→ Airflow DAG Run
                     │
                     ├── input.ipynb (MinIO)
                     └── output.ipynb (MinIO)
```
