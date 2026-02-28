import { NgFor, NgIf } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AnalysisInfo, AnalysisService } from '../../core/services/analysis.service';

@Component({
  selector: 'app-analyses',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  templateUrl: './analyses.component.html',
  styleUrl: './analyses.component.scss'
})
export class AnalysesComponent implements OnInit {
  private readonly analysisService = inject(AnalysisService);
  private readonly router = inject(Router);

  private static readonly AUTO_OPEN_KEY = 'analyses.autoOpened';

  analyses: AnalysisInfo[] = [];
  loading = true;
  errorMessage: string | null = null;

  showCreateForm = false;
  newName = '';
  newDescription = '';
  creating = false;

  ngOnInit(): void {
    this.loadAnalyses();
  }

  openAnalysis(analysis: AnalysisInfo): void {
    this.analysisService.selectAnalysis(analysis);
    this.router.navigate(['/analyses', analysis.id, 'notebooks']);
  }

  toggleCreateForm(): void {
    this.showCreateForm = !this.showCreateForm;
    if (!this.showCreateForm) {
      this.newName = '';
      this.newDescription = '';
    }
  }

  createAnalysis(): void {
    if (!this.newName.trim()) {
      return;
    }
    this.creating = true;
    this.analysisService.createAnalysis({
      name: this.newName.trim(),
      description: this.newDescription.trim() || undefined
    }).subscribe({
      next: (analysis) => {
        this.creating = false;
        this.showCreateForm = false;
        this.newName = '';
        this.newDescription = '';
        this.analyses = [analysis, ...this.analyses];
      },
      error: (err) => {
        this.creating = false;
        this.errorMessage = err?.error?.message ?? 'Failed to create analysis.';
      }
    });
  }

  deleteAnalysis(analysis: AnalysisInfo, event: Event): void {
    event.stopPropagation();
    const confirmed = window.confirm(
      `Delete "${analysis.name}"?\n\nThis will permanently remove the analysis and all associated workspace records. Your saved notebooks will remain in storage.`
    );
    if (!confirmed) {
      return;
    }
    this.analysisService.deleteAnalysis(analysis.id).subscribe({
      next: () => {
        this.analyses = this.analyses.filter(a => a.id !== analysis.id);
      },
      error: (err) => {
        this.errorMessage = err?.error?.message ?? 'Failed to delete analysis.';
      }
    });
  }

  private loadAnalyses(): void {
    this.loading = true;
    this.analysisService.listAnalyses().subscribe({
      next: (analyses) => {
        this.analyses = analyses;
        this.loading = false;
        // Auto-open once per tab session when the user has exactly one analysis
        if (analyses.length === 1 && !sessionStorage.getItem(AnalysesComponent.AUTO_OPEN_KEY)) {
          sessionStorage.setItem(AnalysesComponent.AUTO_OPEN_KEY, '1');
          this.openAnalysis(analyses[0]);
        }
      },
      error: () => {
        this.errorMessage = 'Unable to load analyses.';
        this.loading = false;
      }
    });
  }
}
