import { DatePipe, NgFor, NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import {
  PipelineRunDetail,
  PipelineRunInfo,
  PipelineService,
  PipelineStatus
} from '../../core/services/pipeline.service';
import { RunDetailComponent } from './run-detail/run-detail.component';
import { TriggerDialogComponent } from './trigger-dialog/trigger-dialog.component';

@Component({
  selector: 'app-pipelines',
  standalone: true,
  imports: [NgIf, NgFor, DatePipe, RunDetailComponent, TriggerDialogComponent],
  templateUrl: './pipelines.component.html',
  styleUrl: './pipelines.component.scss'
})
export class PipelinesComponent implements OnInit, OnDestroy {
  private readonly pipelineService = inject(PipelineService);

  runs: PipelineRunInfo[] = [];
  selectedRun: PipelineRunDetail | null = null;
  selectedStatus: PipelineStatus | '' = '';

  loadingRuns = true;
  loadingDetail = false;
  showTriggerDialog = false;
  errorMessage: string | null = null;

  private refreshSub?: Subscription;

  ngOnInit(): void {
    this.loadRuns();
    this.refreshSub = timer(5000, 5000).subscribe(() => this.autoRefresh());
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  get hasActiveRuns(): boolean {
    return this.runs.some((run) => run.status === 'PENDING' || run.status === 'RUNNING');
  }

  openTriggerDialog(): void {
    this.showTriggerDialog = true;
  }

  onDialogClose(): void {
    this.showTriggerDialog = false;
  }

  onRunTriggered(run: PipelineRunInfo): void {
    this.showTriggerDialog = false;
    this.loadRuns();
    this.selectRun(run.id);
  }

  changeFilter(value: string): void {
    this.selectedStatus = (value as PipelineStatus | '') || '';
    this.loadRuns();
  }

  selectRun(runId: string): void {
    this.loadingDetail = true;
    this.pipelineService.getRunDetail(runId).subscribe({
      next: (run) => {
        this.selectedRun = run;
        this.loadingDetail = false;
      },
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Unable to load pipeline run detail.';
        this.loadingDetail = false;
      }
    });
  }

  refreshSelectedRun(): void {
    if (!this.selectedRun) {
      return;
    }
    this.selectRun(this.selectedRun.id);
  }

  private autoRefresh(): void {
    if (!this.hasActiveRuns) {
      return;
    }
    this.loadRuns(false);
    if (this.selectedRun && (this.selectedRun.status === 'PENDING' || this.selectedRun.status === 'RUNNING')) {
      this.selectRun(this.selectedRun.id);
    }
  }

  private loadRuns(showLoading = true): void {
    if (showLoading) {
      this.loadingRuns = true;
    }
    this.errorMessage = null;

    const statusFilter = this.selectedStatus || undefined;
    this.pipelineService.listRuns(statusFilter, 50).subscribe({
      next: (runs) => {
        this.runs = runs;
        this.loadingRuns = false;
        if (this.selectedRun) {
          const stillExists = runs.find((run) => run.id === this.selectedRun?.id);
          if (!stillExists) {
            this.selectedRun = null;
          }
        }
      },
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Unable to load pipeline runs.';
        this.loadingRuns = false;
      }
    });
  }
}
