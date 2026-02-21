import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface AnalysisInfo {
  id: string;
  name: string;
  description?: string;
  createdAt?: string;
}

export interface CreateAnalysisRequest {
  name: string;
  description?: string;
}

@Injectable({ providedIn: 'root' })
export class AnalysisService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/analyses`;

  private readonly currentAnalysis$ = new BehaviorSubject<AnalysisInfo | null>(null);
  readonly selectedAnalysis$ = this.currentAnalysis$.asObservable();

  listAnalyses(): Observable<AnalysisInfo[]> {
    return this.http.get<AnalysisInfo[]>(this.baseUrl);
  }

  createAnalysis(request: CreateAnalysisRequest): Observable<AnalysisInfo> {
    return this.http.post<AnalysisInfo>(this.baseUrl, request);
  }

  getAnalysis(id: string): Observable<AnalysisInfo> {
    return this.http.get<AnalysisInfo>(`${this.baseUrl}/${id}`);
  }

  deleteAnalysis(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  selectAnalysis(analysis: AnalysisInfo | null): void {
    this.currentAnalysis$.next(analysis);
  }

  get selectedAnalysis(): AnalysisInfo | null {
    return this.currentAnalysis$.getValue();
  }
}
