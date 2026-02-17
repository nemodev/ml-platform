import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';
import { switchMap, take } from 'rxjs/operators';
import { Observable } from 'rxjs';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiUrl)) {
    return next(req);
  }

  if (!environment.enableOidc) {
    return next(req.clone({ setHeaders: { Authorization: 'Bearer dev-stub-token' } }));
  }

  const oidc = inject(OidcSecurityService, { optional: true });
  if (!oidc) {
    return next(req);
  }

  const tokenSource = oidc.getAccessToken() as unknown;
  if (typeof tokenSource === 'string') {
    if (!tokenSource) {
      return next(req);
    }
    return next(req.clone({ setHeaders: { Authorization: `Bearer ${tokenSource}` } }));
  }

  return (tokenSource as Observable<string>).pipe(
    take(1),
    switchMap((token) => {
      if (!token) {
        return next(req);
      }
      return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
    })
  );
};
