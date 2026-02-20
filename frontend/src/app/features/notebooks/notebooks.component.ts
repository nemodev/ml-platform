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

  status: WorkspaceState = 'STOPPED';
  statusMessage = 'Workspace is stopped.';
  errorMessage: string | null = null;
  profile: ComputeProfile | null = null;
  iframeUrl: SafeResourceUrl | null = null;

  // Bridge-driven toolbar state
  sidebarVisible = false;
  currentTheme: 'light' | 'dark' = 'light';
  kernelStatus: 'idle' | 'busy' | 'disconnected' | 'unknown' = 'unknown';

  private pollSub?: Subscription;

  get bridgeConnected(): boolean {
    return this.bridgeService.connectionState() === 'ready';
  }

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
    this.bridgeService.destroy();
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
    await this.bridgeService.initialize('jupyter-iframe');
    if (this.bridgeConnected) {
      // Collapse sidebar for clean default view
      await this.bridgeService.execute('application:toggle-left-area');
      this.sidebarVisible = false;
      // Sync portal theme to notebook
      const jupyterTheme = this.currentTheme === 'dark' ? 'JupyterLab Dark' : 'JupyterLab Light';
      await this.bridgeService.execute('apputils:change-theme', { theme: jupyterTheme });
    }
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
