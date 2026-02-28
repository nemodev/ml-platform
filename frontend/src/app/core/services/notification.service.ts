import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface NotificationDto {
  id: string;
  type: 'BUILD_SUCCEEDED' | 'BUILD_FAILED';
  message: string;
  resourceId: string;
  resourceName: string;
  timestamp: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/notifications`;

  private lastPolledAt: string = new Date(Date.now() - 60000).toISOString();
  private readonly seenIds = new Set<string>();
  private readonly notificationsSubject = new BehaviorSubject<NotificationDto[]>([]);

  readonly notifications$: Observable<NotificationDto[]> = this.notificationsSubject.asObservable();

  pollNotifications(): void {
    this.http.get<NotificationDto[]>(`${this.baseUrl}?since=${encodeURIComponent(this.lastPolledAt)}`).subscribe({
      next: (notifications) => {
        const newOnes = notifications.filter((n) => !this.seenIds.has(n.id));
        if (newOnes.length > 0) {
          for (const n of newOnes) {
            this.seenIds.add(n.id);
          }
          const current = this.notificationsSubject.value;
          this.notificationsSubject.next([...newOnes, ...current]);
        }
        this.lastPolledAt = new Date().toISOString();
      },
      error: () => {
        // Silently ignore polling errors
      }
    });
  }

  clearNotification(id: string): void {
    const current = this.notificationsSubject.value;
    this.notificationsSubject.next(current.filter((n) => n.id !== id));
  }

  clearAll(): void {
    this.notificationsSubject.next([]);
  }
}
