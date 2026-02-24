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

echo "This will remove the ML Platform from namespace '${NAMESPACE}'."
echo "  - Delete namespace: ${UNINSTALL_DELETE_NAMESPACE}"
echo "  - Delete PostgreSQL PVC(s): ${UNINSTALL_PURGE_PVC}"
echo "  - Delete retained PV(s): ${UNINSTALL_PURGE_PV}"
echo "  - Purge S3 data: ${UNINSTALL_PURGE_S3_DATA} (prefix='${UNINSTALL_S3_PREFIX}')"
echo "  - Remove cluster-scoped KServe resources: ${UNINSTALL_KSERVE_CLUSTER_RESOURCES}"
echo "Press Ctrl+C within 5 seconds to abort..."
sleep 5

POSTGRES_BOUND_PVS=""
if [[ "${UNINSTALL_PURGE_PV}" == "true" ]]; then
  POSTGRES_BOUND_PVS="$(capture_postgres_pvs || true)"
fi

purge_s3_data

if namespace_exists; then
  echo "[1/8] Removing backend & frontend..."
  kubectl -n "$NAMESPACE" delete deploy backend frontend --ignore-not-found
  kubectl -n "$NAMESPACE" delete svc backend frontend --ignore-not-found
  kubectl -n "$NAMESPACE" delete configmap frontend-nginx-template --ignore-not-found
  kubectl -n "$NAMESPACE" delete certificate frontend-tls --ignore-not-found 2>/dev/null || true
  kubectl -n "$NAMESPACE" delete secret frontend-tls --ignore-not-found
  # Note: selfsigned ClusterIssuer is cluster-scoped and may be shared; not deleted here.
  kubectl -n "$NAMESPACE" delete sa backend --ignore-not-found
  kubectl -n "$NAMESPACE" delete role backend-kserve-manager --ignore-not-found
  kubectl -n "$NAMESPACE" delete rolebinding backend-kserve-manager --ignore-not-found

  echo "[2/8] Removing Airflow..."
  helm uninstall airflow -n "$NAMESPACE" 2>/dev/null || true
  kubectl -n "$NAMESPACE" delete configmap airflow-notebook-runner-dag --ignore-not-found
  kubectl -n "$NAMESPACE" delete sa airflow-spark-sa --ignore-not-found
  kubectl -n "$NAMESPACE" delete role airflow-spark-role --ignore-not-found
  kubectl -n "$NAMESPACE" delete rolebinding airflow-spark-rolebinding --ignore-not-found

  echo "[3/8] Removing JupyterHub..."
  helm uninstall jupyterhub -n "$NAMESPACE" 2>/dev/null || true

  echo "[4/8] Removing sample data..."
  kubectl -n "$NAMESPACE" delete job provision-sample-data --ignore-not-found
  kubectl -n "$NAMESPACE" delete configmap provision-sample-data-script sample-notebook batch-inference-notebook --ignore-not-found
  kubectl -n "$NAMESPACE" delete secret sample-data-readonly-credentials s3-credentials --ignore-not-found

  echo "[5/8] Removing MLflow..."
  helm uninstall mlflow -n "$NAMESPACE" 2>/dev/null || true

  echo "[6/8] Removing KServe resources in app namespace..."
  kubectl -n "$NAMESPACE" delete inferenceservices.serving.kserve.io --all --ignore-not-found 2>/dev/null || true
  kubectl -n "$NAMESPACE" delete sa kserve-s3-sa --ignore-not-found
  kubectl -n "$NAMESPACE" delete secret kserve-s3-secret --ignore-not-found

  echo "[7/8] Removing PostgreSQL..."
  if [[ "${DEPLOY_POSTGRESQL:-true}" == "true" ]]; then
    kubectl -n "$NAMESPACE" delete statefulset postgresql --ignore-not-found
    kubectl -n "$NAMESPACE" delete svc postgresql --ignore-not-found
    kubectl -n "$NAMESPACE" delete secret postgresql-credentials --ignore-not-found
    if [[ "${UNINSTALL_PURGE_PVC}" == "true" ]]; then
      mapfile -t POSTGRES_PVCS < <(
        kubectl -n "$NAMESPACE" get pvc -o name 2>/dev/null \
          | sed -n 's|^persistentvolumeclaim/||p' \
          | grep '^data-postgresql-' || true
      )
      if [[ ${#POSTGRES_PVCS[@]} -gt 0 ]]; then
        kubectl -n "$NAMESPACE" delete pvc "${POSTGRES_PVCS[@]}" --ignore-not-found
      fi
    else
      echo "  Skipping PVC deletion (UNINSTALL_PURGE_PVC=false)."
    fi
  else
    echo "  External PostgreSQL in use; no in-cluster PostgreSQL resources removed."
  fi

  if [[ "${DEPLOY_OBC:-false}" == "true" ]]; then
    echo "  Removing ObjectBucketClaim..."
    kubectl -n "$NAMESPACE" delete objectbucketclaim "${S3_BUCKET}" \
      --ignore-not-found 2>/dev/null || true
  fi

  if [[ "${UNINSTALL_DELETE_NAMESPACE}" == "true" ]]; then
    echo "[8/8] Deleting namespace '${NAMESPACE}'..."
    kubectl delete namespace "$NAMESPACE" --ignore-not-found
    kubectl wait --for=delete namespace/"$NAMESPACE" --timeout=300s 2>/dev/null || true
  else
    echo "[8/8] Skipping namespace deletion (UNINSTALL_DELETE_NAMESPACE=false)."
  fi
else
  echo "Namespace '${NAMESPACE}' does not exist. Skipping namespaced resource cleanup."
fi

if [[ "${UNINSTALL_PURGE_PV}" == "true" ]] && [[ -n "${POSTGRES_BOUND_PVS}" ]]; then
  echo "Removing retained PostgreSQL PV(s)..."
  while IFS= read -r pv; do
    [[ -n "$pv" ]] || continue
    kubectl delete pv "$pv" --ignore-not-found
  done <<< "${POSTGRES_BOUND_PVS}"
fi

if [[ "${UNINSTALL_KSERVE_CLUSTER_RESOURCES}" == "true" ]]; then
  echo "Removing cluster-scoped KServe resources..."
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
