import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { Observable, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';

export type WorkspaceState = 'PENDING' | 'RUNNING' | 'IDLE' | 'STOPPED' | 'FAILED';

export interface ComputeProfile {
  id: string;
  name: string;
  description: string;
  cpuRequest: string;
  cpuLimit: string;
  memoryRequest: string;
  memoryLimit: string;
  gpuLimit?: number;
}

export interface WorkspaceStatus {
  id?: string;
  status: WorkspaceState;
  profile?: string;
  startedAt?: string;
  lastActivity?: string;
  message?: string;
}

export interface WorkspaceUrl {
  url: string;
}

@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  private readonly http = inject(HttpClient);

  getProfiles(): Observable<ComputeProfile[]> {
    return this.http.get<ComputeProfile[]>(`${environment.apiUrl}/workspaces/profiles`);
  }

  launchWorkspace(profile = 'exploratory'): Observable<WorkspaceStatus> {
    return this.http.post<WorkspaceStatus>(`${environment.apiUrl}/workspaces`, { profile });
  }

  getStatus(): Observable<WorkspaceStatus> {
    return this.http.get<WorkspaceStatus>(`${environment.apiUrl}/workspaces`);
  }

  terminateWorkspace(): Observable<void> {
    return this.http.delete<void>(`${environment.apiUrl}/workspaces`);
  }

  getWorkspaceUrl(): Observable<WorkspaceUrl> {
    return this.http.get<WorkspaceUrl>(`${environment.apiUrl}/workspaces/url`);
  }

  watchStatusUntilStable(intervalMs = 3000): Observable<WorkspaceStatus> {
    return timer(0, intervalMs).pipe(
      switchMap(() => this.getStatus()),
      takeWhile((status) => status.status === 'PENDING', true)
    );
  }
}
