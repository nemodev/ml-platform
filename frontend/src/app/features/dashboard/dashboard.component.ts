import { AsyncPipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [AsyncPipe],
  template: `
    <section>
      <h2>Dashboard</h2>
      <p>Welcome, {{ username$ | async }}.</p>
    </section>
  `
})
export class DashboardComponent {
  private readonly authService = inject(AuthService);
  readonly username$ = this.authService.username$;
}
