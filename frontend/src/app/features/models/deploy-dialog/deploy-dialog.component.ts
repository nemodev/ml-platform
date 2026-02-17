import { NgFor, NgIf } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ModelService, ModelVersionInfo } from '../../../core/services/model.service';
import { DeploymentInfo, ServingService } from '../../../core/services/serving.service';

@Component({
  selector: 'app-deploy-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  templateUrl: './deploy-dialog.component.html',
  styleUrl: './deploy-dialog.component.scss'
})
export class DeployDialogComponent implements OnChanges {
  private readonly modelService = inject(ModelService);
  private readonly servingService = inject(ServingService);

  @Input({ required: true }) modelName = '';
  @Output() close = new EventEmitter<void>();
  @Output() deployed = new EventEmitter<DeploymentInfo>();

  versions: ModelVersionInfo[] = [];
  selectedVersion: number | null = null;
  loadingVersions = true;
  submitting = false;
  errorMessage: string | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['modelName']) {
      this.loadVersions();
    }
  }

  deploy(): void {
    if (!this.modelName) {
      this.errorMessage = 'Model name is required.';
      return;
    }
    if (!this.selectedVersion) {
      this.errorMessage = 'Please select a version to deploy.';
      return;
    }

    this.submitting = true;
    this.errorMessage = null;
    this.servingService.deployModel({
      modelName: this.modelName,
      modelVersion: this.selectedVersion
    }).subscribe({
      next: (deployment) => {
        this.submitting = false;
        this.deployed.emit(deployment);
      },
      error: (error) => {
        this.submitting = false;
        this.errorMessage = error?.error?.message ?? 'Failed to create deployment.';
      }
    });
  }

  cancel(): void {
    this.close.emit();
  }

  private loadVersions(): void {
    if (!this.modelName) {
      this.versions = [];
      this.selectedVersion = null;
      this.loadingVersions = false;
      return;
    }

    this.loadingVersions = true;
    this.errorMessage = null;
    this.modelService.getModelVersions(this.modelName).subscribe({
      next: (versions) => {
        this.versions = [...versions].sort((a, b) => b.version - a.version);
        this.selectedVersion = this.versions[0]?.version ?? null;
        this.loadingVersions = false;
      },
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Unable to load model versions.';
        this.loadingVersions = false;
      }
    });
  }
}
