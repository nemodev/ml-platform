# Quickstart: Model Serving & Inference

**Feature**: `006-model-serving-inference`

## Prerequisites

- Feature 001 (Keycloak Auth & Portal Shell) deployed and working
- Feature 002 (JupyterHub Notebook Embedding) deployed and working
- Feature 003 (MLflow Experiment Tracking) deployed — provides MLflow +
  MinIO
- Kubernetes cluster with PostgreSQL, MinIO, MLflow running in
  `ml-platform` namespace
- Port-forwards active for Keycloak (8180), backend (8080),
  JupyterHub (8181), MLflow (5000)

## Step 1: Install KServe

Install KServe CRDs and controller in raw deployment mode:

```bash
# Install KServe CRDs
kubectl apply -f https://github.com/kserve/kserve/releases/download/v0.16.0/kserve.yaml

# Install ClusterServingRuntimes (includes mlserver for MLflow format)
kubectl apply -f https://github.com/kserve/kserve/releases/download/v0.16.0/kserve-cluster-resources.yaml

# Configure KServe for raw deployment mode
kubectl patch configmap/inferenceservice-config \
  -n kserve --type=merge \
  -p '{"data":{"deploy":"{\"defaultDeploymentMode\":\"RawDeployment\"}"}}'
```

Wait for KServe controller to be ready:

```bash
kubectl wait --for=condition=ready pod -l control-plane=kserve-controller-manager \
  -n kserve --timeout=120s
```

## Step 2: Create Serving Namespace and Credentials

```bash
# Create serving namespace
kubectl create namespace ml-platform-serving

# Create S3 credentials for KServe to access MinIO
kubectl apply -f infrastructure/k8s/kserve/s3-secret.yaml \
  -n ml-platform-serving

# Create ServiceAccount for model artifact download
kubectl apply -f infrastructure/k8s/kserve/service-account.yaml \
  -n ml-platform-serving
```

## Step 3: Run Backend with KServe Config

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Verify model endpoints:

```bash
TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/ml-platform/protocol/openid-connect/token" \
  -d "client_id=ml-platform-cli" \
  -d "username=user1" \
  -d "password=password1" \
  -d "grant_type=password" \
  -d "scope=openid" | jq -r '.access_token')

# List registered models (should be empty initially)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/models
# Expected: []

# List deployments (should be empty)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/serving/deployments
# Expected: []
```

## Step 4: Run Frontend

```bash
cd frontend
npm install
ng serve
```

## Step 5: Register a Model in MLflow (US1 - Prerequisite)

1. Open `http://localhost:4200`, log in as `user1`.
2. Click "Notebooks" → launch workspace.
3. Create a notebook and run:

```python
import mlflow
import mlflow.sklearn
import os
from sklearn.ensemble import RandomForestRegressor
from sklearn.datasets import fetch_california_housing
from sklearn.model_selection import train_test_split

# Use direct in-cluster MLflow from notebooks for local dev.
mlflow.set_tracking_uri("http://mlflow.ml-platform.svc:5000")
username = os.getenv("JUPYTERHUB_USER", "user1")

# Load data
data = fetch_california_housing()
X_train, X_test, y_train, y_test = train_test_split(
    data.data, data.target, test_size=0.2, random_state=42
)

# Train and log model
mlflow.set_experiment(f"{username}/housing-model")
with mlflow.start_run() as run:
    model = RandomForestRegressor(n_estimators=100, random_state=42)
    model.fit(X_train, y_train)
    mlflow.sklearn.log_model(model, "model")
    print(f"Run ID: {run.info.run_id}")

# Register model in Model Registry
model_uri = f"runs:/{run.info.run_id}/model"
mv = mlflow.register_model(model_uri, f"{username}/housing-regressor")
print(f"Registered model: {mv.name}, version: {mv.version}")
```

4. Confirm output shows registered model name and version 1.

## Step 6: Deploy Model to KServe (US1)

1. Navigate to "Models" in the portal sidebar.
2. The registered model "housing-regressor" should appear.
3. Click "Deploy" → select version 1.
4. Click "Deploy" to create the inference endpoint.
5. Watch status: DEPLOYING → READY.

Verify via CLI:

```bash
# Check InferenceService status
kubectl get inferenceservices -n ml-platform-serving
# Expected: user1-housing-regressor-v1  READY

# Check deployment status via API
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/serving/deployments
# Expected: [{...status: "READY"...}]
```

## Step 7: Send Inference Request (US2)

Via the portal:
1. Navigate to the deployment detail page.
2. Use the "Test Prediction" form.
3. Enter sample input (8 California Housing features).
4. Click "Predict" — see the prediction result.

Via API:

```bash
DEPLOYMENT_ID="<uuid-from-step-6>"

curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "inputs": [{
      "name": "predict",
      "shape": [1, 8],
      "datatype": "FP64",
      "data": [[8.3252, 41.0, 6.984, 1.024, 322.0, 2.556, 37.88, -122.23]]
    }]
  }' \
  http://localhost:8080/api/v1/serving/deployments/$DEPLOYMENT_ID/predict
# Expected: {"outputs": [{"name": "predict", "data": [[4.52...]]}]}
```

## Step 8: Verify Error Handling (US2 - Acceptance 2)

```bash
# Send malformed input
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"inputs": [{"name": "predict", "shape": [1, 3], "datatype": "FP64", "data": [[1.0, 2.0, 3.0]]}]}' \
  http://localhost:8080/api/v1/serving/deployments/$DEPLOYMENT_ID/predict
# Expected: 400 error with clear message about input shape mismatch
```

## Step 9: Verify Authentication (US3)

```bash
# Unauthenticated request (no token)
curl -X POST -H "Content-Type: application/json" \
  -d '{"inputs": [{"name": "predict", "shape": [1, 8], "datatype": "FP64", "data": [[1,2,3,4,5,6,7,8]]}]}' \
  http://localhost:8080/api/v1/serving/deployments/$DEPLOYMENT_ID/predict
# Expected: 401 Unauthorized
```

## Step 10: Verify Pipeline DAG Inference Call (US2 - Acceptance 3)

1. Create a notebook `inference-pipeline.ipynb`:

```python
import requests
import json

# Call inference endpoint (in-cluster URL)
endpoint_url = "http://user1-housing-regressor-v1-predictor.ml-platform-serving.svc.cluster.local/v2/models/user1-housing-regressor-v1/infer"

payload = {
    "inputs": [{
        "name": "predict",
        "shape": [3, 8],
        "datatype": "FP64",
        "data": [
            [8.3252, 41.0, 6.984, 1.024, 322.0, 2.556, 37.88, -122.23],
            [5.6431, 52.0, 5.817, 1.073, 558.0, 2.547, 37.85, -122.25],
            [3.8462, 52.0, 6.282, 1.081, 565.0, 2.181, 37.85, -122.26]
        ]
    }]
}

response = requests.post(endpoint_url, json=payload)
predictions = response.json()
print(f"Predictions: {predictions['outputs'][0]['data']}")
```

2. Trigger as a pipeline job (from feature 005 Pipelines UI).
3. Confirm the output notebook shows prediction values.

## Step 11: Verify User Isolation

1. Log in as `user2` in a different browser.
2. Navigate to "Models" — should see NO registered models.
3. Navigate to "Deployments" — should see NO deployments.

## Step 12: Verify Endpoint Availability (SC-005)

1. Leave the deployed endpoint running.
2. Send prediction requests periodically over 1 hour.
3. Confirm all requests return valid predictions.

## Dev Profile (no KServe needed)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The dev profile:
- Returns mock registered models and model versions
- Simulates deployment status transitions (DEPLOYING → READY)
- Returns mock prediction responses
- No KServe, MinIO, or MLflow deployment required
