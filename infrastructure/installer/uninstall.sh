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

echo "This will remove the ML Platform from namespace '${NAMESPACE}'."
echo "Press Ctrl+C within 5 seconds to abort..."
sleep 5

echo "[1/7] Removing backend & frontend..."
kubectl -n "$NAMESPACE" delete deploy backend frontend --ignore-not-found
kubectl -n "$NAMESPACE" delete svc backend frontend --ignore-not-found
kubectl -n "$NAMESPACE" delete configmap frontend-nginx-template --ignore-not-found
kubectl -n "$NAMESPACE" delete certificate frontend-tls --ignore-not-found 2>/dev/null || true
kubectl -n "$NAMESPACE" delete secret frontend-tls --ignore-not-found
# Note: selfsigned ClusterIssuer is cluster-scoped and may be shared; not deleted here.
kubectl -n "$NAMESPACE" delete sa backend --ignore-not-found
kubectl -n "$NAMESPACE" delete role backend-kserve-manager --ignore-not-found
kubectl -n "$NAMESPACE" delete rolebinding backend-kserve-manager --ignore-not-found

echo "[2/7] Removing Airflow..."
helm uninstall airflow -n "$NAMESPACE" 2>/dev/null || true
kubectl -n "$NAMESPACE" delete configmap airflow-notebook-runner-dag --ignore-not-found
kubectl -n "$NAMESPACE" delete sa airflow-spark-sa --ignore-not-found
kubectl -n "$NAMESPACE" delete role airflow-spark-role --ignore-not-found
kubectl -n "$NAMESPACE" delete rolebinding airflow-spark-rolebinding --ignore-not-found

echo "[3/7] Removing JupyterHub..."
helm uninstall jupyterhub -n "$NAMESPACE" 2>/dev/null || true

echo "[4/7] Removing sample data..."
kubectl -n "$NAMESPACE" delete job provision-sample-data --ignore-not-found
kubectl -n "$NAMESPACE" delete configmap provision-sample-data-script sample-notebook batch-inference-notebook --ignore-not-found
kubectl -n "$NAMESPACE" delete secret sample-data-readonly-credentials s3-credentials --ignore-not-found

echo "[5/7] Removing MLflow..."
helm uninstall mlflow -n "$NAMESPACE" 2>/dev/null || true

echo "[6/7] Removing KServe resources..."
kubectl -n "$NAMESPACE" delete inferenceservices.serving.kserve.io --all --ignore-not-found 2>/dev/null || true
kubectl -n "$NAMESPACE" delete sa kserve-s3-sa --ignore-not-found
kubectl -n "$NAMESPACE" delete secret kserve-s3-secret --ignore-not-found
if [[ "${UNINSTALL_KSERVE_CLUSTER_RESOURCES}" == "true" ]]; then
  echo "  Removing KServe controller, namespace, and CRDs..."
  kubectl delete --ignore-not-found -f "${KSERVE_RELEASE_URL}/kserve-cluster-resources.yaml" 2>/dev/null || true
  kubectl delete --ignore-not-found -f "${KSERVE_RELEASE_URL}/kserve.yaml" 2>/dev/null || true
  kubectl delete namespace "$KSERVE_SYSTEM_NAMESPACE" --ignore-not-found 2>/dev/null || true
  KSERVE_CRDS="$(kubectl get crd -o name | grep 'serving.kserve.io' || true)"
  if [[ -n "${KSERVE_CRDS}" ]]; then
    # shellcheck disable=SC2086
    kubectl delete --ignore-not-found ${KSERVE_CRDS} 2>/dev/null || true
  fi
else
  echo "  Skipping cluster-scoped KServe uninstall (UNINSTALL_KSERVE_CLUSTER_RESOURCES=false)."
fi

echo "[7/7] Removing PostgreSQL..."
if [[ "${DEPLOY_POSTGRESQL:-true}" == "true" ]]; then
  kubectl -n "$NAMESPACE" delete statefulset postgresql --ignore-not-found
  kubectl -n "$NAMESPACE" delete svc postgresql --ignore-not-found
  kubectl -n "$NAMESPACE" delete secret postgresql-credentials --ignore-not-found
  echo "  NOTE: PVC 'data-postgresql-0' preserved. Delete manually if no longer needed:"
  echo "    kubectl -n ${NAMESPACE} delete pvc data-postgresql-0"
fi

if [[ "${DEPLOY_OBC:-false}" == "true" ]]; then
  echo "Removing ObjectBucketClaim..."
  kubectl -n "$NAMESPACE" delete objectbucketclaim "${S3_BUCKET}" \
    --ignore-not-found 2>/dev/null || true
fi

echo ""
echo "ML Platform uninstalled from namespace '${NAMESPACE}'."
echo "Namespace '${NAMESPACE}' was NOT deleted."
echo "To fully clean up:  kubectl delete namespace ${NAMESPACE}"
