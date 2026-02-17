import { DatePipe, NgFor, NgIf } from '@angular/common';
import { Component, OnInit, ViewChild, inject } from '@angular/core';
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

  @ViewChild(DeploymentsComponent) deploymentsComponent?: DeploymentsComponent;

  models: RegisteredModelInfo[] = [];
  loadingModels = true;
  errorMessage: string | null = null;
  dialogModelName: string | null = null;

  ngOnInit(): void {
    this.loadModels();
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
}
