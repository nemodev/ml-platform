import { DatePipe, NgFor, NgIf } from '@angular/common';
import { Component, OnInit, ViewChild, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ModelService, RegisteredModelInfo } from '../../core/services/model.service';
import { DeploymentInfo } from '../../core/services/serving.service';
import { DeployDialogComponent } from './deploy-dialog/deploy-dialog.component';
import { DeploymentsComponent } from './deployments/deployments.component';

@Component({
  selector: 'app-models',
  standalone: true,
  imports: [NgIf, NgFor, DatePipe, DeployDialogComponent, DeploymentsComponent],
  templateUrl: './models.component.html',
  styleUrl: './models.component.scss'
})
export class ModelsComponent implements OnInit {
  private readonly modelService = inject(ModelService);
  private readonly sanitizer = inject(DomSanitizer);

  @ViewChild(DeploymentsComponent) deploymentsComponent?: DeploymentsComponent;

  activeTab: 'registry' | 'endpoints' = 'registry';

  // Registry tab state
  registryIframeUrl: SafeResourceUrl | null = null;

  // Deployments tab state
  models: RegisteredModelInfo[] = [];
  loadingModels = true;
  errorMessage: string | null = null;
  dialogModelName: string | null = null;

  ngOnInit(): void {
    this.ensureMlflowDefaults();
    this.registryIframeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
      '/mlflow/#/models'
    );
  }

  switchTab(tab: 'registry' | 'endpoints'): void {
    this.activeTab = tab;
    if (tab === 'endpoints' && this.models.length === 0) {
      this.loadModels();
    }
  }

  onRegistryIframeLoad(): void {
    this.configureMlflowIframe('mlflow-registry-iframe');
  }

  refreshModels(): void {
    this.loadModels();
  }

  openDeployDialog(modelName: string): void {
    this.dialogModelName = modelName;
  }

  closeDeployDialog(): void {
    this.dialogModelName = null;
  }

  onDeployed(_deployment: DeploymentInfo): void {
    this.dialogModelName = null;
    this.deploymentsComponent?.refreshDeployments();
  }

  private loadModels(): void {
    this.loadingModels = true;
    this.errorMessage = null;

    this.modelService.listRegisteredModels().subscribe({
      next: (models) => {
        this.models = [...models].sort((a, b) => a.name.localeCompare(b.name));
        this.loadingModels = false;
      },
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Unable to load registered models.';
        this.loadingModels = false;
      }
    });
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
    setTimeout(() => {
      this.forceLightTheme(iframeId);
      this.hideMlflowSidebar(iframeId);
    }, 500);
  }

  /**
   * Force MLflow iframe to Light theme regardless of system prefers-color-scheme.
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
      const links = aside.querySelectorAll('a');
      for (const link of Array.from(links)) {
        if (link.textContent?.trim() === 'Runs') {
          return; // already in Model Training mode
        }
      }
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
}
