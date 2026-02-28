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
#   --mac              Build for linux/arm64 (Apple Silicon). Default: linux/amd64.
#   --no-push          Build images only, do not push to registry.
#   --only <name>      Build only one image: backend, frontend, or notebook.
#   --remote <user@host>  Build on a remote amd64 machine via SSH (avoids QEMU).
#                         Requires Docker on the remote host and SSH key auth.
#   --help             Show this help message.
#
# Examples:
#   ./build-images.sh                                  # Build all, push, amd64
#   ./build-images.sh --mac                            # Build all, push, arm64
#   ./build-images.sh --only backend                   # Build + push backend only
#   ./build-images.sh --no-push config.env             # Build all, skip push
#   ./build-images.sh --remote bruce@172.16.100.10     # Build on remote amd64 host
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# ── Defaults ────────────────────────────────────────────────────────────────
PLATFORM="linux/amd64"
PUSH=true
ONLY=""
REMOTE_HOST=""
CONFIG_FILE="${SCRIPT_DIR}/config.env"
BUILD_BASE_IMAGES=false
PYTHON_VERSIONS=("3.10" "3.11" "3.12")

# ── Parse arguments ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mac)       PLATFORM="linux/arm64"; shift ;;
    --no-push)   PUSH=false; shift ;;
    --only)      ONLY="$2"; shift 2 ;;
    --remote)    REMOTE_HOST="$2"; shift 2 ;;
    --base-images) BUILD_BASE_IMAGES=true; shift ;;
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
if [[ -n "$REMOTE_HOST" ]]; then
  echo "  Remote:    $REMOTE_HOST"
fi
echo ""

# ── Remote builder setup ────────────────────────────────────────────────────
BUILDER_NAME=""
cleanup_builder() {
  if [[ -n "$BUILDER_NAME" ]]; then
    echo "  Cleaning up remote builder: $BUILDER_NAME"
    docker buildx rm "$BUILDER_NAME" 2>/dev/null || true
  fi
}

if [[ -n "$REMOTE_HOST" ]]; then
  BUILDER_NAME="ml-platform-remote-$$"
  echo "Setting up remote builder via ssh://$REMOTE_HOST ..."
  docker buildx create --name "$BUILDER_NAME" \
    --driver docker-container \
    --platform linux/amd64 \
    "ssh://$REMOTE_HOST"
  docker buildx use "$BUILDER_NAME"
  trap cleanup_builder EXIT
  echo "  Remote builder ready: $BUILDER_NAME"
  echo ""
fi

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

  if [[ -n "$REMOTE_HOST" ]]; then
    # Remote build: use buildx, push directly to registry
    local push_flag=""
    if [[ "$PUSH" == "true" ]]; then
      push_flag="--push"
    else
      push_flag="--load"
    fi
    docker buildx build --platform "$PLATFORM" \
      -t "$image" $push_flag "$@" "$context"
  else
    # Local build: standard docker build + push
    docker build --platform "$PLATFORM" -t "$image" "$@" "$context"
    if [[ "$PUSH" == "true" ]]; then
      echo "  Pushing:  $image"
      docker push "$image"
    fi
  fi
  echo "  Done: $image"
}

# ══════════════════════════════════════════════════════════════════════════════
# Notebook Base Images (for custom notebook image builds)
# ══════════════════════════════════════════════════════════════════════════════
if [[ "$BUILD_BASE_IMAGES" == "true" ]]; then
  step "Notebook Base Images"

  NOTEBOOK_DIR="${PROJECT_ROOT}/infrastructure/docker/notebook-image"
  BASE_IMAGE_PREFIX="${REGISTRY}/ml-platform/notebook-base"

  for pyver in "${PYTHON_VERSIONS[@]}"; do
    BASE_TAG="${BASE_IMAGE_PREFIX}:python-${pyver}"
    echo ""
    echo "  Building base image for Python ${pyver}: ${BASE_TAG}"
    build_and_push "$BASE_TAG" "$NOTEBOOK_DIR" --build-arg "PYTHON_VERSION=${pyver}"
  done

  echo ""
  echo "  Base images built for Python versions: ${PYTHON_VERSIONS[*]}"
fi

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
if [[ "$BUILD_BASE_IMAGES" == "true" ]]; then
  for pyver in "${PYTHON_VERSIONS[@]}"; do
    echo "  Base:     ${REGISTRY}/ml-platform/notebook-base:python-${pyver}"
  done
fi
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
