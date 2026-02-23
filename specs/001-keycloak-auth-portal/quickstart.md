# Quickstart: Keycloak Auth & Portal Shell

**Feature**: `001-keycloak-auth-portal`

## Prerequisites

- Kubernetes cluster (local: minikube, kind, or Docker Desktop K8s)
- `kubectl` configured to target the cluster
- `helm` v3 installed
- Java 21 (for backend development)
- Node.js 20+ and npm (for frontend development)
- The `ml-platform` namespace exists on the cluster

## Step 1: Deploy Keycloak

```bash
# Create namespace if not exists
kubectl create namespace ml-platform --dry-run=client -o yaml | kubectl apply -f -

# Deploy PostgreSQL (shared by Keycloak and backend)
helm install postgresql oci://registry-1.docker.io/bitnamicharts/postgresql \
  --namespace ml-platform \
  --set auth.postgresPassword=localdevpassword \
  --set auth.database=keycloak \
  --set primary.persistence.size=1Gi

# Deploy Keycloak with realm config
kubectl apply -f infrastructure/k8s/keycloak/
```

Wait for Keycloak to be ready:

```bash
kubectl wait --for=condition=ready pod -l app=keycloak \
  -n ml-platform --timeout=120s
```

## Step 2: Port-Forward Services

```bash
# Terminal 1: Keycloak
kubectl port-forward svc/keycloak 8180:8080 -n ml-platform

# Terminal 2: PostgreSQL
kubectl port-forward svc/postgresql 5432:5432 -n ml-platform
```

## Step 3: Run Backend (local profile)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

The backend starts at `http://localhost:8080`.

Verify:

```bash
curl http://localhost:8080/api/v1/health
# Expected: {"status":"UP","timestamp":"..."}
```

## Step 4: Run Frontend

```bash
cd frontend
npm install
ng serve
```

The frontend starts at `http://localhost:4200`.

## Step 5: Verify Login Flow

1. Open `http://localhost:4200` in a browser.
2. You should be redirected to Keycloak login at
   `http://localhost:8180/realms/ml-platform/...`.
3. Log in with `user1` / `password1`.
4. You should be redirected back to the portal dashboard.
5. Your username should appear in the portal header.

## Step 6: Verify Navigation

1. Click "Notebooks" in the navigation → placeholder page loads.
2. Click "Experiments" → placeholder page loads.
3. Click "Pipelines" → placeholder page loads.

## Step 7: Verify Multi-User

1. Open a different browser (or incognito window).
2. Navigate to `http://localhost:4200`.
3. Log in with `user2` / `password2`.
4. Confirm both users see their own username in the header.

## Step 8: Verify Logout

1. Click the logout button.
2. Confirm you are redirected to the login page.
3. Navigate to `http://localhost:4200` — should require re-login.

## Step 9: Verify Token Refresh (optional)

1. Log in and note the time.
2. Wait > 1 hour (or temporarily reduce Keycloak access token
   lifespan to 1 minute for testing).
3. Confirm the portal continues working (silent refresh).
4. After 24 hours (or reduced refresh token lifespan), confirm
   redirect to login.

## Dev Profile (no Keycloak needed)

For rapid UI development without Keycloak:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The dev profile:
- Uses H2 in-memory database
- Mocks JWT validation (all requests pass as dev user)
- No Keycloak or PostgreSQL required
