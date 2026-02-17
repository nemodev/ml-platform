# Quickstart: JupyterHub Notebook Embedding

**Feature**: `002-jupyterhub-notebook`

## Prerequisites

- Feature 001 (Keycloak Auth & Portal Shell) is deployed and working
- Kubernetes cluster with Keycloak and PostgreSQL running in
  `ml-platform` namespace
- `helm` v3 installed
- Port-forwards active for Keycloak (8180) and backend (8080)
- The custom notebook Docker image has been built and is available
  to the cluster (loaded into local registry or pulled from remote)

## Step 1: Build the Notebook Image

```bash
cd infrastructure/docker/notebook-image
docker build -t ml-platform-notebook:latest .
```

For local K8s (kind/minikube), load the image:

```bash
# kind
kind load docker-image ml-platform-notebook:latest

# minikube
minikube image load ml-platform-notebook:latest
```

## Step 2: Create JupyterHub Database

```bash
# Port-forward PostgreSQL if not already active
kubectl port-forward svc/postgresql 5432:5432 -n ml-platform

# Create the jupyterhub database
PGPASSWORD=localdevpassword psql -h localhost -U postgres \
  -c "CREATE DATABASE jupyterhub;"
```

## Step 3: Add JupyterHub Keycloak Client

The Keycloak realm ConfigMap should include the
`ml-platform-jupyterhub` confidential client. If updating an existing
deployment:

```bash
kubectl apply -f infrastructure/k8s/keycloak/configmap.yaml
# Restart Keycloak to pick up realm changes
kubectl rollout restart deployment/keycloak -n ml-platform
```

## Step 4: Deploy JupyterHub

```bash
helm install jupyterhub infrastructure/helm/jupyterhub/ \
  --namespace ml-platform \
  -f infrastructure/helm/jupyterhub/local-values.yaml
```

Wait for JupyterHub to be ready:

```bash
kubectl wait --for=condition=ready pod -l app=jupyterhub \
  -l component=hub -n ml-platform --timeout=120s
```

## Step 5: Port-Forward JupyterHub

```bash
# Terminal 3: JupyterHub
kubectl port-forward svc/proxy-public 8181:80 -n ml-platform
```

## Step 6: Run Backend with Workspace Endpoints

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Verify workspace endpoint:

```bash
# Get a token first (requires jq)
TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/ml-platform/protocol/openid-connect/token" \
  -d "client_id=ml-platform-cli" \
  -d "username=scientist1" \
  -d "password=password1" \
  -d "grant_type=password" \
  -d "scope=openid" | jq -r '.access_token')

# Check workspace status
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/workspaces
# Expected: {"status":"STOPPED"}

# List profiles
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/workspaces/profiles
# Expected: [{"id":"exploratory","name":"Exploratory",...}]
```

## Step 7: Run Frontend

```bash
cd frontend
npm install
ng serve
```

## Step 8: Verify Notebook Embedding (US1)

1. Open `http://localhost:4200` in a browser.
2. Log in with `scientist1` / `password1`.
3. Click "Notebooks" in the navigation sidebar.
4. A workspace launcher should appear (or auto-launch).
5. Wait for the server to start (PENDING → RUNNING).
6. The JupyterLab interface should load in an embedded iframe.
7. Verify you can see the file browser with an empty home directory.

## Step 9: Verify Code Execution (US2)

1. In the embedded JupyterLab, click "+" to open a new launcher.
2. Click "Python 3" under Notebook to create a new notebook.
3. In the first cell, type:
   ```python
   import pandas as pd
   pd.DataFrame({'a': [1,2,3], 'b': [4,5,6]})
   ```
4. Press Shift+Enter — the dataframe should render below the cell.
5. In a new cell, verify all libraries:
   ```python
   import numpy, scipy, sklearn, matplotlib, seaborn
   import plotly, torch, tensorflow, mlflow
   print("All imports successful!")
   ```
6. Press Shift+Enter — should print "All imports successful!".

## Step 10: Verify SSO Passthrough (US3)

1. Note that you did NOT see a JupyterHub login page.
2. Open browser DevTools → Network tab.
3. Observe that the iframe loaded without any 302 redirect to a
   login page (or if redirected, it was a silent OIDC flow).
4. Refresh the page — the notebook should reload without login.

## Step 11: Verify User Isolation (SC-003)

1. Open a different browser (or incognito window).
2. Navigate to `http://localhost:4200`.
3. Log in with `scientist2` / `password2`.
4. Click "Notebooks" — a separate workspace should launch.
5. Create a notebook named "scientist2-test.ipynb".
6. Switch back to scientist1's browser — scientist2's notebook
   should NOT be visible.

## Step 12: Verify Persistence (SC-005)

1. In scientist1's notebook, create and save a file.
2. Go back to the portal dashboard (or close the browser tab).
3. Wait for the idle culler to stop the server (30 minutes), or
   manually terminate via the portal.
4. Navigate back to Notebooks — the server should respawn.
5. Verify the previously saved file is still present.

## Dev Profile (no JupyterHub needed)

For rapid UI development without JupyterHub:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The dev profile:
- Returns mock workspace status (always RUNNING)
- Returns a placeholder JupyterLab URL (or static demo page)
- No JupyterHub deployment required
