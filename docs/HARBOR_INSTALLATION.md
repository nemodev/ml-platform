# Harbor Installation on RKE2 Kubernetes Cluster

This guide documents installing Harbor as a private container registry on an RKE2 cluster and configuring it for use with the ML Platform installer.

## Prerequisites

- RKE2 Kubernetes cluster with `kubectl` and `helm` access
- A StorageClass available for persistent volumes (e.g., `local-path`)
- A free NodePort (this guide uses **30500**)
- Docker or compatible container runtime on your build machine

## 1. Install Harbor via Helm

### Add the Harbor Helm repository

```bash
helm repo add harbor https://helm.goharbor.io
helm repo update
```

### Create a namespace

```bash
kubectl create namespace harbor
```

### Install with NodePort exposure

```bash
helm install harbor harbor/harbor \
  --namespace harbor \
  --set expose.type=nodePort \
  --set expose.nodePort.ports.http.nodePort=30500 \
  --set expose.tls.enabled=false \
  --set externalURL=http://<CLUSTER_IP>:30500 \
  --set harborAdminPassword=Harbor12345 \
  --set persistence.persistentVolumeClaim.registry.size=50Gi \
  --set persistence.persistentVolumeClaim.database.size=5Gi \
  --set persistence.persistentVolumeClaim.redis.size=1Gi \
  --set persistence.persistentVolumeClaim.jobservice.jobLog.size=1Gi \
  --wait --timeout 10m
```

Replace `<CLUSTER_IP>` with your cluster node IP (e.g., `172.16.100.10`).

### Verify deployment

```bash
kubectl -n harbor get pods
```

All pods should reach `Running` status:

```
harbor-core-...         1/1  Running
harbor-database-...     1/1  Running
harbor-jobservice-...   1/1  Running
harbor-portal-...       1/1  Running
harbor-redis-...        1/1  Running
harbor-registry-...     2/2  Running
```

The Harbor UI is accessible at `http://<CLUSTER_IP>:30500`. Log in with `admin` / `Harbor12345`.

## 2. Create a Harbor Project

1. Open `http://<CLUSTER_IP>:30500` in a browser
2. Log in as `admin`
3. Go to **Projects** > **New Project**
4. Name: `ml-platform`
5. Access Level: **Public** (so Kubernetes can pull without imagePullSecrets)
6. Click **OK**

Alternatively, via API:

```bash
curl -s -u admin:Harbor12345 \
  -H "Content-Type: application/json" \
  -X POST "http://<CLUSTER_IP>:30500/api/v2.0/projects" \
  -d '{"project_name": "ml-platform", "public": true}'
```

## 3. Configure Docker Client (Build Machine)

Harbor is running without TLS, so Docker must be told to trust it as an insecure registry.

### macOS / Linux (standard Docker)

Edit `~/.docker/daemon.json` (or `/etc/docker/daemon.json`):

```json
{
  "insecure-registries": ["<CLUSTER_IP>:30500"]
}
```

Restart Docker:

```bash
sudo systemctl restart docker
```

### Rancher Desktop

Rancher Desktop runs Docker inside a VM, so the config must be set **inside the VM**:

```bash
# Enter the VM shell
rdctl shell

# Write the daemon config
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "insecure-registries": ["<CLUSTER_IP>:30500"]
}
EOF

# Reload dockerd (find PID first)
DOCKERD_PID=$(ps aux | grep '[d]ockerd' | awk '{print $2}' | head -1)
sudo kill -HUP $DOCKERD_PID

# Verify
exit
docker info 2>/dev/null | grep -A5 "Insecure Registries"
```

You should see `<CLUSTER_IP>:30500` listed.

### Log in to Harbor

```bash
docker login <CLUSTER_IP>:30500 -u admin
# Password: Harbor12345
```

## 4. Configure RKE2 Nodes (Image Pull)

Each RKE2 node also needs to trust the insecure registry. Create the containerd registry config on **every node**:

```bash
# On each RKE2 node (or via ansible/ssh loop)
sudo mkdir -p /etc/rancher/rke2/registries.yaml.d

sudo tee /etc/rancher/rke2/registries.yaml <<'EOF'
mirrors:
  "<CLUSTER_IP>:30500":
    endpoint:
      - "http://<CLUSTER_IP>:30500"
EOF

# Restart RKE2 agent (or server on control plane nodes)
sudo systemctl restart rke2-agent   # worker nodes
sudo systemctl restart rke2-server  # control plane nodes
```

Verify containerd picks it up:

```bash
sudo crictl pull <CLUSTER_IP>:30500/ml-platform/backend:latest
```

## 5. Build and Push ML Platform Images

### Backend

```bash
cd backend
./gradlew build
docker build -t <CLUSTER_IP>:30500/ml-platform/backend:latest .
docker push <CLUSTER_IP>:30500/ml-platform/backend:latest
```

### Frontend

```bash
cd frontend
npm install && npm run build
docker build -t <CLUSTER_IP>:30500/ml-platform/frontend:latest .
docker push <CLUSTER_IP>:30500/ml-platform/frontend:latest
```

### Notebook Image

```bash
docker build -t <CLUSTER_IP>:30500/ml-platform/notebook:latest \
  infrastructure/docker/notebook-image/
docker push <CLUSTER_IP>:30500/ml-platform/notebook:latest
```

## 6. Configure the ML Platform Installer

Edit `infrastructure/installer/config.env` to point at Harbor:

```bash
# ─── Container Images ────────────────────────────────────────────────────────
BACKEND_IMAGE=<CLUSTER_IP>:30500/ml-platform/backend:latest
FRONTEND_IMAGE=<CLUSTER_IP>:30500/ml-platform/frontend:latest
NOTEBOOK_IMAGE=<CLUSTER_IP>:30500/ml-platform/notebook:latest
```

Then run the installer:

```bash
cd infrastructure/installer
./install.sh config.env
```

The installer renders image references into all Kubernetes manifests (backend Deployment, frontend Deployment, JupyterHub Helm values, Airflow DAG, provision Job).

## 7. Verify Image Pull

After installation, confirm pods are pulling from Harbor:

```bash
kubectl -n <NAMESPACE> get pods -o wide
kubectl -n <NAMESPACE> describe pod <backend-pod> | grep "Image:"
```

Expected output:

```
Image: <CLUSTER_IP>:30500/ml-platform/backend:latest
```

## Reference: Example config.env for r1 Cluster

```bash
# ML Platform Installer Configuration — r1 cluster
NAMESPACE=ml-platform

BACKEND_IMAGE=172.16.100.10:30500/ml-platform/backend:latest
FRONTEND_IMAGE=172.16.100.10:30500/ml-platform/frontend:latest
NOTEBOOK_IMAGE=172.16.100.10:30500/ml-platform/notebook:latest

PLATFORM_URL=http://172.16.100.10:30080
FRONTEND_SERVICE_TYPE=NodePort
FRONTEND_NODE_PORT=30080

DEPLOY_KEYCLOAK=true
KEYCLOAK_REALM=ml-platform
KEYCLOAK_PORTAL_CLIENT_ID=ml-platform-portal
KEYCLOAK_JUPYTERHUB_CLIENT_ID=ml-platform-jupyterhub
KEYCLOAK_JUPYTERHUB_CLIENT_SECRET=ml-platform-jupyterhub-secret
JUPYTERHUB_API_TOKEN=ml-platform-jupyterhub-token

S3_ENDPOINT=http://minio.ml-platform.svc:9000
S3_ACCESS_KEY=minioadmin
S3_SECRET_KEY=minioadmin
S3_REGION=us-east-1
S3_BUCKET=ml-platform

DEPLOY_POSTGRESQL=true
POSTGRES_PASSWORD=localdevpassword
CREATE_KEYCLOAK_DB=true

DEPLOY_OBC=false
DNS_RESOLVER=10.43.0.10
```

## Troubleshooting

### "http: server gave HTTP response to HTTPS client"

Docker is trying HTTPS but Harbor is running without TLS. Ensure `insecure-registries` is configured (see Section 3). For Rancher Desktop, the config must be set **inside the VM**, not on the host.

### "push access denied ... no basic auth credentials"

Run `docker login <CLUSTER_IP>:30500 -u admin` and enter the Harbor password.

### Image pull fails in Kubernetes (ErrImagePull)

RKE2 containerd doesn't share Docker's insecure registry config. Configure the containerd mirror on each node (see Section 4).

### Pods stuck in ImagePullBackOff after pushing new image

Kubernetes may cache the old image digest. Force a rollout:

```bash
kubectl -n <NAMESPACE> rollout restart deployment/backend
```

Or use `imagePullPolicy: Always` in the deployment spec (slower but always fresh).
