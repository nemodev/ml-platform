import { AsyncPipe, NgFor, NgIf } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map, switchMap, take } from 'rxjs';
import { AuthService } from './core/services/auth.service';
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
  imports: [RouterOutlet, RouterLink, RouterLinkActive, AsyncPipe, NgFor, NgIf],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly http = inject(HttpClient);

  readonly username$ = this.authService.username$;
  readonly initError$ = this.authService.initError$;

  sections: PortalSection[] = [
    { id: 'dashboard', name: 'Dashboard', path: '/dashboard', enabled: true },
    { id: 'analyses', name: 'Analyses', path: '/analyses', enabled: true },
    { id: 'models', name: 'Models', path: '/models', enabled: true },
    { id: 'pipelines', name: 'Pipelines', path: '/pipelines', enabled: true }
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
  }

  logout(): void {
    this.authService.logout().subscribe();
  }
}
