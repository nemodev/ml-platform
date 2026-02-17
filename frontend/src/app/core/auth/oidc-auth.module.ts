import { EnvironmentProviders } from '@angular/core';
import { LogLevel, provideAuth } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';

export function provideOidcAuth(): EnvironmentProviders {
  const appBaseUrl = `${window.location.origin}/`;

  return provideAuth({
    config: {
      authority: `${environment.keycloakUrl}/realms/${environment.keycloakRealm}`,
      clientId: environment.keycloakClientId,
      redirectUrl: appBaseUrl,
      postLogoutRedirectUri: appBaseUrl,
      responseType: 'code',
      scope: 'openid profile email',
      useRefreshToken: true,
      silentRenew: true,
      renewTimeBeforeTokenExpiresInSeconds: 30,
      secureRoutes: [environment.apiUrl],
      logLevel: LogLevel.Warn
    }
  });
}
