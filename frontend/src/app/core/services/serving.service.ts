import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface DeployModelRequest {
  modelName: string;
  modelVersion: number;
}

export interface DeploymentInfo {
  id: string;
  modelName: string;
  modelVersion: number;
  endpointName: string;
  status: 'DEPLOYING' | 'READY' | 'FAILED' | 'DELETING' | 'DELETED';
  createdAt: string;
  readyAt?: string;
}

export interface DeploymentDetail extends DeploymentInfo {
  inferenceUrl?: string;
  storageUri?: string;
  errorMessage?: string;
}

export interface PredictionRequest {
  inputs: Array<Record<string, unknown>>;
}

export interface PredictionResponse {
  modelName?: string;
  modelVersion?: string;
  outputs: Array<Record<string, unknown>>;
}

@Injectable({ providedIn: 'root' })
export class ServingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/serving/deployments`;

  deployModel(request: DeployModelRequest): Observable<DeploymentInfo> {
    return this.http.post<DeploymentInfo>(this.baseUrl, request);
  }

  listDeployments(status?: DeploymentInfo['status']): Observable<DeploymentInfo[]> {
    let params = new HttpParams();
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<DeploymentInfo[]>(this.baseUrl, { params });
  }

  getDeployment(id: string): Observable<DeploymentDetail> {
    return this.http.get<DeploymentDetail>(`${this.baseUrl}/${id}`);
  }

  deleteDeployment(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  predict(id: string, request: PredictionRequest): Observable<PredictionResponse> {
    return this.http.post<PredictionResponse>(`${this.baseUrl}/${id}/predict`, request);
  }
}
