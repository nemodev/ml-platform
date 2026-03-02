#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# ML Platform Installer
#
# Deploys all platform components EXCEPT S3 storage, which is expected to be
# pre-existing. Keycloak is optionally deployed (DEPLOY_KEYCLOAK=true).
# See config.env.example for all options.
#
# Usage:
#   ./install.sh [config-file]       # config-file defaults to ./config.env
#   ./install.sh --render-only       # generate manifests without applying
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TEMPLATES_DIR="${SCRIPT_DIR}/templates"
BUILD_DIR="${SCRIPT_DIR}/.build"

RENDER_ONLY=false
CONFIG_FILE="${SCRIPT_DIR}/config.env"

for arg in "$@"; do
  case "$arg" in
    --render-only) RENDER_ONLY=true ;;
    *) CONFIG_FILE="$arg" ;;
  esac
done

# ── Load configuration ───────────────────────────────────────────────────────
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "ERROR: Config file not found: $CONFIG_FILE"
  echo "Copy config.env.example to config.env and edit it."
  exit 1
fi
echo "Loading config from: $CONFIG_FILE"
# shellcheck disable=SC1090
source "$CONFIG_FILE"

# ── Derived variables ────────────────────────────────────────────────────────

# Map user-facing config names to internal template variables
S3_ENDPOINT="${S3_INTERNAL_ENDPOINT}"
S3_PREFIX="${S3_PREFIX:-ml-platform}"
S3_PREFIX="${S3_PREFIX#/}"
S3_PREFIX="${S3_PREFIX%/}"
if [[ -z "${S3_PREFIX}" ]]; then
  echo "ERROR: S3_PREFIX must not be empty"
  exit 1
fi
S3_PIPELINES_PREFIX="${S3_PREFIX}/pipelines"
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

# S3 endpoint without protocol (for KServe annotations, Airflow connection URI)
S3_ENDPOINT_HOST="${S3_ENDPOINT#http://}"
S3_ENDPOINT_HOST="${S3_ENDPOINT_HOST#https://}"

# S3 flags
if [[ "$S3_ENDPOINT" == https://* ]]; then
  S3_USE_HTTPS=1
  S3_SECURE=true
  S3_ALLOW_HTTP=false
  S3_IGNORE_TLS=false
  S3A_SSL_ENABLED=true
else
  S3_USE_HTTPS=0
  S3_SECURE=false
  S3_ALLOW_HTTP=true
  S3_IGNORE_TLS=true
  S3A_SSL_ENABLED=false
fi

# URL-encoded S3 endpoint (for Airflow connection URI)
S3_ENDPOINT_URLENCODED=$(echo "$S3_ENDPOINT" | sed 's|:|%3A|g; s|/|%2F|g')

# PostgreSQL
if [[ "${DEPLOY_POSTGRESQL:-true}" == "true" ]]; then
  POSTGRES_HOST="${POSTGRES_HOST:-postgresql.${NAMESPACE}.svc}"
  POSTGRES_PORT="${POSTGRES_PORT:-5432}"
fi
: "${POSTGRES_HOST:?POSTGRES_HOST is required when DEPLOY_POSTGRESQL=false}"
: "${POSTGRES_PORT:?POSTGRES_PORT is required when DEPLOY_POSTGRESQL=false}"

# Keycloak
if [[ "${DEPLOY_KEYCLOAK:-false}" == "true" ]]; then
  KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak.${NAMESPACE}.svc.${CLUSTER_DOMAIN:-cluster.local}:8080}"
fi
: "${KEYCLOAK_ADMIN_PASSWORD:=admin}"

# Keycloak derived URLs
KEYCLOAK_ISSUER_URI="${PLATFORM_URL}/realms/${KEYCLOAK_REALM}"
KEYCLOAK_JWK_SET_URI="${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs"
KEYCLOAK_AUTHORIZE_URL="${PLATFORM_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/auth"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token"
KEYCLOAK_USERDATA_URL="${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/userinfo"
KEYCLOAK_OAUTH_CALLBACK_URL="${PLATFORM_URL}/hub/oauth_callback"

# Notebook image parts (for JupyterHub Helm)
NOTEBOOK_IMAGE_NAME="${NOTEBOOK_IMAGE%:*}"
NOTEBOOK_IMAGE_TAG="${NOTEBOOK_IMAGE##*:}"

# Frontend service ports
FRONTEND_HTTP_PORT="${FRONTEND_HTTP_PORT:-80}"

# Frontend NodePort line (conditionally rendered)
if [[ "${FRONTEND_SERVICE_TYPE}" == "NodePort" ]]; then
  NODEPORT_LINE="nodePort: ${FRONTEND_NODE_PORT}"
else
  NODEPORT_LINE=""
fi

# LoadBalancer IP (optional — for kube-vip or cloud LB to assign a specific IP)
if [[ "${FRONTEND_SERVICE_TYPE}" == "LoadBalancer" ]] && [[ -n "${FRONTEND_LOADBALANCER_IP:-}" ]]; then
  LOADBALANCER_IP="loadBalancerIP: ${FRONTEND_LOADBALANCER_IP}"
else
  LOADBALANCER_IP=""
fi

# ── TLS variables ──────────────────────────────────────────────────────────
if [[ "${TLS_ENABLED:-false}" == "true" ]]; then
  # Extract hostname/IP from PLATFORM_URL
  TLS_HOSTNAME="${PLATFORM_URL#https://}"
  TLS_HOSTNAME="${TLS_HOSTNAME#http://}"
  TLS_HOSTNAME="${TLS_HOSTNAME%%:*}"
  TLS_HOSTNAME="${TLS_HOSTNAME%%/*}"

  # Nginx config directives
  TLS_LISTEN="listen 8443 ssl;"
  TLS_SSL_CERT="ssl_certificate /etc/nginx/tls/tls.crt;"
  TLS_SSL_KEY="ssl_certificate_key /etc/nginx/tls/tls.key;"
  TLS_SSL_PROTOCOLS="ssl_protocols TLSv1.2 TLSv1.3;"

  # Deployment YAML (flow-style for single-line sed replacement)
  TLS_PORT="- {name: https, containerPort: 8443}"
  TLS_VOLUME_MOUNT="- {name: tls-cert, mountPath: /etc/nginx/tls, readOnly: true}"
  TLS_VOLUME="- {name: tls-cert, secret: {secretName: frontend-tls}}"

  # Service YAML
  FRONTEND_HTTPS_PORT="${FRONTEND_HTTPS_PORT:-443}"
  if [[ "${FRONTEND_SERVICE_TYPE}" == "NodePort" ]]; then
    TLS_SERVICE_PORT="- {name: https, port: ${FRONTEND_HTTPS_PORT}, targetPort: https, nodePort: ${FRONTEND_HTTPS_NODE_PORT:-30443}}"
  else
    TLS_SERVICE_PORT="- {name: https, port: ${FRONTEND_HTTPS_PORT}, targetPort: https}"
  fi
else
  TLS_LISTEN=""
  TLS_SSL_CERT=""
  TLS_SSL_KEY=""
  TLS_SSL_PROTOCOLS=""
  TLS_PORT=""
  TLS_VOLUME_MOUNT=""
  TLS_VOLUME=""
  TLS_SERVICE_PORT=""
  TLS_HOSTNAME=""
fi

# ── Validate required config ────────────────────────────────────────────────
missing=()
for var in NAMESPACE BACKEND_IMAGE FRONTEND_IMAGE NOTEBOOK_IMAGE \
           PLATFORM_URL FRONTEND_SERVICE_TYPE KEYCLOAK_REALM \
           KEYCLOAK_PORTAL_CLIENT_ID KEYCLOAK_JUPYTERHUB_CLIENT_ID \
           KEYCLOAK_JUPYTERHUB_CLIENT_SECRET JUPYTERHUB_API_TOKEN \
           S3_INTERNAL_ENDPOINT S3_ACCESS_KEY S3_SECRET_KEY S3_REGION S3_BUCKET \
           POSTGRES_PASSWORD DNS_RESOLVER; do
  if [[ -z "${!var:-}" ]]; then
    missing+=("$var")
  fi
done
if [[ "${DEPLOY_KEYCLOAK:-false}" != "true" ]] && [[ -z "${KEYCLOAK_URL:-}" ]]; then
  missing+=("KEYCLOAK_URL")
fi
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "ERROR: Missing required configuration variables:"
  printf '  - %s\n' "${missing[@]}"
  exit 1
fi

# ── Render templates ─────────────────────────────────────────────────────────
echo "Rendering templates to ${BUILD_DIR}/"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

render() {
  local input="$1"
  local output="${BUILD_DIR}/$(basename "$input")"
  sed \
    -e "s|__NAMESPACE__|${NAMESPACE}|g" \
    -e "s|__BACKEND_IMAGE__|${BACKEND_IMAGE}|g" \
    -e "s|__FRONTEND_IMAGE__|${FRONTEND_IMAGE}|g" \
    -e "s|__NOTEBOOK_IMAGE__|${NOTEBOOK_IMAGE}|g" \
    -e "s|__NOTEBOOK_IMAGE_NAME__|${NOTEBOOK_IMAGE_NAME}|g" \
    -e "s|__NOTEBOOK_IMAGE_TAG__|${NOTEBOOK_IMAGE_TAG}|g" \
    -e "s|__PLATFORM_URL__|${PLATFORM_URL}|g" \
    -e "s|__FRONTEND_SERVICE_TYPE__|${FRONTEND_SERVICE_TYPE}|g" \
    -e "s|__FRONTEND_NODE_PORT__|${FRONTEND_NODE_PORT:-30080}|g" \
    -e "s|__HTTP_PORT__|${FRONTEND_HTTP_PORT}|g" \
    -e "s|__NODEPORT_LINE__|${NODEPORT_LINE}|g" \
    -e "s|__LOADBALANCER_IP__|${LOADBALANCER_IP}|g" \
    -e "s|__KEYCLOAK_URL__|${KEYCLOAK_URL}|g" \
    -e "s|__KEYCLOAK_REALM__|${KEYCLOAK_REALM}|g" \
    -e "s|__KEYCLOAK_PORTAL_CLIENT_ID__|${KEYCLOAK_PORTAL_CLIENT_ID}|g" \
    -e "s|__KEYCLOAK_JUPYTERHUB_CLIENT_ID__|${KEYCLOAK_JUPYTERHUB_CLIENT_ID}|g" \
    -e "s|__KEYCLOAK_JUPYTERHUB_CLIENT_SECRET__|${KEYCLOAK_JUPYTERHUB_CLIENT_SECRET}|g" \
    -e "s|__KEYCLOAK_ISSUER_URI__|${KEYCLOAK_ISSUER_URI}|g" \
    -e "s|__KEYCLOAK_JWK_SET_URI__|${KEYCLOAK_JWK_SET_URI}|g" \
    -e "s|__KEYCLOAK_AUTHORIZE_URL__|${KEYCLOAK_AUTHORIZE_URL}|g" \
    -e "s|__KEYCLOAK_TOKEN_URL__|${KEYCLOAK_TOKEN_URL}|g" \
    -e "s|__KEYCLOAK_USERDATA_URL__|${KEYCLOAK_USERDATA_URL}|g" \
    -e "s|__KEYCLOAK_OAUTH_CALLBACK_URL__|${KEYCLOAK_OAUTH_CALLBACK_URL}|g" \
    -e "s|__KEYCLOAK_ADMIN_PASSWORD__|${KEYCLOAK_ADMIN_PASSWORD}|g" \
    -e "s|__JUPYTERHUB_API_TOKEN__|${JUPYTERHUB_API_TOKEN}|g" \
    -e "s|__S3_ENDPOINT__|${S3_ENDPOINT}|g" \
    -e "s|__S3_ENDPOINT_HOST__|${S3_ENDPOINT_HOST}|g" \
    -e "s|__S3_ENDPOINT_URLENCODED__|${S3_ENDPOINT_URLENCODED}|g" \
    -e "s|__S3_ACCESS_KEY__|${S3_ACCESS_KEY}|g" \
    -e "s|__S3_SECRET_KEY__|${S3_SECRET_KEY}|g" \
    -e "s|__S3_REGION__|${S3_REGION}|g" \
    -e "s|__S3_USE_HTTPS__|${S3_USE_HTTPS}|g" \
    -e "s|__S3_ALLOW_HTTP__|${S3_ALLOW_HTTP}|g" \
    -e "s|__S3_IGNORE_TLS__|${S3_IGNORE_TLS}|g" \
    -e "s|__S3A_SSL_ENABLED__|${S3A_SSL_ENABLED}|g" \
    -e "s|__S3_SECURE__|${S3_SECURE}|g" \
    -e "s|__S3_BUCKET__|${S3_BUCKET}|g" \
    -e "s|__S3_PREFIX__|${S3_PREFIX}|g" \
    -e "s|__S3_PIPELINES_PREFIX__|${S3_PIPELINES_PREFIX}|g" \
    -e "s|__POSTGRES_HOST__|${POSTGRES_HOST}|g" \
    -e "s|__POSTGRES_PORT__|${POSTGRES_PORT}|g" \
    -e "s|__POSTGRES_PASSWORD__|${POSTGRES_PASSWORD}|g" \
    -e "s|__DNS_RESOLVER__|${DNS_RESOLVER}|g" \
    -e "s|__CLUSTER_DOMAIN__|${CLUSTER_DOMAIN:-cluster.local}|g" \
    -e "s|__TLS_LISTEN__|${TLS_LISTEN}|g" \
    -e "s|__TLS_SSL_CERT__|${TLS_SSL_CERT}|g" \
    -e "s|__TLS_SSL_KEY__|${TLS_SSL_KEY}|g" \
    -e "s|__TLS_SSL_PROTOCOLS__|${TLS_SSL_PROTOCOLS}|g" \
    -e "s|__TLS_PORT__|${TLS_PORT}|g" \
    -e "s|__TLS_VOLUME_MOUNT__|${TLS_VOLUME_MOUNT}|g" \
    -e "s|__TLS_VOLUME__|${TLS_VOLUME}|g" \
    -e "s|__TLS_SERVICE_PORT__|${TLS_SERVICE_PORT}|g" \
    -e "s|__TLS_ISSUER__|${TLS_ISSUER:-selfsigned}|g" \
    -e "s|__TLS_ISSUER_KIND__|${TLS_ISSUER_KIND:-ClusterIssuer}|g" \
    -e "s|__TLS_HOSTNAME__|${TLS_HOSTNAME}|g" \
    -e "s|__OBC_STORAGE_CLASS__|${OBC_STORAGE_CLASS:-s3-buckets}|g" \
    "$input" > "$output"
}

# Render all templates
for template in "${TEMPLATES_DIR}"/*.yaml; do
  render "$template"
done

# Copy sample notebook configmaps (only namespace changes)
for f in sample-notebook-configmap.yaml batch-inference-notebook-configmap.yaml; do
  src="${PROJECT_ROOT}/infrastructure/k8s/sample-data/${f}"
  if [[ -f "$src" ]]; then
    sed "s|namespace: ml-platform|namespace: ${NAMESPACE}|g" "$src" > "${BUILD_DIR}/${f}"
  else
    echo "WARNING: ${f} not found at ${src}, skipping"
  fi
done

echo "Templates rendered to ${BUILD_DIR}/"
if [[ "$RENDER_ONLY" == "true" ]]; then
  echo "Render-only mode: skipping deployment."
  ls -la "$BUILD_DIR/"
  exit 0
fi

# ── Helpers ──────────────────────────────────────────────────────────────────
STEP=0
TOTAL_STEPS=12

step() {
  STEP=$((STEP + 1))
  echo ""
  echo "═══════════════════════════════════════════════════════════════"
  echo "  [${STEP}/${TOTAL_STEPS}] $1"
  echo "═══════════════════════════════════════════════════════════════"
}

k() {
  kubectl "$@"
}

h() {
  helm "$@"
}

apply_kserve_crds() {
  local crd_file
  for crd_file in "${KSERVE_CRD_FILES[@]}"; do
    echo "  Applying KServe CRD: ${crd_file}"
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
    echo "  Waiting for CRD: ${crd}"
    k wait --for=condition=Established "crd/${crd}" --timeout=180s
  done
}

reconcile_kserve_release() {
  echo "  Reconciling KServe release ${KSERVE_VERSION}..."
  if ! k get crd certificates.cert-manager.io >/dev/null 2>&1; then
    echo "ERROR: cert-manager CRDs are missing (certificates.cert-manager.io not found)."
    echo "Install cert-manager first, then re-run install.sh."
    return 1
  fi

  echo "  Applying KServe CRDs from ${KSERVE_VERSION}..."
  apply_kserve_crds
  wait_for_kserve_crds

  # Apply controller/resources only after CRDs are established.
  k apply --server-side --force-conflicts -f "${KSERVE_RELEASE_URL}/kserve.yaml"

  # Wait for controller to be ready before applying cluster-resources (which
  # trigger webhook validation that requires the controller to be serving).
  echo "  Waiting for KServe controller to be ready..."
  k -n "$KSERVE_SYSTEM_NAMESPACE" rollout status deployment/kserve-controller-manager --timeout=300s

  k apply --server-side --force-conflicts -f "${KSERVE_RELEASE_URL}/kserve-cluster-resources.yaml"
}

# ── Pre-flight checks ───────────────────────────────────────────────────────
echo ""
echo "Pre-flight checks..."
if ! command -v kubectl &>/dev/null; then
  echo "ERROR: kubectl not found in PATH"
  exit 1
fi
if ! command -v helm &>/dev/null; then
  echo "ERROR: helm not found in PATH"
  exit 1
fi
kubectl cluster-info --request-timeout=5s >/dev/null 2>&1 || {
  echo "ERROR: Cannot connect to Kubernetes cluster. Check your kubeconfig."
  exit 1
}
echo "  kubectl: $(kubectl version --client -o yaml 2>/dev/null | grep gitVersion || echo 'available')"
echo "  helm:    $(helm version --template '{{.Version}}' 2>/dev/null || echo 'available')"
echo "  cluster: $(kubectl cluster-info 2>&1 | grep -o 'https\?://[^ ]*' || echo 'connected')"
echo ""

# ── Helm repository setup ───────────────────────────────────────────────────
echo "Preparing Helm repositories..."
helm repo add jupyterhub https://hub.jupyter.org/helm-chart/ --force-update >/dev/null 2>&1 || true
helm repo add apache-airflow https://airflow.apache.org --force-update >/dev/null 2>&1 || true
helm repo update >/dev/null 2>&1

# ══════════════════════════════════════════════════════════════════════════════
# STEP 1: Namespace
# ══════════════════════════════════════════════════════════════════════════════
step "Creating namespace"
k apply -f "$BUILD_DIR/namespaces.yaml"
echo "  Namespace: ${NAMESPACE}"

# ══════════════════════════════════════════════════════════════════════════════
# STEP 2: PostgreSQL
# ══════════════════════════════════════════════════════════════════════════════
step "PostgreSQL"
if [[ "${DEPLOY_POSTGRESQL:-true}" == "true" ]]; then
  echo "  Deploying PostgreSQL StatefulSet..."
  k apply -f "$BUILD_DIR/postgresql.yaml"

  echo "  Waiting for PostgreSQL pod..."
  k -n "$NAMESPACE" rollout status statefulset/postgresql --timeout=300s

  echo "  Creating application databases..."
  # Wait a moment for postgres to accept connections after readiness
  sleep 5
  POSTGRES_POD="postgresql-0"
  DATABASES="ml_platform mlflow airflow jupyterhub"
  if [[ "${CREATE_KEYCLOAK_DB:-true}" == "true" ]]; then
    DATABASES="$DATABASES keycloak"
  fi
  k -n "$NAMESPACE" exec "$POSTGRES_POD" -- sh -c "
    set -eu
    export PGPASSWORD='${POSTGRES_PASSWORD}'
    for db in ${DATABASES}; do
      if ! psql -U postgres -d postgres -tAc \"SELECT 1 FROM pg_database WHERE datname='\${db}'\" | grep -q 1; then
        psql -U postgres -d postgres -c \"CREATE DATABASE \${db};\"
        echo \"  Created database: \${db}\"
      else
        echo \"  Database exists: \${db}\"
      fi
    done
  "
else
  echo "  Using external PostgreSQL at ${POSTGRES_HOST}:${POSTGRES_PORT}"
  echo "  IMPORTANT: Ensure databases exist: ml_platform, mlflow, airflow, jupyterhub, keycloak"
fi

# ══════════════════════════════════════════════════════════════════════════════
# STEP 3: Keycloak
# ══════════════════════════════════════════════════════════════════════════════
step "Keycloak"
if [[ "${DEPLOY_KEYCLOAK:-false}" == "true" ]]; then
  echo "  Deploying Keycloak..."
  k apply -f "$BUILD_DIR/keycloak-realm-configmap.yaml"
  k apply -f "$BUILD_DIR/keycloak-service.yaml"
  k apply -f "$BUILD_DIR/keycloak-deployment.yaml"

  echo "  Waiting for Keycloak rollout..."
  k -n "$NAMESPACE" rollout status deployment/keycloak --timeout=15m
  echo "  Keycloak deployed"

  # Sync client redirect URIs via admin API (--import-realm skips existing realms)
  echo "  Syncing Keycloak client redirect URIs..."
  KC_ADMIN_TOKEN=""
  for _try in $(seq 1 12); do
    KC_ADMIN_TOKEN="$(kubectl -n "$NAMESPACE" exec deployment/keycloak -- \
      bash -c "curl -sf -X POST http://localhost:8080/realms/master/protocol/openid-connect/token \
        -d client_id=admin-cli -d username=admin -d password=${KEYCLOAK_ADMIN_PASSWORD:-admin} \
        -d grant_type=password" 2>/dev/null \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])' 2>/dev/null)" && break
    sleep 5
  done
  if [[ -n "$KC_ADMIN_TOKEN" ]]; then
    # Update portal client
    PORTAL_UUID="$(kubectl -n "$NAMESPACE" exec deployment/keycloak -- \
      bash -c "curl -sf -H 'Authorization: Bearer $KC_ADMIN_TOKEN' \
        'http://localhost:8080/admin/realms/${KEYCLOAK_REALM}/clients?clientId=${KEYCLOAK_PORTAL_CLIENT_ID}'" 2>/dev/null \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])' 2>/dev/null)" || true
    if [[ -n "$PORTAL_UUID" ]]; then
      kubectl -n "$NAMESPACE" exec deployment/keycloak -- \
        bash -c "curl -sf -X PUT -H 'Authorization: Bearer $KC_ADMIN_TOKEN' -H 'Content-Type: application/json' \
          'http://localhost:8080/admin/realms/${KEYCLOAK_REALM}/clients/${PORTAL_UUID}' \
          -d '{\"clientId\":\"${KEYCLOAK_PORTAL_CLIENT_ID}\",\"redirectUris\":[\"${PLATFORM_URL}/*\",\"http://localhost:4200/*\",\"http://localhost:8080/*\",\"http://localhost:30080/*\"],\"webOrigins\":[\"${PLATFORM_URL}\",\"http://localhost:4200\",\"http://localhost:8080\",\"http://localhost:30080\"]}'" >/dev/null 2>&1
      echo "    Portal client redirect URIs synced"
    fi
    # Update JupyterHub client
    JHUB_UUID="$(kubectl -n "$NAMESPACE" exec deployment/keycloak -- \
      bash -c "curl -sf -H 'Authorization: Bearer $KC_ADMIN_TOKEN' \
        'http://localhost:8080/admin/realms/${KEYCLOAK_REALM}/clients?clientId=${KEYCLOAK_JUPYTERHUB_CLIENT_ID}'" 2>/dev/null \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])' 2>/dev/null)" || true
    if [[ -n "$JHUB_UUID" ]]; then
      kubectl -n "$NAMESPACE" exec deployment/keycloak -- \
        bash -c "curl -sf -X PUT -H 'Authorization: Bearer $KC_ADMIN_TOKEN' -H 'Content-Type: application/json' \
          'http://localhost:8080/admin/realms/${KEYCLOAK_REALM}/clients/${JHUB_UUID}' \
          -d '{\"clientId\":\"${KEYCLOAK_JUPYTERHUB_CLIENT_ID}\",\"redirectUris\":[\"${PLATFORM_URL}/hub/oauth_callback\",\"http://localhost:8181/hub/oauth_callback\",\"http://localhost:30080/hub/oauth_callback\"],\"webOrigins\":[\"${PLATFORM_URL}\",\"http://localhost:8181\",\"http://localhost:30080\"]}'" >/dev/null 2>&1
      echo "    JupyterHub client redirect URIs synced"
    fi
  else
    echo "  WARNING: Could not obtain Keycloak admin token. Client redirect URIs may be stale."
  fi
else
  echo "  Using external Keycloak at ${KEYCLOAK_URL}"
fi

# ══════════════════════════════════════════════════════════════════════════════
# STEP 4: ObjectBucketClaim & S3 credentials
# ══════════════════════════════════════════════════════════════════════════════
step "S3 credentials and ObjectBucketClaim"
k apply -f "$BUILD_DIR/s3-credentials-secret.yaml"
k apply -f "$BUILD_DIR/sample-data-readonly-secret.yaml"
echo "  S3 credential secrets created"

if [[ -n "${S3_EXTERNAL_ENDPOINT:-}" ]]; then
  # Extract hostname from URL (strip protocol and port)
  S3_EXTERNAL_HOST="${S3_EXTERNAL_ENDPOINT#http://}"
  S3_EXTERNAL_HOST="${S3_EXTERNAL_HOST#https://}"
  S3_EXTERNAL_HOST="${S3_EXTERNAL_HOST%%:*}"
  S3_EXTERNAL_HOST="${S3_EXTERNAL_HOST%%/*}"
  echo "  Creating ExternalName service: minio → ${S3_EXTERNAL_HOST}"
  cat <<EOFSVC | k apply -f -
apiVersion: v1
kind: Service
metadata:
  name: minio
  namespace: ${NAMESPACE}
spec:
  type: ExternalName
  externalName: ${S3_EXTERNAL_HOST}
  ports:
    - port: 9000
      targetPort: 9000
EOFSVC
fi

if [[ "${DEPLOY_OBC:-false}" == "true" ]]; then
  echo "  Creating ObjectBucketClaim..."
  k apply -f "$BUILD_DIR/obc.yaml"
  echo "  OBC created for: ${S3_BUCKET}"
else
  echo "  OBC disabled — ensure bucket exists: ${S3_BUCKET}"
fi

# ══════════════════════════════════════════════════════════════════════════════
# STEP 4: MLflow
# ══════════════════════════════════════════════════════════════════════════════
step "MLflow"
h upgrade --install mlflow "$PROJECT_ROOT/infrastructure/helm/mlflow" \
  -n "$NAMESPACE" \
  -f "$BUILD_DIR/mlflow-values.yaml" \
  --wait --timeout 15m
echo "  MLflow deployed"

# ══════════════════════════════════════════════════════════════════════════════
# STEP 5: Sample data
# ══════════════════════════════════════════════════════════════════════════════
step "Sample data provisioning"

echo "  Ensuring S3 bucket exists: ${S3_BUCKET}..."
k -n "$NAMESPACE" delete pod ensure-bucket --ignore-not-found 2>/dev/null || true
k -n "$NAMESPACE" run ensure-bucket --rm -i --restart=Never \
  --image="${NOTEBOOK_IMAGE}" \
  --env="AWS_ENDPOINT_URL=${S3_ENDPOINT}" \
  --env="AWS_ACCESS_KEY_ID=${S3_ACCESS_KEY}" \
  --env="AWS_SECRET_ACCESS_KEY=${S3_SECRET_KEY}" \
  --env="AWS_ALLOW_HTTP=${S3_ALLOW_HTTP}" \
  --env="S3_BUCKET=${S3_BUCKET}" \
  --command -- python -c "
import boto3, os
s3 = boto3.client('s3', endpoint_url=os.environ['AWS_ENDPOINT_URL'])
bucket = os.environ['S3_BUCKET']
try:
    s3.head_bucket(Bucket=bucket)
    print(f'Bucket {bucket} exists')
except Exception:
    s3.create_bucket(Bucket=bucket)
    print(f'Created bucket {bucket}')
" 2>/dev/null || echo "  WARNING: Could not verify bucket — ensure ${S3_BUCKET} exists"

echo "  Creating sample data ConfigMap from bundled Parquet..."
DATA_FILE="${SCRIPT_DIR}/data/california-housing.parquet"
if [[ ! -f "$DATA_FILE" ]]; then
  echo "ERROR: Sample data file not found: $DATA_FILE"
  exit 1
fi
k -n "$NAMESPACE" create configmap california-housing-data \
  --from-file=california-housing.parquet="$DATA_FILE" \
  --dry-run=client -o yaml | k apply --server-side -f -

k apply -f "$BUILD_DIR/provision-script-configmap.yaml"
k apply -f "$BUILD_DIR/sample-notebook-configmap.yaml"
k apply -f "$BUILD_DIR/batch-inference-notebook-configmap.yaml"
k apply -f "$BUILD_DIR/sample-visualization-configmap.yaml"

if [[ "${PROVISION_SAMPLE_DATA:-true}" == "true" ]]; then
  echo "  Running provision job..."
  k -n "$NAMESPACE" delete job provision-sample-data --ignore-not-found
  k apply -f "$BUILD_DIR/provision-job.yaml"
  k -n "$NAMESPACE" wait --for=condition=complete job/provision-sample-data --timeout=20m
  echo "  Sample data provisioned"
else
  echo "  Skipping sample data provisioning (PROVISION_SAMPLE_DATA=false)"
  echo "  Run later: kubectl apply -f ${BUILD_DIR}/provision-job.yaml"
fi

# ══════════════════════════════════════════════════════════════════════════════
# STEP 6: JupyterHub
# ══════════════════════════════════════════════════════════════════════════════
step "JupyterHub"
k apply -f "$BUILD_DIR/jupyterlab-config-configmap.yaml"
h upgrade --install jupyterhub jupyterhub/jupyterhub \
  -n "$NAMESPACE" \
  --version 4.1.0 \
  -f "$BUILD_DIR/jupyterhub-values.yaml" \
  --wait --timeout 20m
echo "  JupyterHub deployed"

# ══════════════════════════════════════════════════════════════════════════════
# STEP 7: Airflow
# ══════════════════════════════════════════════════════════════════════════════
step "Airflow"
k apply -f "$BUILD_DIR/airflow-spark-rbac.yaml"
k apply -f "$BUILD_DIR/airflow-dag-configmap.yaml"

echo "  Running Airflow database migration..."
AIRFLOW_DB_CONN="postgresql+psycopg2://postgres:${POSTGRES_PASSWORD}@${POSTGRES_HOST}:${POSTGRES_PORT}/airflow"
k -n "$NAMESPACE" delete pod airflow-db-migrate --ignore-not-found 2>/dev/null || true
k -n "$NAMESPACE" run airflow-db-migrate --rm -i --restart=Never \
  --image="apache/airflow:2.10.3-python3.11" \
  --env="AIRFLOW__DATABASE__SQL_ALCHEMY_CONN=${AIRFLOW_DB_CONN}" \
  --command -- airflow db migrate 2>/dev/null
echo "  Database migration complete"

h upgrade --install airflow apache-airflow/airflow \
  -n "$NAMESPACE" \
  -f "$BUILD_DIR/airflow-values.yaml" \
  --wait --timeout 20m
echo "  Airflow deployed"

# ══════════════════════════════════════════════════════════════════════════════
# STEP 8: KServe
# ══════════════════════════════════════════════════════════════════════════════
step "KServe setup"
reconcile_kserve_release
echo "  Waiting for KServe controller rollout..."
k -n "$KSERVE_SYSTEM_NAMESPACE" rollout status deployment/kserve-controller-manager --timeout=300s 2>/dev/null || true

if k -n "$KSERVE_SYSTEM_NAMESPACE" get configmap inferenceservice-config >/dev/null 2>&1; then
  echo "  Configuring KServe default deployment mode: ${KSERVE_DEFAULT_DEPLOYMENT_MODE}"
  k patch configmap/inferenceservice-config \
    -n "$KSERVE_SYSTEM_NAMESPACE" \
    --type=merge \
    -p "{\"data\":{\"deploy\":\"{\\\"defaultDeploymentMode\\\":\\\"${KSERVE_DEFAULT_DEPLOYMENT_MODE}\\\"}\"}}"
  echo "  Restarting KServe controller to pick up deployment mode config..."
  k -n "$KSERVE_SYSTEM_NAMESPACE" rollout restart deployment/kserve-controller-manager
  k -n "$KSERVE_SYSTEM_NAMESPACE" rollout status deployment/kserve-controller-manager --timeout=120s 2>/dev/null || true
fi

k apply -f "$BUILD_DIR/kserve-s3-secret.yaml"
k apply -f "$BUILD_DIR/kserve-service-account.yaml"
# Keep existing endpoints aligned with the cluster default mode name.
k -n "$NAMESPACE" annotate inferenceservices.serving.kserve.io \
  --all serving.kserve.io/deploymentMode="${KSERVE_DEFAULT_DEPLOYMENT_MODE}" \
  --overwrite >/dev/null 2>&1 || true
echo "  KServe configured in ${NAMESPACE}"

# ══════════════════════════════════════════════════════════════════════════════
# STEP 9: Container Registry (Custom Notebook Images)
# ══════════════════════════════════════════════════════════════════════════════
step "Container Registry"

REGISTRY_TYPE="${REGISTRY_TYPE:-builtin}"
REGISTRY_ENDPOINT="${REGISTRY_ENDPOINT:-registry.${NAMESPACE}.svc:5000}"
REGISTRY_USERNAME="${REGISTRY_USERNAME:-}"
REGISTRY_PASSWORD="${REGISTRY_PASSWORD:-}"

# Always deploy Kaniko RBAC (needed for both registry modes)
sed "s|namespace: ml-platform|namespace: ${NAMESPACE}|g" \
  "${PROJECT_ROOT}/infrastructure/k8s/platform/base/kaniko-rbac.yaml" | k apply -f -

if [[ "$REGISTRY_TYPE" == "external" ]]; then
  echo "  Using external container registry: ${REGISTRY_ENDPOINT}"
  # Create registry-credentials Secret from external registry credentials
  if [[ -n "$REGISTRY_USERNAME" ]] && [[ -n "$REGISTRY_PASSWORD" ]]; then
    AUTH=$(echo -n "${REGISTRY_USERNAME}:${REGISTRY_PASSWORD}" | base64)
  else
    AUTH=$(echo -n ':' | base64)
  fi
  DOCKER_CONFIG_JSON=$(echo -n "{\"auths\":{\"${REGISTRY_ENDPOINT}\":{\"auth\":\"${AUTH}\"}}}" | base64)
  cat <<EOFREG | k apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: registry-credentials
  namespace: ${NAMESPACE}
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: ${DOCKER_CONFIG_JSON}
EOFREG
  echo "  External registry credentials configured"
else
  echo "  Deploying built-in container registry..."
  k apply -f "$BUILD_DIR/registry-deployment.yaml"
  k apply -f "$BUILD_DIR/registry-service.yaml"

  # Create registry-credentials Secret for built-in registry (no auth)
  DOCKER_CONFIG_JSON=$(echo -n "{\"auths\":{\"${REGISTRY_ENDPOINT}\":{\"auth\":\"$(echo -n ':' | base64)\"}}}" | base64)
  cat <<EOFREG | k apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: registry-credentials
  namespace: ${NAMESPACE}
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: ${DOCKER_CONFIG_JSON}
EOFREG

  echo "  Waiting for registry rollout..."
  k -n "$NAMESPACE" rollout status deployment/registry --timeout=300s
  echo "  Built-in registry deployed"
fi

# ══════════════════════════════════════════════════════════════════════════════
# STEP 10: Backend
# ══════════════════════════════════════════════════════════════════════════════
step "Backend"
k apply -f "$BUILD_DIR/backend-serviceaccount.yaml"
k apply -f "$BUILD_DIR/backend-rbac.yaml"
k apply -f "$BUILD_DIR/backend-deployment.yaml"
k apply -f "$BUILD_DIR/backend-service.yaml"

echo "  Waiting for backend rollout..."
k -n "$NAMESPACE" rollout status deployment/backend --timeout=20m

# ══════════════════════════════════════════════════════════════════════════════
# STEP 11: Frontend
# ══════════════════════════════════════════════════════════════════════════════
step "Frontend"

if [[ "${TLS_ENABLED:-false}" == "true" ]]; then
  # Create self-signed ClusterIssuer if needed
  if [[ "${TLS_ISSUER:-selfsigned}" == "selfsigned" ]]; then
    if ! k get clusterissuer selfsigned >/dev/null 2>&1; then
      echo "  Creating self-signed ClusterIssuer..."
      k apply -f "$BUILD_DIR/frontend-selfsigned-issuer.yaml"
    else
      echo "  Self-signed ClusterIssuer already exists"
    fi
  fi

  # Create cert-manager Certificate (handles IP vs DNS hostname)
  echo "  Creating TLS certificate (issuer: ${TLS_ISSUER:-selfsigned})..."
  if [[ "$TLS_HOSTNAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    # IP address — use ipAddresses field (generated inline)
    cat <<EOFCERT | k apply -f -
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: frontend-tls
  namespace: ${NAMESPACE}
spec:
  secretName: frontend-tls
  ipAddresses:
    - ${TLS_HOSTNAME}
  issuerRef:
    name: ${TLS_ISSUER:-selfsigned}
    kind: ${TLS_ISSUER_KIND:-ClusterIssuer}
EOFCERT
  else
    # DNS name — use rendered template
    k apply -f "$BUILD_DIR/frontend-certificate.yaml"
  fi

  echo "  Waiting for TLS certificate..."
  k -n "$NAMESPACE" wait --for=condition=Ready certificate/frontend-tls --timeout=120s
  echo "  TLS certificate ready"
fi

k apply -f "$BUILD_DIR/frontend-nginx-configmap.yaml"
k apply -f "$BUILD_DIR/frontend-deployment.yaml"
k apply -f "$BUILD_DIR/frontend-service.yaml"

echo "  Waiting for frontend rollout..."
k -n "$NAMESPACE" rollout status deployment/frontend --timeout=20m

# ══════════════════════════════════════════════════════════════════════════════
# Done
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  ML Platform installation complete!"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "  Platform URL:  ${PLATFORM_URL}"
if [[ "${TLS_ENABLED:-false}" == "true" ]]; then
  echo "  TLS:           enabled (issuer: ${TLS_ISSUER:-selfsigned})"
fi
echo "  Namespace:     ${NAMESPACE}"
echo ""
echo "  Services:"
k -n "$NAMESPACE" get svc frontend backend mlflow 2>/dev/null || true
echo ""
echo "  Deployments:"
k -n "$NAMESPACE" get deploy backend frontend 2>/dev/null || true
echo ""
