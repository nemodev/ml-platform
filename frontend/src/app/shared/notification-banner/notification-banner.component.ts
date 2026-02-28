import { NgFor, NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { NotificationDto, NotificationService } from '../../core/services/notification.service';

@Component({
  selector: 'app-notification-banner',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink],
  templateUrl: './notification-banner.component.html',
  styleUrl: './notification-banner.component.scss'
})
export class NotificationBannerComponent implements OnInit, OnDestroy {
  private readonly notificationService = inject(NotificationService);

  notifications: NotificationDto[] = [];
  private sub?: Subscription;
  private autoTimers = new Map<string, ReturnType<typeof setTimeout>>();

  ngOnInit(): void {
    this.sub = this.notificationService.notifications$.subscribe((notifications) => {
      this.notifications = notifications;
      for (const n of notifications) {
        if (!this.autoTimers.has(n.id)) {
          this.autoTimers.set(n.id, setTimeout(() => this.dismiss(n.id), 10000));
        }
      }
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    for (const timer of this.autoTimers.values()) {
      clearTimeout(timer);
    }
  }

  dismiss(id: string): void {
    const timer = this.autoTimers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.autoTimers.delete(id);
    }
    this.notificationService.clearNotification(id);
  }

  typeClass(type: string): string {
    return type === 'BUILD_SUCCEEDED' ? 'success' : 'error';
  }
}
