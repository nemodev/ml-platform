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
    path: 'analyses',
    canActivate: [authGuard],
    loadComponent: () => import('./features/analyses/analyses.component').then((m) => m.AnalysesComponent)
  },
  {
    path: 'analyses/:analysisId',
    canActivate: [authGuard],
    loadComponent: () => import('./features/analyses/analysis-layout.component').then((m) => m.AnalysisLayoutComponent),
    children: [
      // Componentless routes — components are embedded in the layout for iframe
      // persistence. These entries exist solely for routerLinkActive matching.
      { path: 'notebooks', children: [] },
      { path: 'experiments', children: [] },
      { path: '', redirectTo: 'notebooks', pathMatch: 'full' }
    ]
  },
  {
    path: 'models',
    canActivate: [authGuard],
    loadComponent: () => import('./features/models/models.component').then((m) => m.ModelsComponent)
  },
  {
    path: 'pipelines',
    canActivate: [authGuard],
    loadComponent: () => import('./features/pipelines/pipelines.component').then((m) => m.PipelinesComponent)
  },
  // Backward-compat redirects for old bookmarked URLs
  { path: 'notebooks', redirectTo: 'analyses', pathMatch: 'full' },
  { path: 'experiments', redirectTo: 'analyses', pathMatch: 'full' },
  { path: '**', redirectTo: 'dashboard' }
];
