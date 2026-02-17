import { NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Subscription, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  ComputeProfile,
  WorkspaceService,
  WorkspaceState,
  WorkspaceStatus
} from '../../core/services/workspace.service';

@Component({
  selector: 'app-notebooks',
  standalone: true,
  imports: [NgIf],
  templateUrl: './notebooks.component.html',
  styleUrl: './notebooks.component.scss'
})
export class NotebooksComponent implements OnInit, OnDestroy {
  private readonly workspaceService = inject(WorkspaceService);
  private readonly sanitizer = inject(DomSanitizer);

  status: WorkspaceState = 'STOPPED';
  statusMessage = 'Workspace is stopped.';
  errorMessage: string | null = null;
  profile: ComputeProfile | null = null;
  iframeUrl: SafeResourceUrl | null = null;

  private pollSub?: Subscription;

  ngOnInit(): void {
    this.workspaceService.getProfiles().pipe(
      catchError(() => of([] as ComputeProfile[]))
    ).subscribe((profiles) => {
      this.profile = profiles[0] ?? null;
    });

    this.refreshStatus();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  launchWorkspace(): void {
    this.errorMessage = null;
    this.status = 'PENDING';
    this.statusMessage = 'Starting notebook server...';
    this.workspaceService.launchWorkspace(this.profile?.id ?? 'exploratory').subscribe({
      next: () => this.startPolling(),
      error: (error) => {
        this.status = 'FAILED';
        this.errorMessage = error?.error?.message ?? 'Unable to launch workspace.';
      }
    });
  }

  terminateWorkspace(): void {
    this.workspaceService.terminateWorkspace().subscribe({
      next: () => {
        this.status = 'STOPPED';
        this.statusMessage = 'Workspace is stopped.';
        this.iframeUrl = null;
      },
      error: () => {
        this.errorMessage = 'Failed to terminate workspace.';
      }
    });
  }

  retry(): void {
    this.launchWorkspace();
  }

  private refreshStatus(): void {
    this.workspaceService.getStatus().subscribe({
      next: (status) => {
        this.applyStatus(status);
        if (status.status === 'RUNNING' || status.status === 'IDLE') {
          this.loadWorkspaceUrl();
          return;
        }
        if (status.status === 'PENDING') {
          this.startPolling();
        }
      },
      error: () => {
        this.status = 'FAILED';
        this.errorMessage = 'Workspace status is unavailable.';
      }
    });
  }

  private startPolling(): void {
    this.pollSub?.unsubscribe();
    this.pollSub = this.workspaceService.watchStatusUntilStable(3000).subscribe({
      next: (status) => {
        this.applyStatus(status);
        if (status.status === 'RUNNING' || status.status === 'IDLE') {
          this.loadWorkspaceUrl();
        }
      },
      error: () => {
        this.status = 'FAILED';
        this.errorMessage = 'Workspace launch failed.';
      }
    });
  }

  private loadWorkspaceUrl(): void {
    this.workspaceService.getWorkspaceUrl().subscribe({
      next: (response) => {
        this.iframeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(response.url);
      },
      error: () => {
        this.status = 'FAILED';
        this.errorMessage = 'Workspace URL is unavailable.';
      }
    });
  }

  private applyStatus(status: WorkspaceStatus): void {
    this.status = status.status;
    this.statusMessage = status.message ?? `Workspace status: ${status.status}`;
    if (status.status === 'STOPPED') {
      this.iframeUrl = null;
    }
  }
}
