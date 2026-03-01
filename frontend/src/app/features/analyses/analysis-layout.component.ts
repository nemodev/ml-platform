import { NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, ViewChild, inject } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { AnalysisService } from '../../core/services/analysis.service';
import { WorkspaceService } from '../../core/services/workspace.service';
import { NotebooksComponent } from '../notebooks/notebooks.component';
import { ExperimentsComponent } from '../experiments/experiments.component';
import { VisualizationComponent } from '../visualization/visualization.component';

@Component({
  selector: 'app-analysis-layout',
  standalone: true,
  imports: [NgIf, RouterLink, RouterLinkActive, NotebooksComponent, ExperimentsComponent, VisualizationComponent],
  template: `
    <div class="analysis-header">
      <nav class="breadcrumb">
        <a routerLink="/analyses" class="breadcrumb-link">Analyses</a>
        <span class="breadcrumb-sep">/</span>
        <span class="breadcrumb-current" *ngIf="analysisName">{{ analysisName }}</span>
      </nav>
      <nav class="analysis-tabs">
        <a [routerLink]="['notebooks']" routerLinkActive="active">Notebooks</a>
        <a [routerLink]="['experiments']" routerLinkActive="active">Experiments</a>
        <a [routerLink]="['visualization']" routerLinkActive="active">Visualization</a>
      </nav>
    </div>
    <!-- Notebooks: always in DOM (hidden when inactive) to preserve JupyterLab iframe state -->
    <div [style.display]="activeTab === 'notebooks' ? '' : 'none'">
      <app-notebooks *ngIf="analysisId" [analysisId]="analysisId"></app-notebooks>
    </div>
    <!-- Experiments: recreated on each visit for fresh MLflow data -->
    <app-experiments *ngIf="activeTab === 'experiments' && analysisId" [analysisId]="analysisId"></app-experiments>
    <!-- Visualization: always in DOM (hidden when inactive) to preserve Streamlit iframe state -->
    <div [style.display]="activeTab === 'visualization' ? '' : 'none'">
      <app-visualization *ngIf="analysisId" [analysisId]="analysisId" [workspaceRunning]="workspaceRunning" #vizComponent></app-visualization>
    </div>
  `,
  styles: [`
    .analysis-header {
      border-bottom: 2px solid #e5e7eb;
      margin-bottom: 1rem;
    }

    .breadcrumb {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.4rem 0.2rem 0;
      font-size: 0.82rem;
    }

    .breadcrumb-link {
      color: #2563eb;
      text-decoration: none;
      font-weight: 500;
    }

    .breadcrumb-link:hover {
      text-decoration: underline;
    }

    .breadcrumb-sep {
      color: #9ca3af;
    }

    .breadcrumb-current {
      color: #111827;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 360px;
    }

    .analysis-tabs {
      display: flex;
      align-items: center;
      gap: 0;
      margin-top: 0.25rem;
    }

    .analysis-tabs a {
      color: #6b7280;
      cursor: pointer;
      font-size: 0.9rem;
      font-weight: 500;
      padding: 0.55rem 1.1rem;
      text-decoration: none;
      border-bottom: 2px solid transparent;
      margin-bottom: -2px;
      transition: color 0.15s, border-color 0.15s;
    }

    .analysis-tabs a:hover {
      color: #374151;
    }

    .analysis-tabs a.active {
      color: #2563eb;
      border-bottom-color: #2563eb;
    }
  `]
})
export class AnalysisLayoutComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly analysisService = inject(AnalysisService);
  private readonly workspaceService = inject(WorkspaceService);

  @ViewChild('vizComponent') vizComponent?: VisualizationComponent;

  analysisName: string | null = null;
  analysisId: string | null = null;
  activeTab: 'notebooks' | 'experiments' | 'visualization' = 'notebooks';
  workspaceRunning = false;

  private routerSub?: Subscription;
  private vizInitialized = false;

  ngOnInit(): void {
    this.analysisId = this.route.snapshot.params['analysisId'];

    // Determine initial active tab from current URL
    this.activeTab = this.resolveTab(this.router.url);

    // Sync active tab on future navigations
    this.routerSub = this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd)
    ).subscribe((e) => {
      const newTab = this.resolveTab(e.urlAfterRedirects);
      this.activeTab = newTab;
      if (newTab === 'visualization') {
        this.initVisualization();
      }
    });

    // If landing directly on visualization tab
    if (this.activeTab === 'visualization') {
      this.initVisualization();
    }

    // Load analysis name
    const cached = this.analysisService.selectedAnalysis;
    if (cached && cached.id === this.analysisId) {
      this.analysisName = cached.name;
      return;
    }

    this.analysisService.getAnalysis(this.analysisId!).subscribe({
      next: (analysis) => {
        this.analysisName = analysis.name;
        this.analysisService.selectAnalysis(analysis);
      },
      error: () => {
        this.analysisName = null;
      }
    });
  }

  ngOnDestroy(): void {
    this.routerSub?.unsubscribe();
  }

  private resolveTab(url: string): 'notebooks' | 'experiments' | 'visualization' {
    if (url.includes('/visualization')) return 'visualization';
    if (url.includes('/experiments')) return 'experiments';
    return 'notebooks';
  }

  private initVisualization(): void {
    if (!this.analysisId) return;
    this.workspaceService.getStatus(this.analysisId).subscribe({
      next: (status) => {
        this.workspaceRunning = status.status === 'RUNNING' || status.status === 'IDLE';
        // Trigger the visualization component to check workspace & load files
        setTimeout(() => this.vizComponent?.checkWorkspace(), 0);
      },
      error: () => {
        this.workspaceRunning = false;
        setTimeout(() => this.vizComponent?.checkWorkspace(), 0);
      }
    });
  }
}
