export interface RuntimeConfig {
  apiUrl: string;
  keycloakUrl: string;
  keycloakRealm: string;
  keycloakClientId: string;
  enableOidc: boolean;
}

declare global {
  interface Window {
    __ML_PLATFORM_CONFIG?: Partial<RuntimeConfig>;
  }
}

const DEFAULT_ORIGIN = typeof window !== 'undefined'
  ? window.location.origin
  : 'http://localhost:4200';

const runtimeConfig = (typeof window !== 'undefined' && window.__ML_PLATFORM_CONFIG)
  ? window.__ML_PLATFORM_CONFIG
  : {};

export const resolvedRuntimeConfig: RuntimeConfig = {
  apiUrl: runtimeConfig.apiUrl ?? `${DEFAULT_ORIGIN}/api/v1`,
  // Use origin as default so authority matches Keycloak issuer (/realms/...) behind nginx.
  keycloakUrl: runtimeConfig.keycloakUrl ?? DEFAULT_ORIGIN,
  keycloakRealm: runtimeConfig.keycloakRealm ?? 'ml-platform',
  keycloakClientId: runtimeConfig.keycloakClientId ?? 'ml-platform-portal',
  enableOidc: runtimeConfig.enableOidc ?? true
};
