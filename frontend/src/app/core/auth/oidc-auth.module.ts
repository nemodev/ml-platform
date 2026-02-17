import { EnvironmentProviders } from '@angular/core';
import { LogLevel, provideAuth } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';

export function provideOidcAuth(): EnvironmentProviders {
  return provideAuth({
    config: {
      authority: `${environment.keycloakUrl}/realms/${environment.keycloakRealm}`,
      clientId: environment.keycloakClientId,
      redirectUrl: window.location.origin,
      postLogoutRedirectUri: window.location.origin,
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
