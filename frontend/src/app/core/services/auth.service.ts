import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { environment } from '../../../environments/environment';
import { BehaviorSubject, EMPTY, Observable, catchError, map, of, shareReplay, switchMap, tap } from 'rxjs';

export interface UserInfo {
  id: string;
  oidcSubject: string;
  username: string;
  displayName?: string;
  email?: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly oidc = environment.enableOidc ? inject(OidcSecurityService, { optional: true }) : null;
  private readonly initErrorSubject = new BehaviorSubject<string | null>(null);

  readonly initError$ = this.initErrorSubject.asObservable();

  readonly isAuthenticated$: Observable<boolean> = !environment.enableOidc || !this.oidc
    ? of(true)
    : this.oidc.isAuthenticated$.pipe(map((result) => result.isAuthenticated));

  readonly currentUser$: Observable<UserInfo | null> = this.isAuthenticated$.pipe(
    switchMap((isAuthenticated) => {
      if (!isAuthenticated) {
        return of(null);
      }
      return this.http.get<UserInfo>(`${environment.apiUrl}/auth/userinfo`).pipe(
        catchError(() => of(null))
      );
    }),
    shareReplay(1)
  );

  readonly username$: Observable<string> = this.currentUser$.pipe(
    map((user) => user?.displayName || user?.username || 'Anonymous')
  );

  initializeAuth(): Observable<void> {
    if (!environment.enableOidc || !this.oidc) {
      return of(void 0);
    }

    return this.oidc.checkAuth().pipe(
      tap((result) => {
        if (!result.isAuthenticated) {
          this.oidc?.authorize();
        }
      }),
      map(() => void 0),
      catchError(() => {
        this.initErrorSubject.next('Authentication service is currently unavailable. Try again shortly.');
        return EMPTY;
      })
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/auth/logout`, {}).pipe(
      catchError(() => of(void 0)),
      switchMap(() => {
        if (!environment.enableOidc || !this.oidc) {
          return of(void 0);
        }

        return this.oidc.logoff().pipe(
          map(() => void 0),
          catchError(() => {
            // Fallback to local logout state clear if provider logout fails.
            this.oidc?.logoffLocal();
            return of(void 0);
          })
        );
      })
    );
  }
}
