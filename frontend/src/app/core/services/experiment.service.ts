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
  private readonly baseUrl = `${environment.apiUrl}/experiments`;

  listExperiments(): Observable<ExperimentInfo[]> {
    return this.http.get<ExperimentInfo[]>(this.baseUrl);
  }

  getExperiment(id: string): Observable<ExperimentDetail> {
    return this.http.get<ExperimentDetail>(`${this.baseUrl}/${id}`);
  }

  getRuns(experimentId: string): Observable<RunInfo[]> {
    return this.http.get<RunInfo[]>(`${this.baseUrl}/${experimentId}/runs`);
  }

  getTrackingUrl(): Observable<TrackingUrl> {
    return this.http.get<TrackingUrl>(`${this.baseUrl}/tracking-url`);
  }

  createExperiment(name: string): Observable<ExperimentInfo> {
    return this.http.post<ExperimentInfo>(this.baseUrl, { name });
  }
}
