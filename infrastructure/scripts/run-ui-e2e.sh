#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FRONTEND_DIR="${ROOT_DIR}/frontend"

MODE="${UI_TEST_MODE:-smoke}"
POSITIONAL_ARGS=()
PLAYWRIGHT_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for --mode (expected: smoke|full|all)" >&2
        exit 1
      fi
      MODE="$2"
      shift 2
      ;;
    --help|-h)
      cat <<'EOF'
Usage:
  run-ui-e2e.sh [base_url] [username] [password] [--mode smoke|full|all] [playwright args...]

Examples:
  run-ui-e2e.sh
  run-ui-e2e.sh --mode smoke
  run-ui-e2e.sh http://172.16.100.10:30080 scientist1 password1 --mode full
  run-ui-e2e.sh --mode smoke --list
EOF
      exit 0
      ;;
    --)
      shift
      while [[ $# -gt 0 ]]; do
        PLAYWRIGHT_ARGS+=("$1")
        shift
      done
      ;;
    -*)
      PLAYWRIGHT_ARGS+=("$1")
      shift
      ;;
    *)
      POSITIONAL_ARGS+=("$1")
      shift
      ;;
  esac
done

BASE_URL="${POSITIONAL_ARGS[0]:-${BASE_URL:-http://172.16.100.10:30080}}"
UI_TEST_USERNAME="${POSITIONAL_ARGS[1]:-${UI_TEST_USERNAME:-scientist1}}"
UI_TEST_PASSWORD="${POSITIONAL_ARGS[2]:-${UI_TEST_PASSWORD:-password1}}"

if [[ ${#POSITIONAL_ARGS[@]} -gt 3 ]]; then
  for arg in "${POSITIONAL_ARGS[@]:3}"; do
    PLAYWRIGHT_ARGS+=("$arg")
  done
fi

UI_TEST_HEADLESS="${UI_TEST_HEADLESS:-true}"
UI_TEST_INSTALL_BROWSERS="${UI_TEST_INSTALL_BROWSERS:-1}"

echo "Running UI end-to-end tests"
echo "  BASE_URL=${BASE_URL}"
echo "  UI_TEST_USERNAME=${UI_TEST_USERNAME}"
echo "  UI_TEST_HEADLESS=${UI_TEST_HEADLESS}"
echo "  MODE=${MODE}"

PLAYWRIGHT_GREP="@smoke"
case "${MODE}" in
  smoke)
    PLAYWRIGHT_GREP="@smoke"
    ;;
  full)
    PLAYWRIGHT_GREP="@full"
    ;;
  all)
    PLAYWRIGHT_GREP=""
    ;;
  *)
    echo "Unsupported mode '${MODE}'. Use smoke, full, or all." >&2
    exit 1
    ;;
esac

cd "${FRONTEND_DIR}"

if [[ ! -d node_modules ]]; then
  npm install --no-audit --no-fund
fi

if [[ "${UI_TEST_INSTALL_BROWSERS}" == "1" ]]; then
  npx playwright install chromium
fi

BASE_URL="${BASE_URL}" \
UI_TEST_USERNAME="${UI_TEST_USERNAME}" \
UI_TEST_PASSWORD="${UI_TEST_PASSWORD}" \
UI_TEST_HEADLESS="${UI_TEST_HEADLESS}" \
UI_TEST_MODE="${MODE}" \
npx playwright test -c e2e/playwright.config.ts ${PLAYWRIGHT_GREP:+--grep "$PLAYWRIGHT_GREP"} "${PLAYWRIGHT_ARGS[@]-}"
