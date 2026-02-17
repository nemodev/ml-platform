import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface RegisteredModelInfo {
  name: string;
  latestVersion?: number;
  description?: string;
  lastUpdatedAt?: string;
}

export interface ModelVersionInfo {
  version: number;
  status: string;
  stage?: string;
  artifactUri?: string;
  runId?: string;
  createdAt?: string;
}

@Injectable({ providedIn: 'root' })
export class ModelService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/models`;

  listRegisteredModels(): Observable<RegisteredModelInfo[]> {
    return this.http.get<RegisteredModelInfo[]>(this.baseUrl);
  }

  getModelVersions(modelName: string): Observable<ModelVersionInfo[]> {
    return this.http.get<ModelVersionInfo[]>(`${this.baseUrl}/${encodeURIComponent(modelName)}/versions`);
  }
}
