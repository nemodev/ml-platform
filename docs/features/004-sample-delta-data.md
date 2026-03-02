# Feature 004: Sample Delta Lake Data

> Provisions a California Housing dataset as a Delta Lake table in MinIO, giving notebooks immediate access to realistic ML data.

## What & Why

A PoC needs data to demonstrate. Rather than asking evaluators to upload their own datasets, we pre-provision a well-known ML benchmark (California Housing, 20,640 rows, 8 numeric features + 1 target) as a Delta Lake table in MinIO's S3-compatible storage. This feature is infrastructure-only — no backend or frontend code. A Kubernetes Job runs once at deployment to write the Delta table, and notebook pods access it via read-only credentials. Delta Lake was chosen over raw Parquet because the real project plans to use it, and demonstrating that the `deltalake` Python library works seamlessly in notebook environments is part of the PoC's value.

## Architecture

```
provision-job.yaml (K8s Job, one-shot)
    ↓
ml-platform-notebook:latest image
    ↓ (python provision.py)
    ↓ sklearn.fetch_california_housing() → pandas → write_deltalake()
    ↓
MinIO: s3://ml-platform-sample-data/california-housing/
    ├── _delta_log/00000000000000000000.json
    └── part-00000-*.parquet
    ↓ (read-only credentials injected into notebook pods)
Notebook environment: DeltaTable(path).to_pandas()
```

**Key decisions:**

- **`deltalake` library over `delta-spark`** — Pure Python reader avoids Spark overhead for a 2MB dataset. Spark support exists (Feature 005) but isn't needed for data access.
- **K8s Job for provisioning** — Idempotent (`mode="overwrite"`), runs in-cluster, reuses the notebook image, auto-cleans up after 10 minutes (`ttlSecondsAfterFinished: 600`).
- **Read-only MinIO user** — `sample-data-readonly` user with S3 policy restricted to `s3:GetObject` and `s3:ListBucket` on the sample-data bucket. Notebook pods get these credentials via K8s Secret, preventing accidental data modification.
- **California Housing dataset** — Well-known scikit-learn benchmark, public domain, fits in memory, has both numeric features and a regression target. Ideal for demonstrating training, tracking, and serving workflows.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Infra | `k8s/sample-data/provision-job.yaml` | K8s Job that runs the provisioning script |
| Infra | `k8s/sample-data/provision-script-configmap.yaml` | Python script: fetch, convert, write Delta, verify |
| Infra | `k8s/sample-data/read-only-secret.yaml` | MinIO RO credentials for notebook pods |
| Infra | `k8s/sample-data/sample-notebook-configmap.yaml` | Example notebook: load data, train model, log to MLflow |
| Infra | `k8s/sample-data/batch-inference-notebook-configmap.yaml` | Batch inference notebook for KServe evaluation |
| Infra | `k8s/sample-data/sample-visualization-configmap.yaml` | Streamlit dashboard for data exploration |
| Infra | `docker/notebook-image/requirements.txt` | `deltalake>=0.22.0` dependency |
| Infra | `helm/minio/local-values.yaml` | Bucket creation and read-only policy |

**Provisioning script flow:** The Python script in the ConfigMap fetches California Housing from scikit-learn, converts to a pandas DataFrame, writes to MinIO as a Delta table using `write_deltalake()` with S3 storage options, then verifies by reading back and asserting 20,640 rows and 9 columns.

**Three example notebooks:** The sample notebook demonstrates loading data, schema inspection, model training (Gradient Boosting), and MLflow logging with staged predictions. The batch inference notebook shows KServe V2 protocol calls with evaluation metrics. The Streamlit dashboard provides interactive data exploration with sidebar filters and geographic visualization.

**S3 configuration for Delta:** The `deltalake` library reads S3 credentials from standard AWS environment variables (`AWS_ENDPOINT_URL`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`), injected into notebook pods via JupyterHub's `extraEnv` configuration and the read-only credentials Secret.

## Challenges & Solutions

- **MinIO requires path-style S3 access** — Unlike AWS S3, MinIO needs `AWS_S3_ALLOW_UNSAFE_RENAME=true` and path-style URLs. Storage options are passed explicitly to `write_deltalake()` and `DeltaTable()`.
- **Bucket configuration coordination** — MinIO bucket creation, policy, and user setup happen in the Helm values; the provisioning Job runs afterward. Deployment ordering matters.

## Limitations

- **Single dataset only** — Only California Housing is provisioned. The real project would need multiple datasets across different domains.
- **No incremental updates** — The provisioning Job uses `mode="overwrite"` — it replaces the entire table. No append or merge operations are demonstrated.
- **Hardcoded MinIO admin credentials in provisioning Job** — The Job uses `minioadmin:minioadmin` for write access. Acceptable for PoC but not for production.
- **Hardcoded Spark credentials in Dockerfile** — The notebook image Spark defaults include plaintext MinIO credentials in the Docker layer.
- **No data lineage or cataloging** — There's no metadata catalog showing what datasets are available. Users need to know the S3 path.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| delta-spark for provisioning | Requires Spark cluster for a 2MB dataset. `deltalake` pure Python is simpler. |
| Raw Parquet (no Delta) | Doesn't demonstrate Delta Lake, which is the target format for the real project. |
| PostgreSQL table | Different access pattern than the project's planned S3-based data lake architecture. |
| Synthetic data generation | Less realistic. California Housing is well-known and enables reproducible demos. |
| Multiple datasets | Scope creep for PoC. One dataset is sufficient to demonstrate the full workflow. |

## Potential Improvements

- **Data catalog API** — Backend endpoint listing available datasets with schema information, discoverable from the portal.
- **User-uploaded datasets** — Allow users to upload their own CSV/Parquet files to MinIO and register as Delta tables.
- **Multiple sample datasets** — Add time-series, classification, and text datasets to demonstrate different ML workflows.
- **Incremental data operations** — Demonstrate Delta Lake merge, upsert, and time-travel capabilities.
- **Externalize all credentials** — Move MinIO credentials out of Dockerfile and provisioning Job into K8s Secrets.
