# Platform App Manifests (kustomize)

This directory deploys the portal application workloads:
- `backend` (Spring Boot API)
- `frontend` (Angular + nginx)

Namespaces are managed outside this kustomization:
- `ml-platform`
- `ml-platform-serving`

## Structure

- `base/`: shared manifests
- `overlays/local/`: local cluster overlay
  - frontend exposed through `NodePort` `30080`
- `overlays/r1/`: production-style overlay for remote cluster `r1`
  - frontend exposed through `NodePort` `30080`
  - increased replica count and resource requests/limits

## Build and Push Images

Both deployments default to:
- `ml-platform-backend:latest`
- `ml-platform-frontend:latest`

For a remote cluster, push images to a registry reachable by cluster nodes
and update image names/tags before applying the overlay.

## Deploy

```bash
kubectl --context r1 get ns ml-platform ml-platform-serving
kubectl --context r1 apply -k infrastructure/k8s/platform/overlays/r1
kubectl --context r1 -n ml-platform rollout status deploy/backend
kubectl --context r1 -n ml-platform rollout status deploy/frontend
kubectl --context r1 -n ml-platform get svc frontend
```

Frontend URL (NodePort):

`http://<node-ip>:30080`

Notebook URL base (set on backend deployment):

`SERVICES_JUPYTERHUB_PUBLIC_URL=http://<node-ip>:30081`

## Keycloak Redirect URI Requirement

If Keycloak client `ml-platform-portal` does not already allow the NodePort
origin, add it before browser login tests:

`http://<node-ip>:30080/*`
