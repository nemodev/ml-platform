import { DatePipe, NgFor, NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { DeploymentInfo, ServingService } from '../../../core/services/serving.service';
import { PredictDialogComponent } from '../predict-dialog/predict-dialog.component';

@Component({
  selector: 'app-deployments',
  standalone: true,
  imports: [NgIf, NgFor, DatePipe, PredictDialogComponent],
  templateUrl: './deployments.component.html',
  styleUrl: './deployments.component.scss'
})
export class DeploymentsComponent implements OnInit, OnDestroy {
  private readonly servingService = inject(ServingService);

  deployments: DeploymentInfo[] = [];
  loadingDeployments = true;
  errorMessage: string | null = null;
  selectedForPredict: DeploymentInfo | null = null;

  private refreshSub?: Subscription;

  ngOnInit(): void {
    this.refreshDeployments();
    this.refreshSub = timer(5000, 5000).subscribe(() => this.autoRefresh());
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  refreshDeployments(showLoading = true): void {
    if (showLoading) {
      this.loadingDeployments = true;
    }
    this.errorMessage = null;

    this.servingService.listDeployments().subscribe({
      next: (deployments) => {
        this.deployments = deployments;
        this.loadingDeployments = false;
        if (this.selectedForPredict) {
          const stillExists = deployments.some((item) => item.id === this.selectedForPredict?.id);
          if (!stillExists) {
            this.selectedForPredict = null;
          }
        }
      },
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Unable to load deployments.';
        this.loadingDeployments = false;
      }
    });
  }

  deleteDeployment(deployment: DeploymentInfo): void {
    if (!window.confirm(`Delete deployment ${deployment.endpointName}?`)) {
      return;
    }

    this.servingService.deleteDeployment(deployment.id).subscribe({
      next: () => this.refreshDeployments(),
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Failed to delete deployment.';
      }
    });
  }

  openPredictDialog(deployment: DeploymentInfo): void {
    this.selectedForPredict = deployment;
  }

  closePredictDialog(): void {
    this.selectedForPredict = null;
  }

  statusClass(status: DeploymentInfo['status']): string {
    return status.toLowerCase();
  }

  private autoRefresh(): void {
    if (!this.deployments.some((deployment) => deployment.status === 'DEPLOYING' || deployment.status === 'DELETING')) {
      return;
    }
    this.refreshDeployments(false);
  }
}
