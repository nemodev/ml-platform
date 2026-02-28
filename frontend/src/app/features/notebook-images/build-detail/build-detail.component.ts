import { DatePipe, NgClass, NgFor, NgIf } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import {
  ImageBuildDto,
  NotebookImageDetailDto,
  NotebookImageService
} from '../../../core/services/notebook-image.service';

@Component({
  selector: 'app-build-detail',
  standalone: true,
  imports: [NgIf, NgFor, NgClass, DatePipe, RouterLink],
  templateUrl: './build-detail.component.html',
  styleUrl: './build-detail.component.scss'
})
export class BuildDetailComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly notebookImageService = inject(NotebookImageService);

  imageId = '';
  image: NotebookImageDetailDto | null = null;
  builds: ImageBuildDto[] = [];
  selectedBuild: ImageBuildDto | null = null;
  buildLogs = '';

  loading = true;
  loadingLogs = false;
  errorMessage: string | null = null;
  rebuildSubmitting = false;
  elapsedTime = '';

  // Build progress stages
  readonly buildStages = ['Queued', 'Building Base', 'Installing Packages', 'Pushing Image', 'Complete'];

  private refreshSub?: Subscription;
  private elapsedSub?: Subscription;

  ngOnInit(): void {
    this.imageId = this.route.snapshot.paramMap.get('imageId') ?? '';
    this.loadImageDetail();
    this.refreshSub = timer(5000, 5000).subscribe(() => this.autoRefresh());
    this.elapsedSub = timer(0, 1000).subscribe(() => this.updateElapsedTime());
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
    this.elapsedSub?.unsubscribe();
  }

  loadImageDetail(): void {
    this.errorMessage = null;

    this.notebookImageService.getImage(this.imageId).subscribe({
      next: (image) => {
        this.image = image;
        this.loading = false;
        this.loadBuilds();
      },
      error: (error) => {
        this.errorMessage = error?.error?.message ?? 'Unable to load image details.';
        this.loading = false;
      }
    });
  }

  loadBuilds(): void {
    this.notebookImageService.listBuilds(this.imageId).subscribe({
      next: (builds) => {
        this.builds = builds;
        if (!this.selectedBuild && builds.length > 0) {
          this.selectBuild(builds[0]);
        } else if (this.selectedBuild) {
          const updated = builds.find((b) => b.id === this.selectedBuild!.id);
          if (updated) {
            this.selectedBuild = updated;
            if (updated.status === 'BUILDING') {
              this.loadBuildLogs(updated);
            }
          }
        }
      }
    });
  }

  selectBuild(build: ImageBuildDto): void {
    this.selectedBuild = build;
    this.loadBuildLogs(build);
  }

  triggerRebuild(): void {
    this.rebuildSubmitting = true;
    this.errorMessage = null;
    this.notebookImageService.triggerBuild(this.imageId).subscribe({
      next: (build) => {
        this.rebuildSubmitting = false;
        this.builds.unshift(build);
        this.selectBuild(build);
      },
      error: (error) => {
        this.rebuildSubmitting = false;
        this.errorMessage = error?.error?.message ?? 'Failed to trigger build.';
      }
    });
  }

  statusClass(status: string): string {
    return status.toLowerCase();
  }

  currentStageIndex(): number {
    if (!this.selectedBuild) return -1;
    const stage = this.selectedBuild.progressStage?.toLowerCase() ?? '';
    if (this.selectedBuild.status === 'SUCCEEDED') return this.buildStages.length - 1;
    if (this.selectedBuild.status === 'FAILED' || this.selectedBuild.status === 'CANCELLED') return -1;
    if (this.selectedBuild.status === 'QUEUED') return 0;
    if (stage.includes('push')) return 3;
    if (stage.includes('install') || stage.includes('pip')) return 2;
    if (stage.includes('build') || stage.includes('base')) return 1;
    return 1; // default to Building Base for BUILDING status
  }

  isStageComplete(index: number): boolean {
    return index < this.currentStageIndex();
  }

  isStageActive(index: number): boolean {
    return index === this.currentStageIndex();
  }

  private loadBuildLogs(build: ImageBuildDto): void {
    if (build.status === 'QUEUED') {
      this.buildLogs = '';
      return;
    }
    this.loadingLogs = true;
    this.notebookImageService.getBuildLogs(this.imageId, build.id).subscribe({
      next: (logs) => {
        this.buildLogs = logs;
        this.loadingLogs = false;
      },
      error: () => {
        this.buildLogs = '';
        this.loadingLogs = false;
      }
    });
  }

  private updateElapsedTime(): void {
    if (!this.selectedBuild?.startedAt) {
      this.elapsedTime = '';
      return;
    }
    const isActive = this.selectedBuild.status === 'QUEUED' || this.selectedBuild.status === 'BUILDING';
    const end = isActive ? Date.now() : (this.selectedBuild.completedAt ? new Date(this.selectedBuild.completedAt).getTime() : Date.now());
    const start = new Date(this.selectedBuild.startedAt).getTime();
    const seconds = Math.max(0, Math.floor((end - start) / 1000));
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    this.elapsedTime = `${m}m ${s}s`;
  }

  private autoRefresh(): void {
    const imageActive = this.image && (this.image.status === 'PENDING' || this.image.status === 'BUILDING');
    const buildActive = this.selectedBuild && (this.selectedBuild.status === 'QUEUED' || this.selectedBuild.status === 'BUILDING');
    if (!imageActive && !buildActive) {
      return;
    }
    this.notebookImageService.getImage(this.imageId).subscribe({
      next: (image) => {
        this.image = image;
        this.loadBuilds();
      }
    });
  }
}
