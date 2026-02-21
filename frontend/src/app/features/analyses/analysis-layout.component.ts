import { NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { AnalysisService } from '../../core/services/analysis.service';
import { NotebooksComponent } from '../notebooks/notebooks.component';
import { ExperimentsComponent } from '../experiments/experiments.component';

@Component({
  selector: 'app-analysis-layout',
  standalone: true,
  imports: [NgIf, RouterLink, RouterLinkActive, NotebooksComponent, ExperimentsComponent],
  template: `
    <nav class="analysis-tabs">
      <span class="analysis-label" *ngIf="analysisName">{{ analysisName }}</span>
      <span class="tab-separator" *ngIf="analysisName"></span>
      <a [routerLink]="['notebooks']" routerLinkActive="active">Notebooks</a>
      <a [routerLink]="['experiments']" routerLinkActive="active">Experiments</a>
    </nav>
    <!-- Notebooks: always in DOM (hidden when inactive) to preserve JupyterLab iframe state -->
    <div [style.display]="activeTab === 'notebooks' ? '' : 'none'">
      <app-notebooks *ngIf="analysisId" [analysisId]="analysisId"></app-notebooks>
    </div>
    <!-- Experiments: recreated on each visit for fresh MLflow data -->
    <app-experiments *ngIf="activeTab === 'experiments' && analysisId" [analysisId]="analysisId"></app-experiments>
  `,
  styles: [`
    .analysis-tabs {
      display: flex;
      align-items: center;
      gap: 0;
      border-bottom: 2px solid #e5e7eb;
      margin-bottom: 1rem;
    }

    .analysis-label {
      color: #111827;
      font-size: 0.9rem;
      font-weight: 600;
      padding: 0.55rem 0 0.55rem 0.2rem;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 260px;
    }

    .tab-separator {
      width: 1px;
      height: 1.1rem;
      background: #d1d5db;
      margin: 0 0.75rem;
      flex-shrink: 0;
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

  analysisName: string | null = null;
  analysisId: string | null = null;
  activeTab: 'notebooks' | 'experiments' = 'notebooks';

  private routerSub?: Subscription;

  ngOnInit(): void {
    this.analysisId = this.route.snapshot.params['analysisId'];

    // Determine initial active tab from current URL
    this.activeTab = this.router.url.includes('/experiments') ? 'experiments' : 'notebooks';

    // Sync active tab on future navigations
    this.routerSub = this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd)
    ).subscribe((e) => {
      this.activeTab = e.urlAfterRedirects.includes('/experiments') ? 'experiments' : 'notebooks';
    });

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
}
