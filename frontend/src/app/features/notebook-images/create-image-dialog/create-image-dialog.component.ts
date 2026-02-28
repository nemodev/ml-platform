import { NgFor, NgIf } from '@angular/common';
import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  CreateNotebookImageRequest,
  NotebookImageDto,
  NotebookImageService
} from '../../../core/services/notebook-image.service';

@Component({
  selector: 'app-create-image-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  templateUrl: './create-image-dialog.component.html',
  styleUrl: './create-image-dialog.component.scss'
})
export class CreateImageDialogComponent implements OnInit {
  private readonly notebookImageService = inject(NotebookImageService);

  @Output() close = new EventEmitter<void>();
  @Output() created = new EventEmitter<NotebookImageDto>();

  pythonVersions: string[] = [];
  name = '';
  pythonVersion = '';
  packages = '';
  extraPipIndexUrl = '';

  loadingVersions = true;
  submitting = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.notebookImageService.listPythonVersions().subscribe({
      next: (versions) => {
        this.pythonVersions = versions;
        this.pythonVersion = versions.length > 1 ? versions[1] : versions[0] ?? '';
        this.loadingVersions = false;
      },
      error: () => {
        this.errorMessage = 'Unable to load Python versions.';
        this.loadingVersions = false;
      }
    });
  }

  submit(): void {
    if (!this.name.trim()) {
      this.errorMessage = 'Image name is required.';
      return;
    }
    if (!this.pythonVersion) {
      this.errorMessage = 'Please select a Python version.';
      return;
    }

    const request: CreateNotebookImageRequest = {
      name: this.name.trim(),
      pythonVersion: this.pythonVersion,
      packages: this.packages.trim() || undefined,
      extraPipIndexUrl: this.extraPipIndexUrl.trim() || undefined
    };

    this.errorMessage = null;
    this.submitting = true;
    this.notebookImageService.createImage(request).subscribe({
      next: (image) => {
        // Trigger a build immediately after creation
        this.notebookImageService.triggerBuild(image.id).subscribe({
          next: () => {
            this.submitting = false;
            this.created.emit(image);
          },
          error: () => {
            // Image was created but build trigger failed — still report success
            this.submitting = false;
            this.created.emit(image);
          }
        });
      },
      error: (error) => {
        this.submitting = false;
        this.errorMessage = error?.error?.message ?? 'Failed to create image.';
      }
    });
  }

  cancel(): void {
    this.close.emit();
  }
}
