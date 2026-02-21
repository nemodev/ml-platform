import { NgIf } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DeploymentInfo, PredictionResponse, ServingService } from '../../../core/services/serving.service';

@Component({
  selector: 'app-predict-dialog',
  standalone: true,
  imports: [NgIf, FormsModule],
  templateUrl: './predict-dialog.component.html',
  styleUrl: './predict-dialog.component.scss'
})
export class PredictDialogComponent {
  private readonly servingService = inject(ServingService);

  @Input({ required: true }) deployment!: DeploymentInfo;
  @Output() close = new EventEmitter<void>();

  requestJson = JSON.stringify({
    inputs: [
      {
        name: 'predict',
        shape: [1, 8],
        datatype: 'FP64',
        data: [[8.3252, 41.0, 6.984, 1.024, 322.0, 2.556, 37.88, -122.23]]
      }
    ],
    parameters: { content_type: 'np' }
  }, null, 2);

  submitting = false;
  errorMessage: string | null = null;
  responseJson: string | null = null;

  predict(): void {
    this.errorMessage = null;
    this.responseJson = null;

    let payload: { inputs: Array<Record<string, unknown>> };
    try {
      payload = JSON.parse(this.requestJson) as { inputs: Array<Record<string, unknown>> };
    } catch {
      this.errorMessage = 'Request must be valid JSON.';
      return;
    }

    if (!payload?.inputs || !Array.isArray(payload.inputs) || payload.inputs.length === 0) {
      this.errorMessage = 'Request JSON must contain a non-empty inputs array.';
      return;
    }

    this.submitting = true;
    this.servingService.predict(this.deployment.id, payload).subscribe({
      next: (response) => {
        this.submitting = false;
        this.responseJson = this.pretty(response);
      },
      error: (error) => {
        this.submitting = false;
        this.errorMessage = error?.error?.message ?? 'Prediction request failed.';
      }
    });
  }

  cancel(): void {
    this.close.emit();
  }

  private pretty(response: PredictionResponse): string {
    return JSON.stringify(response, null, 2);
  }
}
