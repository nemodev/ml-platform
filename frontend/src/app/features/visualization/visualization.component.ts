import { NgFor, NgIf } from '@angular/common';
import { Component, Input, OnDestroy, OnInit, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import {
  StreamlitFile,
  StreamlitStatus,
  VisualizationService
} from '../../core/services/visualization.service';

@Component({
  selector: 'app-visualization',
  standalone: true,
  imports: [NgIf, NgFor],
  templateUrl: './visualization.component.html',
  styleUrl: './visualization.component.scss'
})
export class VisualizationComponent implements OnInit, OnDestroy {
  private readonly vizService = inject(VisualizationService);
  private readonly sanitizer = inject(DomSanitizer);

  @Input() analysisId!: string;
  @Input() workspaceRunning = false;

  files: StreamlitFile[] = [];
  selectedFile: StreamlitFile | null = null;
  streamlitUrl: SafeResourceUrl | null = null;

  state: 'loading-files' | 'no-files' | 'starting' | 'running' | 'errored' | 'workspace-stopped' = 'loading-files';
  errorMessage: string | null = null;
  startTime = 0;
  timedOut = false;

  private statusPollSub?: Subscription;
  private initialized = false;

  ngOnInit(): void {
    // Initialization deferred until workspaceRunning is set by parent
  }

  ngOnDestroy(): void {
    this.statusPollSub?.unsubscribe();
  }

  /** Called by parent when workspace status changes */
  checkWorkspace(): void {
    if (!this.workspaceRunning) {
      this.state = 'workspace-stopped';
      this.streamlitUrl = null;
      this.statusPollSub?.unsubscribe();
      return;
    }
    this.loadFiles();
  }

  private loadFiles(): void {
    this.state = 'loading-files';
    this.timedOut = false;
    this.vizService.getFiles(this.analysisId).subscribe({
      next: (res) => {
        this.files = res.files ?? [];
        if (this.files.length === 0) {
          this.state = 'no-files';
          return;
        }
        // Auto-start the first file (or keep current selection if it still exists)
        const current = this.selectedFile;
        const stillExists = current && this.files.some(f => f.path === current.path);
        if (!stillExists) {
          this.selectedFile = this.files[0];
        }
        this.startApp(this.selectedFile!.path);
      },
      error: (err) => {
        this.state = 'errored';
        this.errorMessage = err?.error?.message ?? 'Failed to scan for Streamlit files.';
      }
    });
  }

  onFileSelect(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const file = this.files.find(f => f.path === select.value);
    if (file && file.path !== this.selectedFile?.path) {
      this.selectedFile = file;
      this.startApp(file.path);
    }
  }

  private startApp(filePath: string): void {
    this.state = 'starting';
    this.streamlitUrl = null;
    this.errorMessage = null;
    this.timedOut = false;
    this.startTime = Date.now();

    this.vizService.startApp(this.analysisId, filePath).subscribe({
      next: (status) => {
        if (status.status === 'running' && status.url) {
          this.onRunning(status);
        } else {
          this.pollStatus();
        }
      },
      error: (err) => {
        this.state = 'errored';
        this.errorMessage = err?.error?.message ?? 'Failed to start Streamlit app.';
      }
    });
  }

  private pollStatus(): void {
    this.statusPollSub?.unsubscribe();
    this.statusPollSub = timer(1000, 2000).pipe(
      switchMap(() => this.vizService.getStatus(this.analysisId))
    ).subscribe({
      next: (status) => {
        if (status.status === 'running' && status.url) {
          this.statusPollSub?.unsubscribe();
          this.onRunning(status);
          return;
        }
        if (status.status === 'errored') {
          this.statusPollSub?.unsubscribe();
          this.state = 'errored';
          this.errorMessage = status.errorMessage ?? 'Streamlit app failed to start.';
          return;
        }
        // Check timeout (60 seconds)
        if (Date.now() - this.startTime > 60000) {
          this.statusPollSub?.unsubscribe();
          this.timedOut = true;
          this.state = 'errored';
          this.errorMessage = 'Streamlit app took too long to start.';
        }
      },
      error: () => {
        this.statusPollSub?.unsubscribe();
        this.state = 'errored';
        this.errorMessage = 'Lost connection to workspace.';
      }
    });
  }

  private onRunning(status: StreamlitStatus): void {
    this.state = 'running';
    this.streamlitUrl = this.sanitizer.bypassSecurityTrustResourceUrl(status.url!);
  }

  retry(): void {
    if (this.selectedFile) {
      this.startApp(this.selectedFile.path);
    } else {
      this.loadFiles();
    }
  }

  refreshFiles(): void {
    this.loadFiles();
  }
}
