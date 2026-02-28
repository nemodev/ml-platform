import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { Observable } from 'rxjs';

export interface NotebookImageDto {
  id: string;
  name: string;
  pythonVersion: string;
  packageCount: number;
  status: 'PENDING' | 'BUILDING' | 'READY' | 'FAILED';
  imageReference: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface NotebookImageDetailDto extends NotebookImageDto {
  packages: string | null;
  extraPipIndexUrl: string | null;
  errorMessage: string | null;
  latestBuild: ImageBuildDto | null;
}

export interface ImageBuildDto {
  id: string;
  status: 'QUEUED' | 'BUILDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  progressStage: string | null;
  imageReference: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export interface ImageBuildDetailDto extends ImageBuildDto {
  elapsedSeconds: number;
  notebookImageId: string;
  notebookImageName: string;
}

export interface CreateNotebookImageRequest {
  name: string;
  pythonVersion: string;
  packages?: string;
  extraPipIndexUrl?: string;
}

export interface UpdateNotebookImageRequest {
  name?: string;
  pythonVersion?: string;
  packages?: string;
  extraPipIndexUrl?: string;
}

@Injectable({ providedIn: 'root' })
export class NotebookImageService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/notebook-images`;

  listImages(): Observable<NotebookImageDto[]> {
    return this.http.get<NotebookImageDto[]>(this.baseUrl);
  }

  createImage(request: CreateNotebookImageRequest): Observable<NotebookImageDto> {
    return this.http.post<NotebookImageDto>(this.baseUrl, request);
  }

  getImage(imageId: string): Observable<NotebookImageDetailDto> {
    return this.http.get<NotebookImageDetailDto>(`${this.baseUrl}/${imageId}`);
  }

  updateImage(imageId: string, request: UpdateNotebookImageRequest): Observable<NotebookImageDto> {
    return this.http.put<NotebookImageDto>(`${this.baseUrl}/${imageId}`, request);
  }

  deleteImage(imageId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${imageId}`);
  }

  listPythonVersions(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/python-versions`);
  }

  triggerBuild(imageId: string): Observable<ImageBuildDto> {
    return this.http.post<ImageBuildDto>(`${this.baseUrl}/${imageId}/builds`, {});
  }

  listBuilds(imageId: string): Observable<ImageBuildDto[]> {
    return this.http.get<ImageBuildDto[]>(`${this.baseUrl}/${imageId}/builds`);
  }

  getBuild(imageId: string, buildId: string): Observable<ImageBuildDetailDto> {
    return this.http.get<ImageBuildDetailDto>(`${this.baseUrl}/${imageId}/builds/${buildId}`);
  }

  getBuildLogs(imageId: string, buildId: string): Observable<string> {
    return this.http.get(`${this.baseUrl}/${imageId}/builds/${buildId}/logs`, { responseType: 'text' });
  }
}
