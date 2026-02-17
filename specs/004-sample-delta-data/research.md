# Research: Sample Delta Lake Data

**Feature**: `004-sample-delta-data` | **Date**: 2026-02-16

## Summary

Feature 004 provisions a sample Delta Lake table (California Housing
dataset) on MinIO object storage and makes it accessible from notebooks
via the `deltalake` pure Python library. No Spark dependency is required.

## Decisions

### D1: Delta Reader Library — `deltalake` Pure Python

**Decision**: Use the `deltalake` PyPI package (Python bindings for
`delta-rs`) to read Delta tables directly into pandas DataFrames.

**Rationale**: The sample dataset is ~20,640 rows (~2MB). The pure
Python `deltalake` library reads Delta tables without requiring a Spark
cluster. This avoids adding PySpark and Java dependencies to the
notebook image for this feature. The prior project used `delta-spark`
because it also needed Spark for distributed processing; the ML Platform
MVP defers Spark to feature 005 (Airflow).

**Alternatives considered**:
- `delta-spark` (PySpark): Requires Spark + Java runtime in notebook
  image. Overkill for sub-100MB datasets. Deferred to feature 005.
- `polars` with Delta support: Good performance but adds a non-standard
  DataFrame library. Users expect pandas.

**Version**: `deltalake >= 0.22.0` (latest stable with S3 support
via `object_store` Rust crate).

### D2: Sample Dataset — California Housing

**Decision**: Use scikit-learn's California Housing dataset (~20,640
rows, 8 numeric features, 1 regression target).

**Rationale**: Well-known ML benchmark dataset. Numeric features
suitable for regression demos. Small enough to load entirely into
memory. No licensing concerns (public domain via StatLib).

**Schema**:
| Column | Type | Description |
|--------|------|-------------|
| MedInc | float64 | Median income in block group |
| HouseAge | float64 | Median house age in block group |
| AveRooms | float64 | Average number of rooms per household |
| AveBedrms | float64 | Average number of bedrooms per household |
| Population | float64 | Block group population |
| AveOccup | float64 | Average number of household members |
| Latitude | float64 | Block group latitude |
| Longitude | float64 | Block group longitude |
| MedHouseVal | float64 | Median house value (target, in $100k) |

### D3: Storage Location — Reuse MinIO from Feature 003

**Decision**: Store the sample Delta table in a new bucket
`ml-platform-sample-data` on the MinIO instance deployed in feature 003.

**Rationale**: MinIO is already deployed for MLflow artifact storage.
Adding a second bucket avoids deploying another storage backend. The
official MinIO Helm chart (`charts.min.io`) supports bucket provisioning
via `buckets` list in values.yaml; we update the MinIO deployment to
include the new bucket.

**Alternatives considered**:
- Same bucket as MLflow (`ml-platform-mlflow`): Mixes concerns. MLflow
  artifacts and sample data have different access patterns and lifecycle.
- Separate MinIO instance: Wasteful for a single read-only dataset.
- PVC-backed storage: Not S3-compatible, breaks `deltalake` S3 reader.

**Path**: `s3://ml-platform-sample-data/california-housing/`

### D4: Data Provisioning — Kubernetes Job

**Decision**: Create a Kubernetes Job that runs a Python script to
generate the Delta table and write it to MinIO. The Job runs once
during deployment.

**Rationale**: A K8s Job is idempotent (can be re-run safely), runs
within the cluster network (direct MinIO access), and requires no
manual intervention. The script loads California Housing from
scikit-learn, converts to a pandas DataFrame, and writes as a Delta
table using `deltalake.write_deltalake()` with S3 storage options.

**Alternatives considered**:
- Manual script execution: Not reproducible, violates IaC principle.
- Init container on notebook pods: Runs on every pod start, wasteful.
- Pre-built Delta files in Docker image: Increases image size, harder
  to update dataset.

### D5: Read-Only Access — MinIO Bucket Policy

**Decision**: Configure the MinIO bucket with a read-only policy for
notebook server access. The provisioning Job uses admin credentials;
notebook servers use read-only credentials.

**Rationale**: FR-006 requires the sample data to be read-only.
MinIO bucket policies support `s3:GetObject` and `s3:ListBucket`
without write permissions. Notebook pods receive read-only credentials
via environment variables injected by JupyterHub KubeSpawner config.

**Implementation**:
- MinIO admin credentials: Used only by the provisioning Job
- Read-only credentials: Created as a separate MinIO user/policy,
  injected into notebook pods as `AWS_ACCESS_KEY_ID`,
  `AWS_SECRET_ACCESS_KEY`, `AWS_ENDPOINT_URL`

### D6: Notebook Image Update — Add `deltalake` to Requirements

**Decision**: Add `deltalake` to the notebook Docker image's
`requirements.txt` from feature 002.

**Rationale**: FR-003 requires the library to be pre-installed. Users
should not need to `pip install` anything to read sample data.

**Additional dependencies**: None. The `deltalake` package is a
self-contained Rust binary with Python bindings. No Java or Spark
needed.

**Cumulative notebook image requirements**: The notebook Docker image
accumulates dependencies across features: `mlflow` + `scikit-learn` +
`pandas` + `matplotlib` (feature 002), `deltalake` (feature 004),
`papermill` + `boto3` (feature 005), `pyspark` + Hadoop S3A JARs
(feature 005). Each feature updates `infrastructure/docker/notebook-image/requirements.txt`.

### D7: S3 Configuration for `deltalake` Library

**Decision**: Configure S3 access for `deltalake` via `storage_options`
parameter or environment variables.

**Rationale**: The `deltalake` library uses the `object_store` Rust
crate for S3 access. It reads credentials from standard AWS environment
variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) and requires
`AWS_ENDPOINT_URL` for MinIO (non-AWS S3).

**Environment variables** (injected into notebook pods):
```
AWS_ENDPOINT_URL=http://minio.ml-platform.svc:9000
AWS_ACCESS_KEY_ID=<read-only-user>
AWS_SECRET_ACCESS_KEY=<read-only-password>
AWS_ALLOW_HTTP=true
```

**Usage in notebooks**:
```python
from deltalake import DeltaTable
dt = DeltaTable("s3://ml-platform-sample-data/california-housing")
df = dt.to_pandas()
```

### D8: Sample Notebook — Documentation

**Decision**: Provide a sample Jupyter notebook file
(`sample-delta-data.ipynb`) that demonstrates loading the Delta table
and training a simple model. The notebook is mounted read-only into
user workspaces at `/home/jovyan/examples/`.

**Rationale**: FR-007 requires documentation or a sample notebook.
A runnable `.ipynb` file is more useful than markdown docs because
users can execute it immediately. Mounting as read-only in `/examples/`
ensures users can see it without it cluttering their workspace.

**Alternatives considered**:
- Markdown documentation only: Not executable, less discoverable.
- Auto-copy to user workspace: Clutters workspace, hard to update.
- ConfigMap-mounted notebook: Works for small files, good K8s pattern.

### D9: Provisioning Script Design

**Decision**: The provisioning Python script performs these steps:
1. Check if the Delta table already exists (idempotent)
2. Load California Housing from `sklearn.datasets`
3. Create a pandas DataFrame with named columns
4. Write as Delta table using `deltalake.write_deltalake()` with
   `mode="overwrite"`
5. Verify the table is readable

**Rationale**: Idempotent design means the Job can be re-run without
side effects. Using `mode="overwrite"` ensures the latest version of
the dataset is always present. The verify step confirms end-to-end
connectivity.

**Docker image for Job**: A minimal Python 3.11 image with
`deltalake`, `scikit-learn`, and `pandas`. Can reuse the notebook
image or build a lightweight provisioning image.

**Decision**: Reuse the notebook Docker image for the provisioning
Job. This avoids maintaining a separate image and ensures the same
`deltalake` version is used for writing and reading.
