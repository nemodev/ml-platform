#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <context> <node_ip> <backend_image> <frontend_image> <notebook_image>"
  exit 1
fi

CONTEXT="$1"
NODE_IP="$2"
BACKEND_IMAGE="$3"
FRONTEND_IMAGE="$4"
NOTEBOOK_IMAGE="$5"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NS="ml-platform"
SERVING_NS="ml-platform-serving"
KSERVE_VERSION="${KSERVE_VERSION:-v0.16.0}"
KSERVE_RELEASE_URL="https://github.com/kserve/kserve/releases/download/${KSERVE_VERSION}"
KSERVE_CRD_BASE_URL="https://raw.githubusercontent.com/kserve/kserve/${KSERVE_VERSION}/config/crd/full"
KSERVE_DEFAULT_DEPLOYMENT_MODE="${KSERVE_DEFAULT_DEPLOYMENT_MODE:-Standard}"
KSERVE_SYSTEM_NAMESPACE="${KSERVE_SYSTEM_NAMESPACE:-kserve}"

KSERVE_CRD_FILES=(
  serving.kserve.io_inferenceservices.yaml
  serving.kserve.io_trainedmodels.yaml
  serving.kserve.io_clusterservingruntimes.yaml
  serving.kserve.io_servingruntimes.yaml
  serving.kserve.io_inferencegraphs.yaml
  serving.kserve.io_clusterstoragecontainers.yaml
  serving.kserve.io_localmodelcaches.yaml
  serving.kserve.io_localmodelnodegroups.yaml
  serving.kserve.io_localmodelnodes.yaml
  llmisvc/serving.kserve.io_llminferenceservices.yaml
  llmisvc/serving.kserve.io_llminferenceserviceconfigs.yaml
)
OVERLAY="local"
if [[ "$CONTEXT" == "r1" ]]; then
  OVERLAY="r1"
fi

NOTEBOOK_IMAGE_NAME="${NOTEBOOK_IMAGE%:*}"
NOTEBOOK_IMAGE_TAG="${NOTEBOOK_IMAGE##*:}"

k() {
  kubectl --context "$CONTEXT" "$@"
}

h() {
  helm --kube-context "$CONTEXT" "$@"
}

apply_kserve_crds() {
  local crd_file
  for crd_file in "${KSERVE_CRD_FILES[@]}"; do
    echo "[${CONTEXT}] applying KServe CRD ${crd_file}"
    k apply --server-side --force-conflicts -f "${KSERVE_CRD_BASE_URL}/${crd_file}"
  done
}

wait_for_kserve_crds() {
  local crd
  for crd in \
    inferenceservices.serving.kserve.io \
    trainedmodels.serving.kserve.io \
    clusterservingruntimes.serving.kserve.io \
    servingruntimes.serving.kserve.io \
    inferencegraphs.serving.kserve.io \
    clusterstoragecontainers.serving.kserve.io \
    localmodelcaches.serving.kserve.io \
    localmodelnodegroups.serving.kserve.io \
    localmodelnodes.serving.kserve.io \
    llminferenceservices.serving.kserve.io \
    llminferenceserviceconfigs.serving.kserve.io; do
    echo "[${CONTEXT}] waiting for CRD ${crd}"
    k wait --for=condition=Established "crd/${crd}" --timeout=180s
  done
}

reconcile_kserve_release() {
  echo "[${CONTEXT}] reconciling kserve ${KSERVE_VERSION}"
  if ! k get crd certificates.cert-manager.io >/dev/null 2>&1; then
    echo "[${CONTEXT}] ERROR: cert-manager CRDs are missing (certificates.cert-manager.io not found)"
    echo "[${CONTEXT}] install cert-manager first, then retry"
    return 1
  fi

  echo "[${CONTEXT}] applying KServe CRDs from ${KSERVE_VERSION}"
  apply_kserve_crds
  wait_for_kserve_crds

  # Apply controller/resources only after CRDs are established.
  k apply --server-side --force-conflicts -f "${KSERVE_RELEASE_URL}/kserve.yaml"
  k apply --server-side --force-conflicts -f "${KSERVE_RELEASE_URL}/kserve-cluster-resources.yaml"
}

echo "[${CONTEXT}] preparing helm repositories"
helm repo add bitnami https://charts.bitnami.com/bitnami --force-update >/dev/null
helm repo add minio https://charts.min.io/ --force-update >/dev/null
helm repo add apache-airflow https://airflow.apache.org --force-update >/dev/null
helm repo update >/dev/null

echo "[${CONTEXT}] ensuring namespaces"
k create namespace "$NS" --dry-run=client -o yaml | k apply -f -
k create namespace "$SERVING_NS" --dry-run=client -o yaml | k apply -f -

echo "[${CONTEXT}] deploying postgresql"
h upgrade --install postgresql oci://registry-1.docker.io/bitnamicharts/postgresql \
  -n "$NS" \
  --set auth.postgresPassword=localdevpassword \
  --set auth.database=keycloak \
  --set primary.persistence.size=20Gi \
  --wait --timeout 15m

POSTGRES_POD="$(k -n "$NS" get pod -l app.kubernetes.io/instance=postgresql,app.kubernetes.io/name=postgresql -o jsonpath='{.items[0].metadata.name}')"
echo "[${CONTEXT}] ensuring postgresql databases"
k -n "$NS" exec "$POSTGRES_POD" -- sh -lc '
set -eu
export PGPASSWORD=localdevpassword
for db in keycloak ml_platform mlflow airflow jupyterhub; do
  if ! psql -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='\''${db}'\''" | grep -q 1; then
    psql -U postgres -d postgres -c "CREATE DATABASE ${db};"
  fi
done
'

echo "[${CONTEXT}] deploying keycloak"
k apply -f "$ROOT_DIR/infrastructure/k8s/keycloak/"
k -n "$NS" set env deployment/keycloak KC_HOSTNAME="http://${NODE_IP}:30080"
k -n "$NS" rollout status deploy/keycloak --timeout=15m

echo "[${CONTEXT}] configuring keycloak portal client"
KEYCLOAK_POD="$(k -n "$NS" get pod -l app=keycloak -o jsonpath='{.items[0].metadata.name}')"
k -n "$NS" exec "$KEYCLOAK_POD" -- sh -lc '
set -eu
/opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "$KEYCLOAK_ADMIN" \
  --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
CLIENT_ID=$(/opt/keycloak/bin/kcadm.sh get clients -r ml-platform -q clientId=ml-platform-portal --fields id --format csv --noquotes | head -n1)
if [ -z "$CLIENT_ID" ]; then
  echo "ml-platform-portal client not found" >&2
  exit 1
fi
/opt/keycloak/bin/kcadm.sh update clients/$CLIENT_ID -r ml-platform \
  -s '\''standardFlowEnabled=true'\'' \
  -s '\''implicitFlowEnabled=true'\'' >/dev/null
'

echo "[${CONTEXT}] deploying minio"
h upgrade --install minio minio/minio \
  -n "$NS" \
  -f "$ROOT_DIR/infrastructure/helm/minio/local-values.yaml" \
  --wait --timeout 15m

echo "[${CONTEXT}] deploying mlflow"
h upgrade --install mlflow "$ROOT_DIR/infrastructure/helm/mlflow" \
  -n "$NS" \
  -f "$ROOT_DIR/infrastructure/helm/mlflow/values.yaml" \
  --wait --timeout 15m

echo "[${CONTEXT}] deploying jupyterhub"
JH_VALUES="$(mktemp)"
cat > "$JH_VALUES" <<EOF
jupyterhub:
  prePuller:
    hook:
      enabled: false
    continuous:
      enabled: false
  hub:
    networkPolicy:
      enabled: false
    config:
      GenericOAuthenticator:
        oauth_callback_url: http://${NODE_IP}:30080/hub/oauth_callback
        authorize_url: http://${NODE_IP}:30080/realms/ml-platform/protocol/openid-connect/auth
      KubeSpawner:
        args:
          - "--ServerApp.tornado_settings={\"headers\":{\"Content-Security-Policy\":\"frame-ancestors 'self' http://${NODE_IP}:30080\"}}"
    extraConfig:
      00-security.py: |
        c.JupyterHub.tornado_settings = {
          "headers": {
            "Content-Security-Policy": "frame-ancestors 'self' http://${NODE_IP}:30080"
          }
        }
  proxy:
    chp:
      networkPolicy:
        enabled: false
  singleuser:
    networkPolicy:
      enabled: false
    image:
      name: ${NOTEBOOK_IMAGE_NAME}
      tag: ${NOTEBOOK_IMAGE_TAG}
      pullPolicy: Always
EOF
h upgrade --install jupyterhub "$ROOT_DIR/infrastructure/helm/jupyterhub" \
  -n "$NS" \
  -f "$ROOT_DIR/infrastructure/helm/jupyterhub/values.yaml" \
  -f "$JH_VALUES" \
  --wait --timeout 20m
rm -f "$JH_VALUES"

echo "[${CONTEXT}] deploying airflow"
h upgrade --install airflow apache-airflow/airflow \
  -n "$NS" \
  -f "$ROOT_DIR/infrastructure/helm/airflow/local-values.yaml" \
  --wait --timeout 20m

echo "[${CONTEXT}] applying airflow manifests"
DAG_MANIFEST="$(mktemp)"
sed "s|ml-platform-notebook:latest|${NOTEBOOK_IMAGE}|g" \
  "$ROOT_DIR/infrastructure/k8s/airflow/dag-configmap.yaml" > "$DAG_MANIFEST"
k apply -f "$ROOT_DIR/infrastructure/k8s/airflow/spark-rbac.yaml"
k apply -f "$DAG_MANIFEST"
rm -f "$DAG_MANIFEST"

echo "[${CONTEXT}] provisioning sample data manifests"
k apply -f "$ROOT_DIR/infrastructure/k8s/sample-data/read-only-secret.yaml"
k apply -f "$ROOT_DIR/infrastructure/k8s/sample-data/sample-notebook-configmap.yaml"
k apply -f "$ROOT_DIR/infrastructure/k8s/sample-data/batch-inference-notebook-configmap.yaml"
k apply -f "$ROOT_DIR/infrastructure/k8s/sample-data/provision-script-configmap.yaml"

PROVISION_JOB="$(mktemp)"
sed "s|ml-platform-notebook:latest|${NOTEBOOK_IMAGE}|g" \
  "$ROOT_DIR/infrastructure/k8s/sample-data/provision-job.yaml" > "$PROVISION_JOB"
k -n "$NS" delete job provision-sample-data --ignore-not-found
k apply -f "$PROVISION_JOB"
rm -f "$PROVISION_JOB"
k -n "$NS" wait --for=condition=complete job/provision-sample-data --timeout=20m

reconcile_kserve_release
k -n "$KSERVE_SYSTEM_NAMESPACE" rollout status deployment/kserve-controller-manager --timeout=300s
if k -n "$KSERVE_SYSTEM_NAMESPACE" get configmap inferenceservice-config >/dev/null 2>&1; then
  k patch configmap/inferenceservice-config \
    -n "$KSERVE_SYSTEM_NAMESPACE" \
    --type=merge \
    -p "{\"data\":{\"deploy\":\"{\\\"defaultDeploymentMode\\\":\\\"${KSERVE_DEFAULT_DEPLOYMENT_MODE}\\\"}\"}}"
  k -n "$KSERVE_SYSTEM_NAMESPACE" rollout restart deployment/kserve-controller-manager
  k -n "$KSERVE_SYSTEM_NAMESPACE" rollout status deployment/kserve-controller-manager --timeout=300s
fi
# Keep existing endpoints aligned with the cluster default mode name.
k -n "$SERVING_NS" annotate inferenceservices.serving.kserve.io \
  --all serving.kserve.io/deploymentMode="${KSERVE_DEFAULT_DEPLOYMENT_MODE}" \
  --overwrite >/dev/null 2>&1 || true
k apply -f "$ROOT_DIR/infrastructure/k8s/kserve/serving-namespace.yaml"
k apply -f "$ROOT_DIR/infrastructure/k8s/kserve/s3-secret.yaml"
k apply -f "$ROOT_DIR/infrastructure/k8s/kserve/service-account.yaml"

echo "[${CONTEXT}] deploying backend and frontend"
k apply -k "$ROOT_DIR/infrastructure/k8s/platform/overlays/${OVERLAY}"
k -n "$NS" set image deployment/backend backend="$BACKEND_IMAGE"
k -n "$NS" set image deployment/frontend frontend="$FRONTEND_IMAGE"
k -n "$NS" set env deployment/backend \
  SERVICES_JUPYTERHUB_PUBLIC_URL="http://${NODE_IP}:30080" \
  SERVICES_MLFLOW_TRACKING_URL="/mlflow"
k -n "$NS" rollout status deployment/backend --timeout=20m
k -n "$NS" rollout status deployment/frontend --timeout=20m

echo "[${CONTEXT}] deployment complete"
k -n "$NS" get svc frontend proxy-public
k -n "$NS" get deploy backend frontend
k -n "$SERVING_NS" get sa kserve-s3-sa
