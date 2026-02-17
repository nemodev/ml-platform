import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type PipelineStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface PipelineRunInfo {
  id: string;
  notebookName: string;
  status: PipelineStatus;
  enableSpark: boolean;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

export interface PipelineRunDetail extends PipelineRunInfo {
  parameters: Record<string, string>;
  inputPath?: string;
  outputPath?: string;
  errorMessage?: string;
}

export interface PipelineOutputUrl {
  url: string;
  expiresAt: string;
}

export interface NotebookInfo {
  name: string;
  path: string;
  lastModified?: string;
  sizeBytes?: number;
}

export interface TriggerPipelineRequest {
  notebookPath: string;
  parameters?: Record<string, string>;
  enableSpark?: boolean;
}

@Injectable({ providedIn: 'root' })
export class PipelineService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/pipelines`;

  triggerPipeline(request: TriggerPipelineRequest): Observable<PipelineRunInfo> {
    return this.http.post<PipelineRunInfo>(this.baseUrl, request);
  }

  listRuns(status?: PipelineStatus, limit = 20): Observable<PipelineRunInfo[]> {
    let params = new HttpParams().set('limit', String(limit));
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<PipelineRunInfo[]>(this.baseUrl, { params });
  }

  getRunDetail(runId: string): Observable<PipelineRunDetail> {
    return this.http.get<PipelineRunDetail>(`${this.baseUrl}/${runId}`);
  }

  getOutputUrl(runId: string): Observable<PipelineOutputUrl> {
    return this.http.get<PipelineOutputUrl>(`${this.baseUrl}/${runId}/output`);
  }

  listNotebooks(): Observable<NotebookInfo[]> {
    return this.http.get<NotebookInfo[]>(`${this.baseUrl}/notebooks`);
  }
}
