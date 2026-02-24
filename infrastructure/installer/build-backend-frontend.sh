#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# ML Platform — Backend + Frontend Code Builder
#
# Builds code artifacts only (no Docker image builds):
#   - backend: Spring Boot JAR under backend/build/libs/
#   - frontend: Angular dist under frontend/dist/
#
# Usage:
#   ./build-backend-frontend.sh [options]
#
# Options:
#   --backend-only    Build backend only.
#   --frontend-only   Build frontend only.
#   --skip-npm-ci     Skip "npm ci" and reuse existing node_modules.
#   --with-tests      Run backend tests (default skips tests).
#   --help            Show this help message.
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BUILD_BACKEND=true
BUILD_FRONTEND=true
RUN_NPM_CI=true
BACKEND_WITH_TESTS=false

show_help() {
  awk '
    /^set -euo pipefail/ { exit }
    /^#!/ { next }
    /^#/ { sub(/^# ?/, ""); print }
  ' "$0"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend-only)
      BUILD_FRONTEND=false
      shift
      ;;
    --frontend-only)
      BUILD_BACKEND=false
      shift
      ;;
    --skip-npm-ci)
      RUN_NPM_CI=false
      shift
      ;;
    --with-tests)
      BACKEND_WITH_TESTS=true
      shift
      ;;
    --help|-h)
      show_help
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1"
      show_help
      exit 1
      ;;
  esac
done

if [[ "$BUILD_BACKEND" != "true" && "$BUILD_FRONTEND" != "true" ]]; then
  echo "ERROR: nothing to build."
  exit 1
fi

step() {
  echo ""
  echo "═══════════════════════════════════════════════════════════════"
  echo "  $1"
  echo "═══════════════════════════════════════════════════════════════"
}

if [[ "$BUILD_BACKEND" == "true" ]]; then
  if ! command -v gradle >/dev/null 2>&1; then
    echo "ERROR: gradle is required to build backend."
    exit 1
  fi

  step "Building backend artifact"
  BACKEND_DIR="${PROJECT_ROOT}/backend"
  if [[ "$BACKEND_WITH_TESTS" == "true" ]]; then
    (cd "$BACKEND_DIR" && gradle clean bootJar)
  else
    (cd "$BACKEND_DIR" && gradle clean bootJar -x test)
  fi

  BACKEND_JAR="$(ls -1t "${BACKEND_DIR}"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -n1 || true)"
  if [[ -z "$BACKEND_JAR" ]]; then
    echo "ERROR: backend JAR not found under ${BACKEND_DIR}/build/libs"
    exit 1
  fi
  echo "Backend artifact: $BACKEND_JAR"
fi

if [[ "$BUILD_FRONTEND" == "true" ]]; then
  if ! command -v npm >/dev/null 2>&1; then
    echo "ERROR: npm is required to build frontend."
    exit 1
  fi

  step "Building frontend artifact"
  FRONTEND_DIR="${PROJECT_ROOT}/frontend"
  if [[ "$RUN_NPM_CI" == "true" ]]; then
    (cd "$FRONTEND_DIR" && npm ci)
  fi
  (cd "$FRONTEND_DIR" && npm run build)

  FRONTEND_DIST="${FRONTEND_DIR}/dist/ml-platform-frontend/browser"
  if [[ ! -d "$FRONTEND_DIST" ]]; then
    echo "ERROR: frontend dist not found: $FRONTEND_DIST"
    exit 1
  fi
  echo "Frontend artifact: $FRONTEND_DIST"
fi

echo ""
echo "Code build complete."
