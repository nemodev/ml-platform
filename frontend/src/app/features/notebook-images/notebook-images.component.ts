import { DatePipe, NgFor, NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import {
  NotebookImageDto,
  NotebookImageService
} from '../../core/services/notebook-image.service';
import { CreateImageDialogComponent } from './create-image-dialog/create-image-dialog.component';

@Component({
  selector: 'app-notebook-images',
  standalone: true,
  imports: [NgIf, NgFor, DatePipe, RouterLink, CreateImageDialogComponent],
  templateUrl: './notebook-images.component.html',
  styleUrl: './notebook-images.component.scss'
})
export class NotebookImagesComponent implements OnInit, OnDestroy {
  private readonly notebookImageService = inject(NotebookImageService);

  images: NotebookImageDto[] = [];
  loading = true;
  errorMessage: string | null = null;
  showCreateDialog = false;

  private refreshSub?: Subscription;

  ngOnInit(): void {
    this.loadImages();
    this.refreshSub = timer(5000, 5000).subscribe(() => this.autoRefresh());
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  loadImages(showLoading = true): void {
    if (showLoading) {
      this.loading = true;
    }
    this.errorMessage = null;

    this.notebookImageService.listImages().subscribe({
      next: (images) => {
        this.images = images;
        this.loading = false;
      },
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Unable to load notebook images.';
        this.loading = false;
      }
    });
  }

  openCreateDialog(): void {
    this.showCreateDialog = true;
  }

  closeCreateDialog(): void {
    this.showCreateDialog = false;
  }

  onImageCreated(): void {
    this.showCreateDialog = false;
    this.loadImages();
  }

  rebuildImage(image: NotebookImageDto): void {
    this.errorMessage = null;
    this.notebookImageService.triggerBuild(image.id).subscribe({
      next: () => this.loadImages(false),
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Failed to trigger rebuild.';
      }
    });
  }

  deleteImage(image: NotebookImageDto): void {
    if (!window.confirm(`Delete image "${image.name}"? This cannot be undone.`)) {
      return;
    }
    this.errorMessage = null;
    this.notebookImageService.deleteImage(image.id).subscribe({
      next: () => this.loadImages(),
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Failed to delete image.';
      }
    });
  }

  statusClass(status: string): string {
    return status.toLowerCase();
  }

  private autoRefresh(): void {
    if (!this.images.some((img) => img.status === 'PENDING' || img.status === 'BUILDING')) {
      return;
    }
    this.loadImages(false);
  }
}
