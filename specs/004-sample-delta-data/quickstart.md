# Quickstart: Sample Delta Lake Data

**Feature**: `004-sample-delta-data`

## Prerequisites

- Feature 002 (JupyterHub Notebook Embedding) deployed and working
- Feature 003 (MLflow Experiment Tracking) deployed — provides MinIO
- Kubernetes cluster with MinIO running in `ml-platform` namespace
- Port-forwards active for Keycloak (8180), backend (8080),
  JupyterHub (8181)

## Step 1: Update MinIO with Sample Data Bucket

Upgrade the MinIO deployment to include the new bucket:

```bash
helm upgrade minio minio/minio \
  --namespace ml-platform \
  -f infrastructure/helm/minio/local-values.yaml
```

Wait for MinIO to be ready:

```bash
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=minio \
  -n ml-platform --timeout=120s
```

## Step 2: Build Updated Notebook Image

The notebook image now includes the `deltalake` library:

```bash
cd infrastructure/docker/notebook-image
docker build -t ml-platform-notebook:latest .
```

If using a local cluster (kind/minikube), load the image:

```bash
kind load docker-image ml-platform-notebook:latest
# or: minikube image load ml-platform-notebook:latest
```

## Step 3: Run Data Provisioning Job

Deploy the Kubernetes Job that creates the Delta table in MinIO:

```bash
kubectl apply -f infrastructure/k8s/sample-data/provision-job.yaml \
  -n ml-platform
```

Wait for the Job to complete:

```bash
kubectl wait --for=condition=complete job/provision-sample-data \
  -n ml-platform --timeout=120s
```

Verify the Job succeeded:

```bash
kubectl logs job/provision-sample-data -n ml-platform
# Expected: "Delta table written to s3://ml-platform-sample-data/california-housing"
# Expected: "Verification: 20640 rows, 9 columns"
```

## Step 4: Update JupyterHub with S3 Credentials

Upgrade JupyterHub to include S3 environment variables for notebooks:

```bash
helm upgrade jupyterhub infrastructure/helm/jupyterhub/ \
  --namespace ml-platform \
  -f infrastructure/helm/jupyterhub/local-values.yaml
```

The update adds:
```yaml
singleuser:
  extraEnv:
    AWS_ENDPOINT_URL: "http://minio.ml-platform.svc:9000"
    AWS_ACCESS_KEY_ID: "<read-only-user>"
    AWS_SECRET_ACCESS_KEY: "<read-only-password>"
    AWS_ALLOW_HTTP: "true"
```

## Step 5: Verify Delta Table from Notebook (US1)

1. Open `http://localhost:4200`, log in as `scientist1`.
2. Click "Notebooks" → launch workspace.
3. Create a new notebook and run:

```python
from deltalake import DeltaTable
import pandas as pd

# Read the sample Delta table
dt = DeltaTable("s3://ml-platform-sample-data/california-housing")
df = dt.to_pandas()

# Inspect schema
print("Shape:", df.shape)
print("\nColumns:", df.columns.tolist())
print("\nData types:")
print(df.dtypes)
print("\nFirst 5 rows:")
df.head()
```

**Expected output**:
- Shape: (20640, 9)
- 9 columns with float64 types
- First 5 rows displayed with data

## Step 6: Verify Row Count and Schema (US1 - Acceptance 2 & 3)

```python
# Row count
print(f"Row count: {len(df)}")
assert len(df) > 1000, "Dataset must have at least 1,000 rows"

# Schema check
expected_columns = [
    'MedInc', 'HouseAge', 'AveRooms', 'AveBedrms',
    'Population', 'AveOccup', 'Latitude', 'Longitude',
    'MedHouseVal'
]
assert df.columns.tolist() == expected_columns, "Schema mismatch"
print("Schema and row count verified!")
```

## Step 7: Train a Model on Sample Data (US2)

```python
from sklearn.ensemble import RandomForestRegressor
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_squared_error, r2_score
import numpy as np

# Split features and target
X = df.drop('MedHouseVal', axis=1)
y = df['MedHouseVal']

# Train/test split
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42
)

# Train model
model = RandomForestRegressor(n_estimators=100, random_state=42)
model.fit(X_train, y_train)

# Evaluate
y_pred = model.predict(X_test)
rmse = np.sqrt(mean_squared_error(y_test, y_pred))
r2 = r2_score(y_test, y_pred)

print(f"RMSE: {rmse:.4f}")
print(f"R² Score: {r2:.4f}")
```

**Expected**: RMSE around 0.5, R² around 0.8 (reasonable for this
dataset with default hyperparameters).

## Step 8: Verify MLflow Integration (US2 - Acceptance 3)

```python
import mlflow

mlflow.set_experiment("california-housing-demo")

with mlflow.start_run():
    mlflow.log_param("n_estimators", 100)
    mlflow.log_param("dataset", "california-housing")
    mlflow.log_metric("rmse", rmse)
    mlflow.log_metric("r2_score", r2)
    mlflow.sklearn.log_model(model, "random-forest-regressor")
    print("Run logged to MLflow!")
```

Navigate to Experiments in the portal to confirm the run appears.

## Step 9: Verify Read-Only Access (Edge Case)

```python
from deltalake import write_deltalake

# Attempt to write to the sample data bucket (should fail)
try:
    write_deltalake(
        "s3://ml-platform-sample-data/test-write",
        df.head()
    )
    print("ERROR: Write succeeded — access is not read-only!")
except Exception as e:
    print(f"Write correctly denied: {e}")
```

## Step 10: Verify Sample Notebook in Examples

1. In the JupyterHub file browser, navigate to `/home/jovyan/examples/`.
2. Open `sample-delta-data.ipynb`.
3. Run all cells — they should complete without errors.
4. Confirm the notebook demonstrates: data loading, schema inspection,
   model training, and evaluation.
