import { NgFor, NgIf } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { catchError, of } from 'rxjs';
import { ExperimentInfo, ExperimentService } from '../../core/services/experiment.service';

@Component({
  selector: 'app-experiments',
  standalone: true,
  imports: [NgIf, NgFor],
  templateUrl: './experiments.component.html',
  styleUrl: './experiments.component.scss'
})
export class ExperimentsComponent implements OnInit {
  private readonly experimentService = inject(ExperimentService);
  private readonly sanitizer = inject(DomSanitizer);

  loadingIframe = true;
  loadingExperiments = true;
  errorMessage: string | null = null;
  iframeUrl: SafeResourceUrl | null = null;
  experiments: ExperimentInfo[] = [];
  selectedExperimentId: string | null = null;

  private trackingBaseUrl: string | null = null;

  ngOnInit(): void {
    this.loadTrackingUrl();
    this.loadExperiments();
  }

  retry(): void {
    this.errorMessage = null;
    this.loadTrackingUrl();
    this.loadExperiments();
  }

  selectExperiment(experiment: ExperimentInfo): void {
    this.selectedExperimentId = experiment.experimentId;
    this.applyIframeUrl(experiment.experimentId);
  }

  private loadTrackingUrl(): void {
    this.loadingIframe = true;
    this.experimentService.getTrackingUrl().subscribe({
      next: (response) => {
        this.trackingBaseUrl = response.url;
        this.applyIframeUrl(this.selectedExperimentId);
        this.loadingIframe = false;
      },
      error: () => {
        this.loadingIframe = false;
        this.errorMessage = 'MLflow UI is unavailable.';
      }
    });
  }

  private loadExperiments(): void {
    this.loadingExperiments = true;
    this.experimentService.listExperiments().pipe(
      catchError(() => {
        this.errorMessage = 'Experiment list is unavailable.';
        return of([] as ExperimentInfo[]);
      })
    ).subscribe((experiments) => {
      this.experiments = experiments;
      this.loadingExperiments = false;
    });
  }

  private applyIframeUrl(experimentId: string | null): void {
    if (!this.trackingBaseUrl) {
      this.iframeUrl = null;
      return;
    }

    const baseUrl = this.trackingBaseUrl.replace(/\/+$/, '');
    const url = experimentId
      ? `${baseUrl}/#/experiments/${encodeURIComponent(experimentId)}`
      : baseUrl;
    this.iframeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
  }
}
