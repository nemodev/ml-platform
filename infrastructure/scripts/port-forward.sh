#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="ml-platform"

cleanup() {
  if [[ -n "${KEYCLOAK_PID:-}" ]]; then kill "$KEYCLOAK_PID" 2>/dev/null || true; fi
  if [[ -n "${POSTGRES_PID:-}" ]]; then kill "$POSTGRES_PID" 2>/dev/null || true; fi
  if [[ -n "${BACKEND_PID:-}" ]]; then kill "$BACKEND_PID" 2>/dev/null || true; fi
}

trap cleanup EXIT INT TERM

kubectl port-forward svc/keycloak 8180:8080 -n "$NAMESPACE" &
KEYCLOAK_PID=$!

kubectl port-forward svc/postgresql 5432:5432 -n "$NAMESPACE" &
POSTGRES_PID=$!

if kubectl get svc/ml-platform-backend -n "$NAMESPACE" >/dev/null 2>&1; then
  kubectl port-forward svc/ml-platform-backend 8080:8080 -n "$NAMESPACE" &
else
  kubectl port-forward deployment/ml-platform-backend 8080:8080 -n "$NAMESPACE" &
fi
BACKEND_PID=$!

wait
