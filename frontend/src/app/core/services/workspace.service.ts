import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { Observable, of, timer } from 'rxjs';
import { catchError, map, switchMap, takeWhile } from 'rxjs/operators';

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

  private analysisWorkspacesUrl(analysisId: string): string {
    return `${environment.apiUrl}/analyses/${analysisId}/workspaces`;
  }

  getProfiles(analysisId: string): Observable<ComputeProfile[]> {
    return this.http.get<ComputeProfile[]>(`${this.analysisWorkspacesUrl(analysisId)}/profiles`);
  }

  launchWorkspace(analysisId: string, profile = 'exploratory'): Observable<WorkspaceStatus> {
    return this.http.post<WorkspaceStatus>(this.analysisWorkspacesUrl(analysisId), { profile });
  }

  getStatus(analysisId: string): Observable<WorkspaceStatus> {
    return this.http.get<WorkspaceStatus>(this.analysisWorkspacesUrl(analysisId));
  }

  terminateWorkspace(analysisId: string): Observable<void> {
    return this.http.delete<void>(this.analysisWorkspacesUrl(analysisId));
  }

  getWorkspaceUrl(analysisId: string, notebookPath?: string): Observable<WorkspaceUrl> {
    const base = `${this.analysisWorkspacesUrl(analysisId)}/url`;
    if (notebookPath) {
      return this.http.get<WorkspaceUrl>(`${base}?notebookPath=${encodeURIComponent(notebookPath)}`);
    }
    return this.http.get<WorkspaceUrl>(base);
  }

  getKernelStatus(analysisId: string): Observable<string> {
    return this.http.get<{ status: string }>(`${this.analysisWorkspacesUrl(analysisId)}/kernel-status`).pipe(
      map(res => res.status),
      catchError(() => of('disconnected'))
    );
  }

  watchStatusUntilStable(analysisId: string, intervalMs = 3000): Observable<WorkspaceStatus> {
    return timer(0, intervalMs).pipe(
      switchMap(() => this.getStatus(analysisId)),
      takeWhile((status) => status.status === 'PENDING', true)
    );
  }
}
