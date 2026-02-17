# Quickstart: Airflow Notebook Pipeline

**Feature**: `005-airflow-notebook-pipeline`

## Prerequisites

- Feature 001 (Keycloak Auth & Portal Shell) deployed and working
- Feature 002 (JupyterHub Notebook Embedding) deployed and working
- Feature 003 (MLflow Experiment Tracking) deployed — provides MinIO
- Kubernetes cluster with PostgreSQL running in `ml-platform` namespace
- Port-forwards active for Keycloak (8180), backend (8080),
  JupyterHub (8181), MinIO (9000)

## Step 1: Create Airflow Database

```bash
kubectl port-forward svc/postgresql 5432:5432 -n ml-platform

PGPASSWORD=localdevpassword psql -h localhost -U postgres \
  -c "CREATE DATABASE airflow;"
```

## Step 2: Create Pipelines MinIO Bucket

```bash
# Using mc (MinIO Client)
mc alias set local http://localhost:9000 minioadmin minioadmin
mc mb local/ml-platform-pipelines
```

## Step 3: Deploy Airflow

```bash
helm repo add apache-airflow https://airflow.apache.org
helm repo update

helm install airflow apache-airflow/airflow \
  --namespace ml-platform \
  -f infrastructure/helm/airflow/local-values.yaml
```

Wait for Airflow to be ready:

```bash
kubectl wait --for=condition=ready pod -l component=scheduler \
  -n ml-platform --timeout=180s
```

## Step 4: Deploy Spark RBAC

Create the ServiceAccount and Role for Spark executor pod creation:

```bash
kubectl apply -f infrastructure/k8s/airflow/spark-rbac.yaml \
  -n ml-platform
```

## Step 5: Deploy DAG Template

Deploy the notebook_runner DAG as a ConfigMap:

```bash
kubectl apply -f infrastructure/k8s/airflow/dag-configmap.yaml \
  -n ml-platform
```

Verify the DAG is loaded:

```bash
kubectl port-forward svc/airflow-webserver 8280:8080 -n ml-platform

curl -u admin:admin http://localhost:8280/api/v1/dags/notebook_runner
# Expected: {"dag_id": "notebook_runner", ...}
```

## Step 6: Run Backend with Airflow Config

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Verify pipeline endpoints:

```bash
TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/ml-platform/protocol/openid-connect/token" \
  -d "client_id=ml-platform-cli" \
  -d "username=scientist1" \
  -d "password=password1" \
  -d "grant_type=password" \
  -d "scope=openid" | jq -r '.access_token')

# List pipelines (should be empty)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/pipelines
# Expected: []

# List available notebooks
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/pipelines/notebooks
# Expected: list of .ipynb files from user's workspace
```

## Step 7: Run Frontend

```bash
cd frontend
npm install
ng serve
```

## Step 8: Verify Notebook Pipeline Execution (US1)

1. Open `http://localhost:4200`, log in as `scientist1`.
2. Click "Notebooks" → launch workspace.
3. Create a simple notebook `hello-pipeline.ipynb`:
   ```python
   print("Hello from pipeline!")
   import datetime
   print(f"Executed at: {datetime.datetime.now()}")
   ```
4. Save the notebook.
5. Navigate to "Pipelines" in the portal sidebar.
6. Click "Run Pipeline" → select `hello-pipeline.ipynb`.
7. Click "Trigger" (no parameters needed).
8. Watch the status change: PENDING → RUNNING → SUCCEEDED.
9. Click "View Output" to download the executed notebook.
10. Verify the output notebook shows "Hello from pipeline!" and
    the timestamp in the cell outputs.

## Step 9: Verify Failed Pipeline Output (US1 - Acceptance 4)

1. Create a notebook `fail-test.ipynb`:
   ```python
   print("This cell works")
   ```
   ```python
   raise ValueError("Intentional failure")
   ```
2. Trigger it as a pipeline.
3. Wait for status: FAILED.
4. View the output — the first cell should show output, the second
   cell should show the ValueError with traceback.

## Step 10: Verify Spark Pipeline (US2)

1. Create a notebook `spark-test.ipynb`:
   ```python
   from pyspark.sql import SparkSession

   spark = SparkSession.builder \
       .appName("pipeline-spark-test") \
       .getOrCreate()

   df = spark.range(1000)
   print(f"Count: {df.count()}")
   print(f"Executors: {spark.sparkContext._jsc.sc().getExecutorMemoryStatus().size()}")
   spark.stop()
   ```
2. Navigate to "Pipelines" → "Run Pipeline".
3. Select `spark-test.ipynb` and enable "Spark" toggle.
4. Trigger the pipeline.
5. While running, verify executor pods exist:
   ```bash
   kubectl get pods -n ml-platform -l spark-role=executor
   ```
6. After completion (SUCCEEDED), verify the output notebook shows
   Count and Executors values.
7. Verify executor pods are cleaned up:
   ```bash
   kubectl get pods -n ml-platform -l spark-role=executor
   # Expected: No resources found
   ```

## Step 11: Verify Experiment Tracking from Pipeline (US3)

1. Create a notebook `mlflow-pipeline.ipynb`:
   ```python
   import mlflow
   from sklearn.ensemble import RandomForestClassifier
   from sklearn.datasets import load_iris
   from sklearn.model_selection import train_test_split
   from sklearn.metrics import accuracy_score

   X, y = load_iris(return_X_y=True)
   X_train, X_test, y_train, y_test = train_test_split(X, y)

   mlflow.set_experiment("pipeline-iris")
   with mlflow.start_run():
       model = RandomForestClassifier(n_estimators=100)
       model.fit(X_train, y_train)
       accuracy = accuracy_score(y_test, model.predict(X_test))
       mlflow.log_param("n_estimators", 100)
       mlflow.log_param("source", "pipeline")
       mlflow.log_metric("accuracy", accuracy)
       print(f"Pipeline run logged! Accuracy: {accuracy:.4f}")
   ```
2. Trigger as a pipeline (no Spark needed).
3. Wait for SUCCEEDED status.
4. Navigate to "Experiments" in the portal.
5. Find the "pipeline-iris" experiment.
6. Verify the run shows `source=pipeline` parameter and accuracy
   metric.

## Step 12: Verify User Isolation

1. Log in as `scientist2` in a different browser.
2. Navigate to "Pipelines" — should see NO runs.
3. Trigger a pipeline as scientist2.
4. Switch to scientist1's browser — scientist2's run should NOT
   be visible.

## Dev Profile (no Airflow needed)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The dev profile:
- Returns mock pipeline run data (no Airflow server needed)
- Simulates status transitions (PENDING → RUNNING → SUCCEEDED)
- No MinIO, Airflow, or Spark deployment required
