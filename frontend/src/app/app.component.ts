import { AsyncPipe, NgFor, NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Subscription, filter, map, switchMap, take, timer } from 'rxjs';
import { AuthService } from './core/services/auth.service';
import { NotificationService } from './core/services/notification.service';
import { NotificationBannerComponent } from './shared/notification-banner/notification-banner.component';
import { environment } from '../environments/environment';

interface PortalSection {
  id: string;
  name: string;
  path: string;
  icon?: string;
  enabled: boolean;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, AsyncPipe, NgFor, NgIf, NotificationBannerComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly authService = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly notificationService = inject(NotificationService);

  private notificationPollSub?: Subscription;

  readonly username$ = this.authService.username$;
  readonly initError$ = this.authService.initError$;

  sections: PortalSection[] = [
    { id: 'dashboard', name: 'Dashboard', path: '/dashboard', enabled: true },
    { id: 'analyses', name: 'Analyses', path: '/analyses', enabled: true },
    { id: 'models', name: 'Models', path: '/models', enabled: true },
    { id: 'pipelines', name: 'Pipelines', path: '/pipelines', enabled: true },
    { id: 'notebook-images', name: 'Custom Images', path: '/notebook-images', enabled: true }
  ];

  ngOnInit(): void {
    this.authService.isAuthenticated$.pipe(
      filter((isAuthenticated) => isAuthenticated),
      take(1),
      switchMap(() => this.http.get<PortalSection[]>(`${environment.apiUrl}/portal/sections`)),
      map((sections) => sections.filter((s) => s.enabled)),
      filter((sections) => sections.length > 0)
    ).subscribe({
      next: (sections) => {
        this.sections = sections;
      },
      error: () => {
        // Keep default static sections when backend is unavailable.
      }
    });

    // Poll for build notifications (first after 5s, then every 30s)
    this.notificationPollSub = timer(5000, 30000).subscribe(() => {
      this.notificationService.pollNotifications();
    });
  }

  ngOnDestroy(): void {
    this.notificationPollSub?.unsubscribe();
  }

  logout(): void {
    this.authService.logout().subscribe();
  }
}
