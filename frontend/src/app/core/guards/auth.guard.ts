import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';
import { map, of, take } from 'rxjs';

function isOidcCallbackInProgress(): boolean {
  const query = new URLSearchParams(window.location.search);
  const hash = new URLSearchParams(window.location.hash.startsWith('#') ? window.location.hash.slice(1) : window.location.hash);

  const hasCodeFlowCallback = query.has('code') && query.has('state');
  const hasCodeFlowError = query.has('error') && (query.has('state') || query.has('session_state'));
  const hasImplicitCallback = hash.has('state') && (hash.has('id_token') || hash.has('access_token') || hash.has('error'));

  return hasCodeFlowCallback || hasCodeFlowError || hasImplicitCallback;
}

export const authGuard: CanActivateFn = () => {
  if (!environment.enableOidc) {
    return of(true);
  }

  const oidc = inject(OidcSecurityService, { optional: true });

  if (!oidc) {
    return of(false);
  }

  if (isOidcCallbackInProgress()) {
    return of(true);
  }

  return oidc.isAuthenticated$.pipe(
    take(1),
    map((result) => {
      if (result.isAuthenticated) {
        return true;
      }

      oidc.authorize();
      return false;
    })
  );
};
