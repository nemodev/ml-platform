import { NgIf } from '@angular/common';
import { Component, Input, OnDestroy, OnInit, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Subscription, interval, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import {
  ComputeProfile,
  WorkspaceService,
  WorkspaceState,
  WorkspaceStatus
} from '../../core/services/workspace.service';
import { JupyterBridgeService } from '../../core/services/jupyter-bridge.service';

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
  readonly bridgeService = inject(JupyterBridgeService);

  @Input() analysisId!: string;

  status: WorkspaceState = 'STOPPED';
  statusMessage = 'Workspace is stopped.';
  errorMessage: string | null = null;
  profile: ComputeProfile | null = null;
  iframeUrl: SafeResourceUrl | null = null;

  // Bridge-driven toolbar state
  sidebarVisible = false;
  currentTheme: 'light' | 'dark' = 'light';
  kernelStatus: 'idle' | 'busy' | 'disconnected' | 'unknown' | 'no_kernel' = 'unknown';

  private pollSub?: Subscription;
  private kernelPollSub?: Subscription;
  private originalWorkspaceUrl: string | null = null;
  private reauthAttempted = false;

  get bridgeConnected(): boolean {
    return this.bridgeService.connectionState() === 'ready';
  }

  get kernelStatusLabel(): string {
    switch (this.kernelStatus) {
      case 'idle': return 'Idle';
      case 'busy': return 'Busy';
      case 'disconnected': return 'Disconnected';
      case 'no_kernel': return 'No Kernel';
      default: return 'Kernel';
    }
  }

  ngOnInit(): void {
    this.workspaceService.getProfiles(this.analysisId).pipe(
      catchError(() => of([] as ComputeProfile[]))
    ).subscribe((profiles) => {
      this.profile = profiles[0] ?? null;
    });

    this.refreshStatus();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
    this.kernelPollSub?.unsubscribe();
    this.bridgeService.destroy();
  }

  launchWorkspace(): void {
    this.errorMessage = null;
    this.status = 'PENDING';
    this.statusMessage = 'Starting notebook server...';
    this.workspaceService.launchWorkspace(this.analysisId, this.profile?.id ?? 'exploratory').subscribe({
      next: () => this.startPolling(),
      error: (error) => {
        this.status = 'FAILED';
        this.errorMessage = error?.error?.message ?? 'Unable to launch workspace.';
      }
    });
  }

  terminateWorkspace(): void {
    this.stopKernelPolling();
    this.workspaceService.terminateWorkspace(this.analysisId).subscribe({
      next: () => {
        this.status = 'STOPPED';
        this.statusMessage = 'Workspace is stopped.';
        this.iframeUrl = null;
        this.kernelStatus = 'unknown';
        this.bridgeService.destroy();
      },
      error: () => {
        this.errorMessage = 'Failed to terminate workspace.';
      }
    });
  }

  retry(): void {
    this.launchWorkspace();
  }

  // Bridge toolbar actions
  async onIframeLoad(): Promise<void> {
    // Detect JupyterHub 403 (stale session for a different user) and force re-auth
    if (this.detectForbidden()) {
      return;
    }

    await this.bridgeService.initialize('jupyter-iframe');
    if (this.bridgeConnected) {
      this.reauthAttempted = false; // successful load resets the flag
      // Collapse sidebar for clean default view
      await this.bridgeService.execute('application:toggle-left-area');
      this.sidebarVisible = false;
      // Sync portal theme to notebook
      const jupyterTheme = this.currentTheme === 'dark' ? 'JupyterLab Dark' : 'JupyterLab Light';
      await this.bridgeService.execute('apputils:change-theme', { theme: jupyterTheme });
    }
  }

  /**
   * Detect a JupyterHub 403 in the iframe (caused by a stale session cookie
   * for a different user). If detected, redirect through /hub/logout to clear
   * the session, then JupyterHub OAuth will re-authenticate via Keycloak SSO
   * as the currently logged-in portal user.
   */
  private detectForbidden(): boolean {
    if (this.reauthAttempted || !this.originalWorkspaceUrl) {
      return false;
    }
    try {
      const iframe = document.getElementById('jupyter-iframe') as HTMLIFrameElement | null;
      if (!iframe?.contentDocument) {
        return false;
      }
      const bodyText = iframe.contentDocument.body?.innerText ?? '';
      if (bodyText.includes('403') && bodyText.includes('Forbidden')) {
        this.reauthAttempted = true;
        const next = encodeURIComponent(this.originalWorkspaceUrl);
        this.iframeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
          `/hub/logout?next=${next}`
        );
        return true;
      }
    } catch {
      // Cross-origin or iframe not ready — ignore
    }
    return false;
  }

  async toggleSidebar(): Promise<void> {
    await this.bridgeService.execute('application:toggle-left-area');
    this.sidebarVisible = !this.sidebarVisible;
  }

  async toggleTheme(): Promise<void> {
    this.currentTheme = this.currentTheme === 'light' ? 'dark' : 'light';
    const jupyterTheme = this.currentTheme === 'dark' ? 'JupyterLab Dark' : 'JupyterLab Light';
    await this.bridgeService.execute('apputils:change-theme', { theme: jupyterTheme });
  }

  async runAll(): Promise<void> {
    await this.bridgeService.execute('notebook:run-all-cells');
  }

  async saveNotebook(): Promise<void> {
    await this.bridgeService.execute('notebook:save');
  }

  private refreshStatus(): void {
    this.workspaceService.getStatus(this.analysisId).subscribe({
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
    this.pollSub = this.workspaceService.watchStatusUntilStable(this.analysisId, 3000).subscribe({
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
    this.workspaceService.getWorkspaceUrl(this.analysisId).subscribe({
      next: (response) => {
        this.originalWorkspaceUrl = response.url;
        this.reauthAttempted = false;
        this.iframeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(response.url);
        this.startKernelPolling();
      },
      error: () => {
        this.status = 'FAILED';
        this.errorMessage = 'Workspace URL is unavailable.';
      }
    });
  }

  private startKernelPolling(): void {
    this.stopKernelPolling();
    this.kernelPollSub = interval(5000).pipe(
      switchMap(() => this.workspaceService.getKernelStatus(this.analysisId))
    ).subscribe((status) => {
      if (status === 'idle' || status === 'busy' || status === 'disconnected' || status === 'no_kernel') {
        this.kernelStatus = status;
      } else {
        this.kernelStatus = 'unknown';
      }
    });
  }

  private stopKernelPolling(): void {
    this.kernelPollSub?.unsubscribe();
    this.kernelPollSub = undefined;
  }

  private applyStatus(status: WorkspaceStatus): void {
    this.status = status.status;
    this.statusMessage = status.message ?? `Workspace status: ${status.status}`;
    if (status.status === 'STOPPED') {
      this.iframeUrl = null;
      this.stopKernelPolling();
    }
  }
}
