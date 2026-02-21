import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ExperimentInfo {
  experimentId: string;
  name: string;
  artifactLocation?: string;
  lifecycleStage: string;
  creationTime?: string;
  lastUpdateTime?: string;
}

export interface RunInfo {
  runId: string;
  experimentId: string;
  status: string;
  startTime?: string;
  endTime?: string;
  parameters: Record<string, string>;
  metrics: Record<string, number>;
  artifactUri?: string;
}

export interface ExperimentDetail extends ExperimentInfo {
  runs: RunInfo[];
}

export interface TrackingUrl {
  url: string;
}

@Injectable({ providedIn: 'root' })
export class ExperimentService {
  private readonly http = inject(HttpClient);

  private analysisExperimentsUrl(analysisId: string): string {
    return `${environment.apiUrl}/analyses/${analysisId}/experiments`;
  }

  listExperiments(analysisId: string): Observable<ExperimentInfo[]> {
    return this.http.get<ExperimentInfo[]>(this.analysisExperimentsUrl(analysisId));
  }

  getExperiment(analysisId: string, id: string): Observable<ExperimentDetail> {
    return this.http.get<ExperimentDetail>(`${this.analysisExperimentsUrl(analysisId)}/${id}`);
  }

  getRuns(analysisId: string, experimentId: string): Observable<RunInfo[]> {
    return this.http.get<RunInfo[]>(`${this.analysisExperimentsUrl(analysisId)}/${experimentId}/runs`);
  }

  getTrackingUrl(analysisId: string): Observable<TrackingUrl> {
    return this.http.get<TrackingUrl>(`${this.analysisExperimentsUrl(analysisId)}/tracking-url`);
  }

  createExperiment(analysisId: string, name: string): Observable<ExperimentInfo> {
    return this.http.post<ExperimentInfo>(this.analysisExperimentsUrl(analysisId), { name });
  }
}
