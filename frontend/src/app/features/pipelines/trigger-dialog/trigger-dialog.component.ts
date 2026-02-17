import { NgFor, NgIf } from '@angular/common';
import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  NotebookInfo,
  PipelineRunInfo,
  PipelineService,
  TriggerPipelineRequest
} from '../../../core/services/pipeline.service';

interface ParameterEntry {
  key: string;
  value: string;
}

@Component({
  selector: 'app-trigger-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  templateUrl: './trigger-dialog.component.html',
  styleUrl: './trigger-dialog.component.scss'
})
export class TriggerDialogComponent implements OnInit {
  private readonly pipelineService = inject(PipelineService);

  @Output() close = new EventEmitter<void>();
  @Output() triggered = new EventEmitter<PipelineRunInfo>();

  notebooks: NotebookInfo[] = [];
  selectedNotebook = '';
  enableSpark = false;
  loadingNotebooks = true;
  submitting = false;
  errorMessage: string | null = null;
  parameters: ParameterEntry[] = [{ key: '', value: '' }];

  ngOnInit(): void {
    this.pipelineService.listNotebooks().subscribe({
      next: (notebooks) => {
        this.notebooks = notebooks;
        this.selectedNotebook = notebooks[0]?.path ?? '';
        this.loadingNotebooks = false;
      },
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Unable to load notebooks.';
        this.loadingNotebooks = false;
      }
    });
  }

  addParameter(): void {
    this.parameters.push({ key: '', value: '' });
  }

  removeParameter(index: number): void {
    this.parameters.splice(index, 1);
    if (this.parameters.length === 0) {
      this.parameters.push({ key: '', value: '' });
    }
  }

  trigger(): void {
    if (!this.selectedNotebook) {
      this.errorMessage = 'Please select a notebook to run.';
      return;
    }

    const payload: TriggerPipelineRequest = {
      notebookPath: this.selectedNotebook,
      enableSpark: this.enableSpark,
      parameters: this.toParametersMap()
    };

    this.errorMessage = null;
    this.submitting = true;
    this.pipelineService.triggerPipeline(payload).subscribe({
      next: (run) => {
        this.submitting = false;
        this.triggered.emit(run);
      },
      error: (error) => {
        this.submitting = false;
        this.errorMessage = error?.error?.message ?? 'Failed to trigger pipeline.';
      }
    });
  }

  cancel(): void {
    this.close.emit();
  }

  private toParametersMap(): Record<string, string> {
    const params: Record<string, string> = {};
    this.parameters.forEach((entry) => {
      const key = entry.key.trim();
      if (!key) {
        return;
      }
      params[key] = entry.value;
    });
    return params;
  }
}
