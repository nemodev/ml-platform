import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';
import { map, of, take } from 'rxjs';

export const authGuard: CanActivateFn = () => {
  if (!environment.enableOidc) {
    return of(true);
  }

  const router = inject(Router);
  const oidc = inject(OidcSecurityService, { optional: true });

  if (!oidc) {
    router.navigateByUrl('/dashboard');
    return of(false);
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
