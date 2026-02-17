import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';
import { map, of, take } from 'rxjs';

function isOidcCallbackInProgress(): boolean {
  const params = new URLSearchParams(window.location.search);
  return params.has('code') && params.has('state');
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
    return of(false);
  }

  return oidc.isAuthenticated$.pipe(
    take(1),
    map((result) => result.isAuthenticated)
  );
};
