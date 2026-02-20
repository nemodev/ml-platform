import { EnvironmentProviders } from '@angular/core';
import { LogLevel, provideAuth } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';

export function provideOidcAuth(): EnvironmentProviders {
  const appBaseUrl = `${window.location.origin}/`;
  const supportsWebCrypto = typeof window !== 'undefined' && window.isSecureContext && !!window.crypto?.subtle;

  return provideAuth({
    config: {
      authority: `${environment.keycloakUrl}/realms/${environment.keycloakRealm}`,
      clientId: environment.keycloakClientId,
      redirectUrl: appBaseUrl,
      postLogoutRedirectUri: appBaseUrl,
      responseType: supportsWebCrypto ? 'code' : 'id_token token',
      scope: 'openid profile email',
      useRefreshToken: supportsWebCrypto,
      silentRenew: false,
      ignoreNonceAfterRefresh: true,
      autoUserInfo: false,
      disablePkce: !supportsWebCrypto,
      disableIdTokenValidation: !supportsWebCrypto,
      triggerRefreshWhenIdTokenExpired: supportsWebCrypto,
      renewTimeBeforeTokenExpiresInSeconds: 30,
      secureRoutes: [environment.apiUrl],
      logLevel: LogLevel.Warn
    }
  });
}
