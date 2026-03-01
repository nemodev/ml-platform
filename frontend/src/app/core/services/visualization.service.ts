import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface StreamlitFile {
  name: string;
  path: string;
  lastModified?: string;
}

export interface StreamlitFileList {
  files: StreamlitFile[];
}

export interface StreamlitStatus {
  status: 'stopped' | 'starting' | 'running' | 'errored';
  filePath?: string;
  url?: string;
  errorMessage?: string;
}

@Injectable({ providedIn: 'root' })
export class VisualizationService {
  private readonly http = inject(HttpClient);

  private baseUrl(analysisId: string): string {
    return `${environment.apiUrl}/analyses/${analysisId}/visualizations`;
  }

  getFiles(analysisId: string): Observable<StreamlitFileList> {
    return this.http.get<StreamlitFileList>(`${this.baseUrl(analysisId)}/files`);
  }

  startApp(analysisId: string, filePath: string): Observable<StreamlitStatus> {
    return this.http.post<StreamlitStatus>(`${this.baseUrl(analysisId)}/start`, { filePath });
  }

  stopApp(analysisId: string): Observable<StreamlitStatus> {
    return this.http.post<StreamlitStatus>(`${this.baseUrl(analysisId)}/stop`, {});
  }

  getStatus(analysisId: string): Observable<StreamlitStatus> {
    return this.http.get<StreamlitStatus>(`${this.baseUrl(analysisId)}/status`);
  }
}
