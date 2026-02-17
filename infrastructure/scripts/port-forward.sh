#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="ml-platform"
KUBE_CONTEXT="${KUBE_CONTEXT:-local}"
KUBECONFIG_PATH="${KUBECONFIG_PATH:-/tmp/rd-k3s.yaml}"

KUBECTL=(kubectl --context "$KUBE_CONTEXT")
if [[ -f "$KUBECONFIG_PATH" ]]; then
  KUBECTL+=(--kubeconfig "$KUBECONFIG_PATH")
fi

kctl() {
  "${KUBECTL[@]}" "$@"
}

cleanup() {
  if [[ -n "${KEYCLOAK_PID:-}" ]]; then kill "$KEYCLOAK_PID" 2>/dev/null || true; fi
  if [[ -n "${POSTGRES_PID:-}" ]]; then kill "$POSTGRES_PID" 2>/dev/null || true; fi
  if [[ -n "${JUPYTERHUB_PID:-}" ]]; then kill "$JUPYTERHUB_PID" 2>/dev/null || true; fi
  if [[ -n "${BACKEND_PID:-}" ]]; then kill "$BACKEND_PID" 2>/dev/null || true; fi
  if [[ -n "${MLFLOW_PID:-}" ]]; then kill "$MLFLOW_PID" 2>/dev/null || true; fi
  if [[ -n "${MINIO_PID:-}" ]]; then kill "$MINIO_PID" 2>/dev/null || true; fi
  if [[ -n "${AIRFLOW_PID:-}" ]]; then kill "$AIRFLOW_PID" 2>/dev/null || true; fi
}

trap cleanup EXIT INT TERM

kctl port-forward svc/keycloak 8180:8080 -n "$NAMESPACE" &
KEYCLOAK_PID=$!

kctl port-forward svc/postgresql 5432:5432 -n "$NAMESPACE" &
POSTGRES_PID=$!

JUPYTERHUB_PID=""
if kctl get svc/proxy-public -n "$NAMESPACE" >/dev/null 2>&1; then
  kctl port-forward svc/proxy-public 8181:80 -n "$NAMESPACE" &
  JUPYTERHUB_PID=$!
elif kctl get svc/jupyterhub-proxy-public -n "$NAMESPACE" >/dev/null 2>&1; then
  kctl port-forward svc/jupyterhub-proxy-public 8181:80 -n "$NAMESPACE" &
  JUPYTERHUB_PID=$!
fi

if kctl get svc/ml-platform-backend -n "$NAMESPACE" >/dev/null 2>&1; then
  kctl port-forward svc/ml-platform-backend 8080:8080 -n "$NAMESPACE" &
else
  kctl port-forward deployment/ml-platform-backend 8080:8080 -n "$NAMESPACE" &
fi
BACKEND_PID=$!

MLFLOW_PID=""
if kctl get svc/mlflow -n "$NAMESPACE" >/dev/null 2>&1; then
  kctl port-forward svc/mlflow 15000:5000 -n "$NAMESPACE" &
  MLFLOW_PID=$!
fi

MINIO_PID=""
if kctl get svc/minio -n "$NAMESPACE" >/dev/null 2>&1; then
  kctl port-forward svc/minio 9000:9000 9001:9001 -n "$NAMESPACE" &
  MINIO_PID=$!
fi

AIRFLOW_PID=""
if kctl get svc/airflow-webserver -n "$NAMESPACE" >/dev/null 2>&1; then
  kctl port-forward svc/airflow-webserver 8280:8080 -n "$NAMESPACE" &
  AIRFLOW_PID=$!
fi

wait
