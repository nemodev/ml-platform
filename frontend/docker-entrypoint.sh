#!/usr/bin/env sh
set -eu

cat > /usr/share/nginx/html/assets/runtime-config.js <<RUNTIME_EOF
window.__ML_PLATFORM_CONFIG = {
  apiUrl: "${RUNTIME_API_URL:-/api/v1}",
  keycloakUrl: "${RUNTIME_KEYCLOAK_URL:-}",
  keycloakRealm: "${RUNTIME_KEYCLOAK_REALM:-ml-platform}",
  keycloakClientId: "${RUNTIME_KEYCLOAK_CLIENT_ID:-ml-platform-portal}",
  enableOidc: ${RUNTIME_ENABLE_OIDC:-true}
};
if (!window.__ML_PLATFORM_CONFIG.keycloakUrl) {
  window.__ML_PLATFORM_CONFIG.keycloakUrl = window.location.origin;
}
RUNTIME_EOF
