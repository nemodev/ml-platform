#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# ML Platform — Image Builder
#
# Builds and pushes all platform container images to the registry defined
# in config.env.
#
# Usage:
#   ./build-images.sh [options] [config-file]
#
# Options:
#   --mac          Build for linux/arm64 (Apple Silicon). Default: linux/amd64.
#   --no-push      Build images only, do not push to registry.
#   --only <name>  Build only one image: backend, frontend, or notebook.
#   --help         Show this help message.
#
# Examples:
#   ./build-images.sh                          # Build all, push, amd64
#   ./build-images.sh --mac                    # Build all, push, arm64
#   ./build-images.sh --only backend           # Build + push backend only
#   ./build-images.sh --no-push config.env     # Build all, skip push
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# ── Defaults ────────────────────────────────────────────────────────────────
PLATFORM="linux/amd64"
PUSH=true
ONLY=""
CONFIG_FILE="${SCRIPT_DIR}/config.env"

# ── Parse arguments ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mac)       PLATFORM="linux/arm64"; shift ;;
    --no-push)   PUSH=false; shift ;;
    --only)      ONLY="$2"; shift 2 ;;
    --help|-h)
      sed -n '2,/^# ─── Defaults/{ /^#/s/^# \?//p; }' "$0"
      exit 0
      ;;
    *)           CONFIG_FILE="$1"; shift ;;
  esac
done

# ── Load configuration ─────────────────────────────────────────────────────
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "ERROR: Config file not found: $CONFIG_FILE"
  echo "Copy config.env.example to config.env and edit it."
  exit 1
fi
echo "Loading config from: $CONFIG_FILE"
# shellcheck disable=SC1090
source "$CONFIG_FILE"

# Validate required image variables
for var in BACKEND_IMAGE FRONTEND_IMAGE NOTEBOOK_IMAGE; do
  if [[ -z "${!var:-}" ]]; then
    echo "ERROR: $var is not set in $CONFIG_FILE"
    exit 1
  fi
done

# Extract registry from image reference (everything before the first /)
REGISTRY="${BACKEND_IMAGE%%/*}"

echo ""
echo "  Platform:  $PLATFORM"
echo "  Registry:  $REGISTRY"
echo "  Push:      $PUSH"
if [[ -n "$ONLY" ]]; then
  echo "  Only:      $ONLY"
fi
echo ""

# ── Helpers ─────────────────────────────────────────────────────────────────
STEP=0
step() {
  STEP=$((STEP + 1))
  echo ""
  echo "═══════════════════════════════════════════════════════════════"
  echo "  [$STEP] $1"
  echo "═══════════════════════════════════════════════════════════════"
}

should_build() {
  [[ -z "$ONLY" ]] || [[ "$ONLY" == "$1" ]]
}

build_and_push() {
  local image="$1"
  local context="$2"
  shift 2
  # remaining args are extra docker build flags

  echo "  Building: $image"
  echo "  Context:  $context"
  echo "  Platform: $PLATFORM"
  docker build --platform "$PLATFORM" -t "$image" "$@" "$context"

  if [[ "$PUSH" == "true" ]]; then
    echo "  Pushing:  $image"
    docker push "$image"
  fi
  echo "  Done: $image"
}

# ══════════════════════════════════════════════════════════════════════════════
# Backend
# ══════════════════════════════════════════════════════════════════════════════
if should_build "backend"; then
  step "Backend"

  echo "  Running Gradle build..."
  BACKEND_DIR="${PROJECT_ROOT}/backend"
  if [[ -x "${BACKEND_DIR}/gradlew" ]]; then
    (cd "$BACKEND_DIR" && ./gradlew build -x test --quiet)
  else
    (cd "$BACKEND_DIR" && gradle build -x test --quiet)
  fi

  build_and_push "$BACKEND_IMAGE" "${PROJECT_ROOT}/backend"
fi

# ══════════════════════════════════════════════════════════════════════════════
# Frontend
# ══════════════════════════════════════════════════════════════════════════════
if should_build "frontend"; then
  step "Frontend"

  # Use Dockerfile.runtime (pre-built) when dist/ exists, otherwise full Dockerfile
  FRONTEND_DIR="${PROJECT_ROOT}/frontend"
  if [[ -d "${FRONTEND_DIR}/dist" ]]; then
    echo "  Using pre-built dist/ with Dockerfile.runtime"
    build_and_push "$FRONTEND_IMAGE" "$FRONTEND_DIR" -f "${FRONTEND_DIR}/Dockerfile.runtime"
  else
    echo "  Building from source with multi-stage Dockerfile"
    build_and_push "$FRONTEND_IMAGE" "$FRONTEND_DIR"
  fi
fi

# ══════════════════════════════════════════════════════════════════════════════
# Notebook
# ══════════════════════════════════════════════════════════════════════════════
if should_build "notebook"; then
  step "Notebook"

  build_and_push "$NOTEBOOK_IMAGE" "${PROJECT_ROOT}/infrastructure/docker/notebook-image"
fi

# ══════════════════════════════════════════════════════════════════════════════
# Summary
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Build complete!"
echo "═══════════════════════════════════════════════════════════════"
echo ""
if should_build "backend";  then echo "  Backend:  $BACKEND_IMAGE"; fi
if should_build "frontend"; then echo "  Frontend: $FRONTEND_IMAGE"; fi
if should_build "notebook"; then echo "  Notebook: $NOTEBOOK_IMAGE"; fi
echo ""
if [[ "$PUSH" == "true" ]]; then
  echo "  All images pushed to $REGISTRY"
else
  echo "  Images built locally (not pushed). Run with --no-push removed to push."
fi
echo ""
