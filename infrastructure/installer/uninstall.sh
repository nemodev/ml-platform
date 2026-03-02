#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# ML Platform Uninstaller
#
# Removes all platform components deployed by install.sh.
# Usage: ./uninstall.sh [config-file]
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${1:-${SCRIPT_DIR}/config.env}"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "ERROR: Config file not found: $CONFIG_FILE"
  exit 1
fi
# shellcheck disable=SC1090
source "$CONFIG_FILE"

KSERVE_VERSION="${KSERVE_VERSION:-v0.16.0}"
KSERVE_RELEASE_URL="https://github.com/kserve/kserve/releases/download/${KSERVE_VERSION}"
KSERVE_SYSTEM_NAMESPACE="${KSERVE_SYSTEM_NAMESPACE:-kserve}"
UNINSTALL_KSERVE_CLUSTER_RESOURCES="${UNINSTALL_KSERVE_CLUSTER_RESOURCES:-true}"
UNINSTALL_DELETE_NAMESPACE="${UNINSTALL_DELETE_NAMESPACE:-false}"
UNINSTALL_PURGE_PVC="${UNINSTALL_PURGE_PVC:-true}"
UNINSTALL_PURGE_PV="${UNINSTALL_PURGE_PV:-true}"
UNINSTALL_PURGE_S3_DATA="${UNINSTALL_PURGE_S3_DATA:-false}"
UNINSTALL_S3_PREFIX="${UNINSTALL_S3_PREFIX:-${S3_PREFIX:-ml-platform}}"

namespace_exists() {
  kubectl get namespace "$NAMESPACE" >/dev/null 2>&1
}

capture_postgres_pvs() {
  kubectl get pv \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.claimRef.namespace}{"\t"}{.spec.claimRef.name}{"\n"}{end}' 2>/dev/null \
    | awk -v ns="$NAMESPACE" '$2 == ns && $3 ~ /^data-postgresql-/ {print $1}'
}

purge_s3_data() {
  if [[ "${UNINSTALL_PURGE_S3_DATA}" != "true" ]]; then
    return 0
  fi
  if [[ -z "${NOTEBOOK_IMAGE:-}" ]]; then
    echo "  WARNING: UNINSTALL_PURGE_S3_DATA=true but NOTEBOOK_IMAGE is not set. Skipping S3 purge."
    return 0
  fi
  if ! namespace_exists; then
    echo "  WARNING: Namespace '${NAMESPACE}' not found. Skipping S3 purge."
    return 0
  fi

  local purge_script
  local purge_pod
  purge_pod="s3-purge-$(date +%s)"
  purge_script="$(cat <<'PY'
import os
import sys
import boto3

bucket = os.environ["S3_BUCKET"]
prefix = os.environ.get("S3_PURGE_PREFIX", "")

client = boto3.client(
    "s3",
    endpoint_url=os.environ["AWS_ENDPOINT_URL"],
    aws_access_key_id=os.environ["AWS_ACCESS_KEY_ID"],
    aws_secret_access_key=os.environ["AWS_SECRET_ACCESS_KEY"],
    region_name=os.environ.get("AWS_REGION", "us-east-1"),
)

deleted = 0
token = None
try:
    while True:
      kwargs = {"Bucket": bucket, "Prefix": prefix, "MaxKeys": 1000}
      if token:
          kwargs["ContinuationToken"] = token
      resp = client.list_objects_v2(**kwargs)
      objs = [{"Key": o["Key"]} for o in resp.get("Contents", [])]
      if objs:
          client.delete_objects(Bucket=bucket, Delete={"Objects": objs, "Quiet": True})
          deleted += len(objs)
      if not resp.get("IsTruncated"):
          break
      token = resp.get("NextContinuationToken")
except Exception as exc:
    print(f"S3 purge failed: {exc}", file=sys.stderr)
    sys.exit(1)

scope = f"s3://{bucket}/{prefix}" if prefix else f"s3://{bucket}"
print(f"Deleted {deleted} object(s) from {scope}")
PY
)"

  echo "[0/8] Purging S3 data..."
  kubectl -n "$NAMESPACE" run "$purge_pod" --rm -i --restart=Never \
    --image="${NOTEBOOK_IMAGE}" \
    --env="AWS_ENDPOINT_URL=${S3_INTERNAL_ENDPOINT}" \
    --env="AWS_ACCESS_KEY_ID=${S3_ACCESS_KEY}" \
    --env="AWS_SECRET_ACCESS_KEY=${S3_SECRET_KEY}" \
    --env="AWS_REGION=${S3_REGION:-us-east-1}" \
    --env="S3_BUCKET=${S3_BUCKET}" \
    --env="S3_PURGE_PREFIX=${UNINSTALL_S3_PREFIX}" \
    --command -- python -c "$purge_script"
}

capture_all_pvs() {
  kubectl get pv \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.claimRef.namespace}{"\t"}{.spec.claimRef.name}{"\n"}{end}' 2>/dev/null \
    | awk -v ns="$NAMESPACE" '$2 == ns {print $1}'
}

echo "This will remove the ML Platform from namespace '${NAMESPACE}'."
echo "  - Delete namespace: ${UNINSTALL_DELETE_NAMESPACE}"
echo "  - Purge all PVCs: ${UNINSTALL_PURGE_PVC}"
echo "  - Delete retained PVs: ${UNINSTALL_PURGE_PV}"
echo "  - Purge S3 data: ${UNINSTALL_PURGE_S3_DATA} (prefix='${UNINSTALL_S3_PREFIX}')"
echo "  - Remove cluster-scoped KServe resources: ${UNINSTALL_KSERVE_CLUSTER_RESOURCES}"
echo "Press Ctrl+C within 5 seconds to abort..."
sleep 5

POSTGRES_BOUND_PVS=""
ALL_BOUND_PVS=""
if [[ "${UNINSTALL_PURGE_PV}" == "true" ]]; then
  POSTGRES_BOUND_PVS="$(capture_postgres_pvs || true)"
  ALL_BOUND_PVS="$(capture_all_pvs || true)"
fi

purge_s3_data

TOTAL=11
STEP=0

if namespace_exists; then
  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing backend & frontend..."
  kubectl -n "$NAMESPACE" delete deploy backend frontend --ignore-not-found
  kubectl -n "$NAMESPACE" delete svc backend frontend --ignore-not-found
  kubectl -n "$NAMESPACE" delete configmap frontend-nginx-template --ignore-not-found
  kubectl -n "$NAMESPACE" delete certificate frontend-tls --ignore-not-found 2>/dev/null || true
  kubectl -n "$NAMESPACE" delete secret frontend-tls --ignore-not-found
  # Note: selfsigned ClusterIssuer is cluster-scoped and may be shared; not deleted here.
  kubectl -n "$NAMESPACE" delete sa backend --ignore-not-found
  kubectl -n "$NAMESPACE" delete role backend-kserve-manager --ignore-not-found
  kubectl -n "$NAMESPACE" delete rolebinding backend-kserve-manager --ignore-not-found

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing Airflow..."
  helm uninstall airflow -n "$NAMESPACE" 2>/dev/null || true
  kubectl -n "$NAMESPACE" delete configmap airflow-notebook-runner-dag --ignore-not-found
  kubectl -n "$NAMESPACE" delete sa airflow-spark-sa --ignore-not-found
  kubectl -n "$NAMESPACE" delete role airflow-spark-role --ignore-not-found
  kubectl -n "$NAMESPACE" delete rolebinding airflow-spark-rolebinding --ignore-not-found
  kubectl -n "$NAMESPACE" delete secret airflow-broker-url airflow-fernet-key --ignore-not-found
  # Clean up orphaned Airflow PVCs (e.g. triggerer logs)
  AIRFLOW_PVCS="$(kubectl -n "$NAMESPACE" get pvc -o name 2>/dev/null \
    | sed -n 's|^persistentvolumeclaim/||p' \
    | grep -E '^(logs-airflow-|redis-db-airflow-)' || true)"
  if [[ -n "$AIRFLOW_PVCS" ]]; then
    echo "  Deleting orphaned Airflow PVCs..."
    echo "$AIRFLOW_PVCS" | xargs -r kubectl -n "$NAMESPACE" delete pvc --ignore-not-found
  fi

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing JupyterHub..."
  helm uninstall jupyterhub -n "$NAMESPACE" 2>/dev/null || true
  # Delete JupyterHub configmaps left behind by helm uninstall
  kubectl -n "$NAMESPACE" delete configmap jupyterlab-config sample-visualization --ignore-not-found
  # Delete lingering user notebook pods left behind by helm uninstall
  JHUB_PODS="$(kubectl -n "$NAMESPACE" get pods -o name 2>/dev/null \
    | grep -E '^pod/jupyter-' || true)"
  if [[ -n "$JHUB_PODS" ]]; then
    echo "  Deleting JupyterHub user pods..."
    echo "$JHUB_PODS" | xargs -r kubectl -n "$NAMESPACE" delete --ignore-not-found --grace-period=0 --force 2>/dev/null || true
  fi
  # Delete JupyterHub user PVCs (claim-*)
  JHUB_PVCS="$(kubectl -n "$NAMESPACE" get pvc -o name 2>/dev/null \
    | sed -n 's|^persistentvolumeclaim/||p' \
    | grep '^claim-' || true)"
  if [[ -n "$JHUB_PVCS" ]]; then
    echo "  Deleting JupyterHub user PVCs..."
    echo "$JHUB_PVCS" | xargs -r kubectl -n "$NAMESPACE" delete pvc --ignore-not-found
  fi
  # Delete orphaned Kaniko build jobs and pods
  KANIKO_JOBS="$(kubectl -n "$NAMESPACE" get jobs -o name 2>/dev/null \
    | grep -E '^job.batch/kaniko-build-' || true)"
  if [[ -n "$KANIKO_JOBS" ]]; then
    echo "  Deleting Kaniko build jobs..."
    echo "$KANIKO_JOBS" | xargs -r kubectl -n "$NAMESPACE" delete --ignore-not-found
  fi
  KANIKO_PODS="$(kubectl -n "$NAMESPACE" get pods -o name 2>/dev/null \
    | grep -E '^pod/kaniko-build-' || true)"
  if [[ -n "$KANIKO_PODS" ]]; then
    echo "  Deleting Kaniko build pods..."
    echo "$KANIKO_PODS" | xargs -r kubectl -n "$NAMESPACE" delete --ignore-not-found
  fi

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing sample data..."
  kubectl -n "$NAMESPACE" delete job provision-sample-data --ignore-not-found
  kubectl -n "$NAMESPACE" delete configmap provision-sample-data-script sample-notebook \
    batch-inference-notebook california-housing-data --ignore-not-found

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing MLflow..."
  helm uninstall mlflow -n "$NAMESPACE" 2>/dev/null || true

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing KServe resources in app namespace..."
  # Remove webhooks early so the API server stops routing to the (possibly
  # unhealthy) KServe controller — this unblocks finalizer-stuck deletions.
  kubectl delete mutatingwebhookconfiguration inferenceservice.serving.kserve.io --ignore-not-found 2>/dev/null || true
  for vwc in clusterservingruntime inferencegraph inferenceservice servingruntime trainedmodel; do
    kubectl delete validatingwebhookconfiguration "${vwc}.serving.kserve.io" --ignore-not-found 2>/dev/null || true
  done
  # Attempt graceful deletion first (timeout 30s), then force-patch finalizers
  ISVC_NAMES="$(kubectl -n "$NAMESPACE" get inferenceservices.serving.kserve.io -o name 2>/dev/null || true)"
  if [[ -n "$ISVC_NAMES" ]]; then
    echo "  Deleting InferenceServices (30s timeout)..."
    if ! timeout 30 kubectl -n "$NAMESPACE" delete inferenceservices.serving.kserve.io --all --ignore-not-found 2>/dev/null; then
      echo "  Graceful delete timed out — clearing finalizers to force removal..."
      while IFS= read -r isvc; do
        [[ -n "$isvc" ]] || continue
        kubectl -n "$NAMESPACE" patch "$isvc" --type=merge -p '{"metadata":{"finalizers":null}}' 2>/dev/null || true
      done <<< "$ISVC_NAMES"
      # Retry delete after finalizers are cleared
      kubectl -n "$NAMESPACE" delete inferenceservices.serving.kserve.io --all --ignore-not-found 2>/dev/null || true
    fi
  fi
  kubectl -n "$NAMESPACE" delete sa kserve-s3-sa --ignore-not-found
  kubectl -n "$NAMESPACE" delete secret kserve-s3-secret --ignore-not-found

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing Container Registry & Kaniko..."
  kubectl -n "$NAMESPACE" delete deploy registry --ignore-not-found
  kubectl -n "$NAMESPACE" delete svc registry --ignore-not-found
  kubectl -n "$NAMESPACE" delete secret registry-credentials --ignore-not-found
  # Kaniko RBAC
  kubectl -n "$NAMESPACE" delete sa kaniko-builder --ignore-not-found
  kubectl -n "$NAMESPACE" delete role kaniko-builder --ignore-not-found
  kubectl -n "$NAMESPACE" delete rolebinding kaniko-builder --ignore-not-found

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing Keycloak..."
  if [[ "${DEPLOY_KEYCLOAK:-false}" == "true" ]]; then
    kubectl -n "$NAMESPACE" delete deploy keycloak --ignore-not-found
    kubectl -n "$NAMESPACE" delete svc keycloak --ignore-not-found
    kubectl -n "$NAMESPACE" delete configmap keycloak-realm-config --ignore-not-found
  else
    echo "  External Keycloak in use; skipping."
  fi

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing S3 credentials & services..."
  kubectl -n "$NAMESPACE" delete secret sample-data-readonly-credentials s3-credentials --ignore-not-found
  # Remove ExternalName service for S3 (minio)
  kubectl -n "$NAMESPACE" delete svc minio --ignore-not-found
  if [[ "${DEPLOY_OBC:-false}" == "true" ]]; then
    echo "  Removing ObjectBucketClaim..."
    kubectl -n "$NAMESPACE" delete objectbucketclaim "${S3_BUCKET}" \
      --ignore-not-found 2>/dev/null || true
  fi

  STEP=$((STEP + 1))
  echo "[${STEP}/${TOTAL}] Removing PostgreSQL..."
  if [[ "${DEPLOY_POSTGRESQL:-true}" == "true" ]]; then
    kubectl -n "$NAMESPACE" delete statefulset postgresql --ignore-not-found
    kubectl -n "$NAMESPACE" delete svc postgresql --ignore-not-found
    kubectl -n "$NAMESPACE" delete secret postgresql-credentials --ignore-not-found
    if [[ "${UNINSTALL_PURGE_PVC}" == "true" ]]; then
      POSTGRES_PVCS=()
      while IFS= read -r pvc; do
        [[ -n "$pvc" ]] && POSTGRES_PVCS+=("$pvc")
      done < <(
        kubectl -n "$NAMESPACE" get pvc -o name 2>/dev/null \
          | sed -n 's|^persistentvolumeclaim/||p' \
          | grep '^data-postgresql-' || true
      )
      if [[ ${#POSTGRES_PVCS[@]} -gt 0 ]]; then
        kubectl -n "$NAMESPACE" delete pvc "${POSTGRES_PVCS[@]}" --ignore-not-found
      fi
    else
      echo "  Skipping PostgreSQL PVC deletion (UNINSTALL_PURGE_PVC=false)."
    fi
  else
    echo "  External PostgreSQL in use; no in-cluster PostgreSQL resources removed."
  fi

  STEP=$((STEP + 1))
  if [[ "${UNINSTALL_DELETE_NAMESPACE}" == "true" ]]; then
    echo "[${STEP}/${TOTAL}] Deleting namespace '${NAMESPACE}'..."
    kubectl delete namespace "$NAMESPACE" --ignore-not-found
    kubectl wait --for=delete namespace/"$NAMESPACE" --timeout=300s 2>/dev/null || true
  else
    echo "[${STEP}/${TOTAL}] Skipping namespace deletion (UNINSTALL_DELETE_NAMESPACE=false)."
  fi
else
  echo "Namespace '${NAMESPACE}' does not exist. Skipping namespaced resource cleanup."
fi

if [[ "${UNINSTALL_PURGE_PV}" == "true" ]] && [[ -n "${ALL_BOUND_PVS}" ]]; then
  echo "Removing retained PVs bound to namespace '${NAMESPACE}'..."
  while IFS= read -r pv; do
    [[ -n "$pv" ]] || continue
    kubectl delete pv "$pv" --ignore-not-found
  done <<< "${ALL_BOUND_PVS}"
fi

if [[ "${UNINSTALL_KSERVE_CLUSTER_RESOURCES}" == "true" ]]; then
  echo "Removing cluster-scoped KServe resources..."
  # Remove webhook configurations (may already be gone from namespace cleanup step)
  kubectl delete mutatingwebhookconfiguration inferenceservice.serving.kserve.io --ignore-not-found 2>/dev/null || true
  for vwc in clusterservingruntime inferencegraph inferenceservice servingruntime trainedmodel; do
    kubectl delete validatingwebhookconfiguration "${vwc}.serving.kserve.io" --ignore-not-found 2>/dev/null || true
  done
  kubectl delete --ignore-not-found -f "${KSERVE_RELEASE_URL}/kserve-cluster-resources.yaml" 2>/dev/null || true
  kubectl delete --ignore-not-found -f "${KSERVE_RELEASE_URL}/kserve.yaml" 2>/dev/null || true

  if [[ "$KSERVE_SYSTEM_NAMESPACE" != "$NAMESPACE" ]]; then
    kubectl delete namespace "$KSERVE_SYSTEM_NAMESPACE" --ignore-not-found 2>/dev/null || true
  elif [[ "${UNINSTALL_DELETE_NAMESPACE}" != "true" ]]; then
    echo "Skipping kserve system namespace deletion because it matches NAMESPACE and namespace deletion is disabled."
  fi

  KSERVE_CRDS="$(kubectl get crd -o name | grep 'serving.kserve.io' || true)"
  if [[ -n "${KSERVE_CRDS}" ]]; then
    # shellcheck disable=SC2086
    kubectl delete --ignore-not-found ${KSERVE_CRDS} 2>/dev/null || true
  fi
else
  echo "Skipping cluster-scoped KServe uninstall (UNINSTALL_KSERVE_CLUSTER_RESOURCES=false)."
fi

echo ""
echo "ML Platform uninstall complete for namespace '${NAMESPACE}'."
