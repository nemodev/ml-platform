import { NgIf } from '@angular/common';
import { Component, Input, OnInit, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { catchError, of } from 'rxjs';
import { ExperimentInfo, ExperimentService } from '../../core/services/experiment.service';

@Component({
  selector: 'app-experiments',
  standalone: true,
  imports: [NgIf],
  templateUrl: './experiments.component.html',
  styleUrl: './experiments.component.scss'
})
export class ExperimentsComponent implements OnInit {
  private readonly experimentService = inject(ExperimentService);
  private readonly sanitizer = inject(DomSanitizer);

  @Input() analysisId!: string;

  loadingIframe = true;
  errorMessage: string | null = null;
  iframeUrl: SafeResourceUrl | null = null;

  private experiments: ExperimentInfo[] = [];
  private selectedExperimentId: string | null = null;

  private trackingBaseUrl: string | null = null;

  ngOnInit(): void {
    this.ensureMlflowDefaults();
    this.loadTrackingUrl();
    this.loadExperiments();
  }

  retry(): void {
    this.errorMessage = null;
    this.loadTrackingUrl();
    this.loadExperiments();
  }

  onMlflowIframeLoad(): void {
    this.configureMlflowIframe('mlflow-experiments-iframe');
  }

  private loadTrackingUrl(): void {
    this.loadingIframe = true;
    this.experimentService.getTrackingUrl(this.analysisId).subscribe({
      next: (response) => {
        this.trackingBaseUrl = response.url;
        // Don't load iframe yet — wait for experiments to auto-select
        if (this.selectedExperimentId) {
          this.applyIframeUrl(this.selectedExperimentId);
        }
        this.loadingIframe = false;
      },
      error: () => {
        this.loadingIframe = false;
        this.errorMessage = 'MLflow UI is unavailable.';
      }
    });
  }

  private loadExperiments(): void {
    this.experimentService.listExperiments(this.analysisId).pipe(
      catchError(() => of([] as ExperimentInfo[]))
    ).subscribe((experiments) => {
      this.experiments = experiments;
      // Auto-select the first experiment if none selected
      if (!this.selectedExperimentId && experiments.length > 0) {
        this.selectedExperimentId = experiments[0].experimentId;
        this.applyIframeUrl(this.selectedExperimentId);
      }
    });
  }

  private applyIframeUrl(experimentId: string | null): void {
    if (!this.trackingBaseUrl) {
      this.iframeUrl = null;
      return;
    }

    const baseUrl = this.trackingBaseUrl.replace(/\/+$/, '');
    const url = experimentId
      ? `${baseUrl}/#/experiments/${encodeURIComponent(experimentId)}`
      : `${baseUrl}/#/experiments`;
    this.iframeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }

  /**
   * Pre-set localStorage so the MLflow React app boots in Model Training mode
   * with Light theme. The parent page and MLflow iframe share the same origin,
   * so they share localStorage.
   */
  private ensureMlflowDefaults(): void {
    try {
      localStorage.setItem('mlflow.workflowType_v1', '"machine_learning"');
      localStorage.setItem('_mlflow_dark_mode_toggle_enabled', 'false');
      localStorage.setItem('databricks-dark-mode-pref', 'light');
    } catch {
      // localStorage unavailable — ignore
    }
  }

  /**
   * Configure the MLflow iframe after load: ensure Model Training mode is
   * active, force light theme, then hide the sidebar.
   */
  private configureMlflowIframe(iframeId: string): void {
    this.switchToModelTrainingIfNeeded(iframeId);
    this.forceLightTheme(iframeId);
    this.hideMlflowSidebar(iframeId);
    this.hideMlflowHeader(iframeId);
    // Retry after a short delay in case the mode switch triggers a
    // React re-render that re-creates the sidebar or resets the theme
    setTimeout(() => {
      this.forceLightTheme(iframeId);
      this.hideMlflowSidebar(iframeId);
      this.hideMlflowHeader(iframeId);
    }, 500);
  }

  /**
   * Force MLflow iframe to Light theme regardless of system prefers-color-scheme.
   * MLflow uses a `dark-mode` class on <body> and reads `databricks-dark-mode-pref`
   * from localStorage. We remove the class and update localStorage so subsequent
   * React renders stay light.
   */
  private forceLightTheme(iframeId: string): void {
    try {
      const iframe = document.getElementById(iframeId) as HTMLIFrameElement | null;
      const doc = iframe?.contentDocument;
      if (!doc) {
        return;
      }
      doc.body.classList.remove('dark-mode');
      localStorage.setItem('databricks-dark-mode-pref', 'light');
    } catch {
      // Cross-origin or iframe not ready — ignore
    }
  }

  /**
   * If the MLflow UI loaded in GenAI mode despite the localStorage hint,
   * programmatically click the "Model training" button in the sidebar.
   */
  private switchToModelTrainingIfNeeded(iframeId: string): void {
    try {
      const iframe = document.getElementById(iframeId) as HTMLIFrameElement | null;
      const doc = iframe?.contentDocument;
      if (!doc) {
        return;
      }
      const aside = doc.querySelector('aside');
      if (!aside) {
        return;
      }
      // Check if we're already in Model Training mode by looking for "Runs" link
      const links = aside.querySelectorAll('a');
      for (const link of Array.from(links)) {
        if (link.textContent?.trim() === 'Runs') {
          return; // already in Model Training mode
        }
      }
      // Not in Model Training mode — click the button
      const buttons = aside.querySelectorAll('div[role="button"]');
      for (const btn of Array.from(buttons)) {
        if (btn.textContent?.trim() === 'Model training') {
          (btn as HTMLElement).click();
          break;
        }
      }
    } catch {
      // Cross-origin or iframe not ready — ignore
    }
  }

  /**
   * Hide the MLflow sidebar by injecting CSS into the same-origin iframe.
   * Both the portal and MLflow are served through the same nginx, so
   * same-origin policy allows DOM access.
   */
  private hideMlflowSidebar(iframeId: string): void {
    try {
      const iframe = document.getElementById(iframeId) as HTMLIFrameElement | null;
      const doc = iframe?.contentDocument;
      if (!doc) {
        return;
      }
      if (doc.getElementById('ml-platform-mlflow-hide-sidebar')) {
        return;
      }
      const style = doc.createElement('style');
      style.id = 'ml-platform-mlflow-hide-sidebar';
      style.textContent = `
        /* Hide MLflow sidebar navigation */
        aside, [role="complementary"] {
          display: none !important;
        }
        /* Expand main content to fill available width */
        main {
          margin-left: 0 !important;
        }
      `;
      doc.head.appendChild(style);
    } catch {
      // Cross-origin or iframe not ready — ignore
    }
  }

  /**
   * Hide the MLflow header section (breadcrumbs, experiment title, action
   * buttons) by injecting CSS into the same-origin iframe. This gives the
   * embedded MLflow a clean, seamless appearance inside the platform.
   */
  private hideMlflowHeader(iframeId: string): void {
    try {
      const iframe = document.getElementById(iframeId) as HTMLIFrameElement | null;
      const doc = iframe?.contentDocument;
      if (!doc) {
        return;
      }
      if (doc.getElementById('ml-platform-mlflow-hide-header')) {
        return;
      }
      const style = doc.createElement('style');
      style.id = 'ml-platform-mlflow-hide-header';
      style.textContent = `
        /* Hide breadcrumb navigation (du-bois design system) */
        [class*="du-bois"][class*="breadcrumb"] {
          display: none !important;
        }
        /* Hide the header area: breadcrumbs + Runs title + action buttons.
           It is the first child of the main wrapper div. */
        main > div > div:first-child {
          display: none !important;
        }
        /* Hide the divider below the header */
        main > div > div:nth-child(2):empty {
          display: none !important;
        }
        /* Reclaim space: make content wrapper fill available height
           and allow the runs table area to stretch and scroll */
        main {
          height: 100vh !important;
          overflow: hidden !important;
        }
        main > div {
          height: 100% !important;
          display: flex !important;
          flex-direction: column !important;
        }
        main > div > div:last-child {
          flex: 1 1 auto !important;
          min-height: 0 !important;
          overflow: auto !important;
        }
      `;
      doc.head.appendChild(style);
    } catch {
      // Cross-origin or iframe not ready — ignore
    }
  }
}
