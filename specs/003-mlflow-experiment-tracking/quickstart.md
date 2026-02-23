# Quickstart: MLflow Experiment Tracking

**Feature**: `003-mlflow-experiment-tracking`

## Prerequisites

- Feature 001 (Keycloak Auth & Portal Shell) deployed and working
- Feature 002 (JupyterHub Notebook Embedding) deployed and working
- Kubernetes cluster with PostgreSQL running in `ml-platform` namespace
- Port-forwards active for Keycloak (8180), backend (8080),
  JupyterHub (8181)

## Step 1: Deploy MinIO

```bash
helm install minio oci://registry-1.docker.io/bitnamicharts/minio \
  --namespace ml-platform \
  --set auth.rootUser=minioadmin \
  --set auth.rootPassword=minioadmin \
  --set defaultBuckets="ml-platform-mlflow"
```

Wait for MinIO to be ready:

```bash
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=minio \
  -n ml-platform --timeout=120s
```

## Step 2: Create MLflow Database

```bash
# Port-forward PostgreSQL if not already active
kubectl port-forward svc/postgresql 5432:5432 -n ml-platform

# Create the mlflow database
PGPASSWORD=localdevpassword psql -h localhost -U postgres \
  -c "CREATE DATABASE mlflow;"
```

## Step 3: Deploy MLflow Tracking Server

```bash
helm install mlflow infrastructure/helm/mlflow/ \
  --namespace ml-platform \
  -f infrastructure/helm/mlflow/local-values.yaml
```

Wait for MLflow to be ready:

```bash
kubectl wait --for=condition=ready pod -l app=mlflow \
  -n ml-platform --timeout=120s
```

## Step 4: Port-Forward MLflow

```bash
# Terminal 4: MLflow UI
kubectl port-forward svc/mlflow 5000:5000 -n ml-platform
```

Verify MLflow is running:

```bash
curl http://localhost:5000/health
# Expected: OK
```

## Step 5: Update JupyterHub Configuration

Ensure the JupyterHub Helm values include the MLflow tracking URI
environment variable for notebook servers:

```bash
helm upgrade jupyterhub infrastructure/helm/jupyterhub/ \
  --namespace ml-platform \
  -f infrastructure/helm/jupyterhub/local-values.yaml
```

The update adds:
```yaml
singleuser:
  extraEnv:
    MLFLOW_TRACKING_URI: "http://backend.ml-platform.svc:8080/api/v1/mlflow-proxy"
```

## Step 6: Run Backend with MLflow Config

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Verify experiment endpoints:

```bash
TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/ml-platform/protocol/openid-connect/token" \
  -d "client_id=ml-platform-cli" \
  -d "username=user1" \
  -d "password=password1" \
  -d "grant_type=password" \
  -d "scope=openid" | jq -r '.access_token')

# List experiments (should be empty)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/experiments
# Expected: []

# Create an experiment
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"test-experiment"}' \
  http://localhost:8080/api/v1/experiments
# Expected: {"experimentId":"1","name":"test-experiment",...}
```

## Step 7: Run Frontend

```bash
cd frontend
npm install
ng serve
```

## Step 8: Verify Logging from Notebook (US1)

1. Open `http://localhost:4200`, log in as `user1`.
2. Click "Notebooks" → launch workspace.
3. Create a new notebook and run:
   ```python
   import mlflow
   import mlflow.sklearn
   import os
   from sklearn.ensemble import RandomForestClassifier
   from sklearn.datasets import load_iris
   from sklearn.model_selection import train_test_split
   from sklearn.metrics import accuracy_score

   # In local dev notebook kernels, set tracking URI explicitly.
   mlflow.set_tracking_uri("http://mlflow.ml-platform.svc:5000")
   username = os.getenv("JUPYTERHUB_USER", "user1")

   # Load data
   X, y = load_iris(return_X_y=True)
   X_train, X_test, y_train, y_test = train_test_split(X, y)

   # Start MLflow run
   mlflow.set_experiment(f"{username}/iris-classification")
   with mlflow.start_run():
       model = RandomForestClassifier(n_estimators=100)
       model.fit(X_train, y_train)
       accuracy = accuracy_score(y_test, model.predict(X_test))

       mlflow.log_param("n_estimators", 100)
       mlflow.log_metric("accuracy", accuracy)
       mlflow.sklearn.log_model(model, "model")

       print(f"Run logged! Accuracy: {accuracy:.4f}")
   ```
4. Confirm the cell outputs "Run logged!" with an accuracy value.

## Step 9: Verify Experiment UI Embedding (US2)

1. Navigate to "Experiments" in the portal sidebar.
2. The MLflow UI should load embedded in the portal.
3. Find the "iris-classification" experiment.
4. Click on the experiment — see the run with metrics and parameters.
5. Click on the run — view the logged model artifact.

## Step 10: Verify User Isolation (SC-003)

1. Open a different browser, log in as `user2`.
2. Navigate to "Experiments" — should see NO experiments.
3. Open a notebook and log a run to a different experiment.
4. Switch to user1's browser — user2's experiment should
   NOT be visible.

## Step 11: Verify SSO Passthrough (US3)

1. Confirm you did NOT see a login page when loading the MLflow UI.
2. Refresh the Experiments page — MLflow UI reloads without login.

## Step 12: Verify Metric Comparison (SC-004)

1. As user1, run the training script 3 more times with different
   `n_estimators` values (50, 200, 500).
2. In the Experiments UI, select all 4 runs.
3. Compare accuracy metrics side by side.

## Dev Profile (no MLflow/MinIO needed)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The dev profile:
- Returns mock experiment data (no MLflow server needed)
- Returns a placeholder tracking URL for iframe
- No MinIO or MLflow deployment required
