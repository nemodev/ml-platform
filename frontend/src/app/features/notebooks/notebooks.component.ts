import { NgFor, NgIf } from '@angular/common';
import { ChangeDetectorRef, Component, Input, OnDestroy, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Subscription, interval, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import {
  ComputeProfile,
  WorkspaceMetrics,
  WorkspaceService,
  WorkspaceState,
  WorkspaceStatus
} from '../../core/services/workspace.service';
import { NotebookImageDto, NotebookImageService } from '../../core/services/notebook-image.service';
import { JupyterBridgeService } from '../../core/services/jupyter-bridge.service';

@Component({
  selector: 'app-notebooks',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  templateUrl: './notebooks.component.html',
  styleUrl: './notebooks.component.scss'
})
export class NotebooksComponent implements OnInit, OnDestroy {
  private readonly workspaceService = inject(WorkspaceService);
  private readonly notebookImageService = inject(NotebookImageService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly cdr = inject(ChangeDetectorRef);
  readonly bridgeService = inject(JupyterBridgeService);

  @Input() analysisId!: string;

  status: WorkspaceState = 'STOPPED';
  statusMessage = 'Workspace is stopped.';
  errorMessage: string | null = null;
  iframeUrl: SafeResourceUrl | null = null;

  // Profile selection
  profiles: ComputeProfile[] = [];
  selectedProfileId = '';
  activeProfileId: string | null = null;
  switchingProfile = false;

  get selectedProfile(): ComputeProfile | null {
    return this.profiles.find(p => p.id === this.selectedProfileId) ?? null;
  }

  // Custom image selection
  customImages: NotebookImageDto[] = [];
  selectedImageId = '';
  activeImageId: string | null = null;
  activeImageName: string | null = null;
  switchingImage = false;

  // Bridge-driven toolbar state
  sidebarVisible = false;
  headerVisible = true;
  currentTheme: 'light' | 'dark' = 'light';
  kernelStatus: 'idle' | 'busy' | 'disconnected' | 'unknown' | 'no_kernel' = 'unknown';
  lineNumbersVisible = true;

  // Resource metrics
  metrics: WorkspaceMetrics | null = null;

  // Command palette state
  commandPaletteOpen = false;
  allCommands: string[] = [];
  filteredCommands: string[] = [];
  commandFilter = '';
  commandPaletteLoading = false;
  lastExecutedCommand: string | null = null;

  private pollSub?: Subscription;
  private kernelPollSub?: Subscription;
  private metricsPollSub?: Subscription;
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
      this.profiles = profiles;
      const defaultProfile = profiles.find(p => p.isDefault) ?? profiles[0];
      if (defaultProfile) {
        this.selectedProfileId = defaultProfile.id;
      }
    });

    this.notebookImageService.listImages().pipe(
      catchError(() => of([] as NotebookImageDto[]))
    ).subscribe((images) => {
      this.customImages = images.filter((img) => img.status === 'READY');
    });

    this.refreshStatus();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
    this.kernelPollSub?.unsubscribe();
    this.metricsPollSub?.unsubscribe();
    this.bridgeService.destroy();
  }

  launchWorkspace(): void {
    this.errorMessage = null;
    this.status = 'PENDING';
    this.statusMessage = 'Starting notebook server...';
    const imageId = this.selectedImageId || undefined;
    this.workspaceService.launchWorkspace(this.analysisId, this.selectedProfileId || 'exploratory', imageId).subscribe({
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

  onProfileChange(newProfileId: string): void {
    if (this.status === 'STOPPED' || this.status === 'FAILED') {
      this.selectedProfileId = newProfileId;
      return;
    }
    // Running state — handled in US2 (T010)
    if (newProfileId === this.activeProfileId) {
      return;
    }
    const currentProfile = this.profiles.find(p => p.id === this.activeProfileId);
    const newProfile = this.profiles.find(p => p.id === newProfileId);
    const confirmed = window.confirm(
      `Switching from "${currentProfile?.name || 'Current'}" to "${newProfile?.name || 'New'}" will restart your workspace.\n\n` +
      `All running kernels will be interrupted and in-memory state will be lost.\n` +
      `Your saved notebooks and files will be preserved.\n\nContinue?`
    );
    if (!confirmed) {
      this.selectedProfileId = '';
      this.cdr.detectChanges();
      this.selectedProfileId = this.activeProfileId ?? '';
      return;
    }
    this.switchingProfile = true;
    this.selectedProfileId = newProfileId;
    this.stopKernelPolling();
    this.workspaceService.terminateWorkspace(this.analysisId).subscribe({
      next: () => {
        this.iframeUrl = null;
        this.kernelStatus = 'unknown';
        this.bridgeService.destroy();
        this.status = 'PENDING';
        this.statusMessage = 'Restarting workspace with new resource profile...';
        const imageId = this.selectedImageId || undefined;
        this.workspaceService.launchWorkspace(this.analysisId, this.selectedProfileId || 'exploratory', imageId).subscribe({
          next: () => {
            this.switchingProfile = false;
            this.startPolling();
          },
          error: (error) => {
            this.switchingProfile = false;
            this.status = 'FAILED';
            this.errorMessage = error?.error?.message ?? 'Unable to relaunch workspace with new profile.';
          }
        });
      },
      error: () => {
        this.switchingProfile = false;
        this.selectedProfileId = this.activeProfileId ?? '';
        this.errorMessage = 'Failed to terminate workspace for profile switch.';
      }
    });
  }

  onImageChange(newImageId: string): void {
    // If workspace is not running, just update the selection for next launch
    if (this.status === 'STOPPED' || this.status === 'FAILED') {
      this.selectedImageId = newImageId;
      return;
    }
    // Workspace is running — confirm restart
    const currentName = this.activeImageName || 'Default Platform Image';
    const newName = this.resolveImageName(newImageId);
    const confirmed = window.confirm(
      `Switching from "${currentName}" to "${newName}" will restart your workspace.\n\n` +
      `All your work is saved and will be available after restart.\n\nContinue?`
    );
    if (!confirmed) {
      // Force revert: temporarily clear then restore to trigger Angular change detection
      this.selectedImageId = '';
      this.cdr.detectChanges();
      this.selectedImageId = this.activeImageId ?? '';
      return;
    }
    this.switchingImage = true;
    this.selectedImageId = newImageId;
    // Terminate then relaunch with new image
    this.stopKernelPolling();
    this.workspaceService.terminateWorkspace(this.analysisId).subscribe({
      next: () => {
        this.iframeUrl = null;
        this.kernelStatus = 'unknown';
        this.bridgeService.destroy();
        this.status = 'PENDING';
        this.statusMessage = 'Restarting workspace with new image...';
        const imageId = this.selectedImageId || undefined;
        this.workspaceService.launchWorkspace(this.analysisId, this.selectedProfileId || 'exploratory', imageId).subscribe({
          next: () => {
            this.switchingImage = false;
            this.startPolling();
          },
          error: (error) => {
            this.switchingImage = false;
            this.status = 'FAILED';
            this.errorMessage = error?.error?.message ?? 'Unable to relaunch workspace with new image.';
          }
        });
      },
      error: () => {
        this.switchingImage = false;
        this.selectedImageId = this.activeImageId ?? '';
        this.errorMessage = 'Failed to terminate workspace for image switch.';
      }
    });
  }

  private resolveImageName(imageId: string): string {
    if (!imageId) return 'Default Platform Image';
    const img = this.customImages.find(i => i.id === imageId);
    return img ? `${img.name} (Python ${img.pythonVersion})` : 'Custom Image';
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
      // Hide JupyterLab top header for clean embedded view
      await this.bridgeService.execute('application:toggle-header');
      this.headerVisible = false;
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

  async interruptKernel(): Promise<void> {
    await this.bridgeService.execute('notebook:interrupt-kernel');
  }

  async restartKernel(): Promise<void> {
    await this.bridgeService.execute('notebook:restart-kernel');
  }

  async clearAllOutputs(): Promise<void> {
    await this.bridgeService.execute('notebook:clear-all-cell-outputs');
  }

  // Cell operations
  async insertCellBelow(): Promise<void> {
    await this.bridgeService.execute('notebook:insert-cell-below');
  }

  async insertCellAbove(): Promise<void> {
    await this.bridgeService.execute('notebook:insert-cell-above');
  }

  async moveCellUp(): Promise<void> {
    await this.bridgeService.execute('notebook:move-cell-up');
  }

  async moveCellDown(): Promise<void> {
    await this.bridgeService.execute('notebook:move-cell-down');
  }

  async undoCellAction(): Promise<void> {
    await this.bridgeService.execute('notebook:undo-cell-action');
  }

  async redoCellAction(): Promise<void> {
    await this.bridgeService.execute('notebook:redo-cell-action');
  }

  async toggleLineNumbers(): Promise<void> {
    await this.bridgeService.execute('notebook:toggle-all-cell-line-numbers');
    this.lineNumbersVisible = !this.lineNumbersVisible;
  }

  async toggleHeader(): Promise<void> {
    await this.bridgeService.execute('application:toggle-header');
    this.headerVisible = !this.headerVisible;
  }

  // Command palette
  async toggleCommandPalette(): Promise<void> {
    this.commandPaletteOpen = !this.commandPaletteOpen;
    if (this.commandPaletteOpen && this.allCommands.length === 0) {
      this.commandPaletteLoading = true;
      this.allCommands = await this.bridgeService.listCommands();
      this.allCommands.sort();
      this.filteredCommands = this.allCommands;
      this.commandPaletteLoading = false;
    }
  }

  filterCommands(event: Event): void {
    this.commandFilter = (event.target as HTMLInputElement).value;
    const q = this.commandFilter.toLowerCase();
    this.filteredCommands = q
      ? this.allCommands.filter((cmd) => cmd.toLowerCase().includes(q))
      : this.allCommands;
  }

  async executeCommand(commandId: string): Promise<void> {
    this.lastExecutedCommand = commandId;
    await this.bridgeService.execute(commandId);
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
        this.startMetricsPolling();
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

  private startMetricsPolling(): void {
    this.stopMetricsPolling();
    this.metricsPollSub = interval(15000).pipe(
      switchMap(() => this.workspaceService.getMetrics(this.analysisId).pipe(
        catchError(() => of(null as WorkspaceMetrics | null))
      ))
    ).subscribe((metrics) => {
      this.metrics = metrics;
    });
  }

  private stopMetricsPolling(): void {
    this.metricsPollSub?.unsubscribe();
    this.metricsPollSub = undefined;
    this.metrics = null;
  }

  formatMemoryGB(bytes: number | null): string {
    if (bytes == null) return '?';
    return (bytes / (1024 * 1024 * 1024)).toFixed(1);
  }

  private applyStatus(status: WorkspaceStatus): void {
    this.status = status.status;
    this.statusMessage = status.message ?? `Workspace status: ${status.status}`;
    // Track active profile from backend
    this.activeProfileId = status.profile ?? null;
    if (!this.switchingProfile && this.activeProfileId) {
      this.selectedProfileId = this.activeProfileId;
    }
    // Track active image from backend
    this.activeImageId = status.notebookImageId ?? null;
    this.activeImageName = status.notebookImageName ?? null;
    // Sync selector to active image (unless user is mid-switch)
    if (!this.switchingImage) {
      this.selectedImageId = this.activeImageId ?? '';
    }
    if (status.status === 'STOPPED') {
      this.iframeUrl = null;
      this.stopKernelPolling();
      this.stopMetricsPolling();
    }
  }
}
