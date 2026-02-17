import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent)
  },
  {
    path: 'notebooks',
    canActivate: [authGuard],
    loadComponent: () => import('./features/notebooks/notebooks.component').then((m) => m.NotebooksComponent)
  },
  {
    path: 'experiments',
    canActivate: [authGuard],
    loadComponent: () => import('./features/experiments/experiments.component').then((m) => m.ExperimentsComponent)
  },
  {
    path: 'pipelines',
    canActivate: [authGuard],
    loadComponent: () => import('./features/pipelines/pipelines.component').then((m) => m.PipelinesComponent)
  },
  { path: '**', redirectTo: 'dashboard' }
];
