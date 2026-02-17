import { DatePipe, JsonPipe, NgIf } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { PipelineRunDetail, PipelineService } from '../../../core/services/pipeline.service';

@Component({
  selector: 'app-run-detail',
  standalone: true,
  imports: [NgIf, DatePipe, JsonPipe],
  templateUrl: './run-detail.component.html',
  styleUrl: './run-detail.component.scss'
})
export class RunDetailComponent {
  private readonly pipelineService = inject(PipelineService);

  @Input({ required: true }) run: PipelineRunDetail | null = null;
  @Output() refresh = new EventEmitter<void>();

  loadingOutput = false;
  outputError: string | null = null;

  get canDownloadOutput(): boolean {
    if (!this.run?.outputPath) {
      return false;
    }
    return this.run.status === 'SUCCEEDED' || this.run.status === 'FAILED';
  }

  requestRefresh(): void {
    this.refresh.emit();
  }

  viewOutput(): void {
    if (!this.run) {
      return;
    }

    this.loadingOutput = true;
    this.outputError = null;

    this.pipelineService.getOutputUrl(this.run.id).subscribe({
      next: (response) => {
        this.loadingOutput = false;
        window.open(response.url, '_blank', 'noopener');
      },
      error: (error) => {
        this.loadingOutput = false;
        this.outputError = error?.error?.message ?? 'Output notebook is unavailable.';
      }
    });
  }
}
