import { Injectable, signal } from '@angular/core';
import { Subject, Observable } from 'rxjs';
import { createBridge, createProxy } from 'jupyter-iframe-commands-host';

export type BridgeConnectionState = 'idle' | 'connecting' | 'ready' | 'disconnected';

interface BridgeLogEntry {
  time: Date;
  action: string;
  result: string;
}

interface JupyterBridge {
  ready: Promise<void>;
  execute(commandId: string, args?: Record<string, unknown>): Promise<void>;
  listCommands(): Promise<string[]>;
}

const MAX_LOG_ENTRIES = 100;

@Injectable({ providedIn: 'root' })
export class JupyterBridgeService {
  /** Reactive signal for the current bridge connection state. */
  readonly connectionState = signal<BridgeConnectionState>('idle');

  /** Log of bridge operations for debugging (capped at the last 100 entries). */
  readonly bridgeLog = signal<BridgeLogEntry[]>([]);

  private readonly _bridgeErrors = new Subject<{ commandId: string; error: string }>();
  /** Observable of bridge errors for components to display user-facing notifications. */
  readonly bridgeErrors$ = this._bridgeErrors.asObservable();

  private bridge: JupyterBridge | null = null;

  /**
   * Initialize bridge targeting an iframe by DOM id.
   * Calls createBridge(), awaits bridge.ready, and updates connectionState.
   */
  async initialize(iframeId: string): Promise<void> {
    this.connectionState.set('connecting');
    this.log('initialize', `targeting iframe id="${iframeId}"`);

    try {
      this.bridge = createBridge({ iframeId }) as JupyterBridge;
      await this.bridge.ready;
      this.connectionState.set('ready');
      this.log('initialize', 'bridge ready');
    } catch (err) {
      this.connectionState.set('disconnected');
      const message = err instanceof Error ? err.message : String(err);
      this.log('initialize', `error: ${message}`);
      console.error('[JupyterBridgeService] Bridge initialization failed:', err);
    }
  }

  /**
   * Execute a JupyterLab command via the bridge.
   * On unknown command error, logs a warning and does not throw.
   */
  async execute(commandId: string, args?: Record<string, unknown>): Promise<void> {
    if (this.connectionState() !== 'ready' || this.bridge === null) {
      this.log('execute', `skipped — bridge not ready (state="${this.connectionState()}")`);
      console.warn('[JupyterBridgeService] execute() called but bridge is not ready.');
      return;
    }

    try {
      await this.bridge.execute(commandId, args);
      this.log('execute', `ok — commandId="${commandId}"`);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log('execute', `warning — commandId="${commandId}" error: ${message}`);
      console.warn(`[JupyterBridgeService] execute() failed for command "${commandId}":`, err);
      this._bridgeErrors.next({ commandId, error: message });
      // Intentionally not re-throwing; unknown command errors are non-fatal.
    }
  }

  /**
   * List all available JupyterLab commands.
   * Returns an empty array if the bridge is not ready.
   */
  async listCommands(): Promise<string[]> {
    if (this.connectionState() !== 'ready' || this.bridge === null) {
      this.log('listCommands', `skipped — bridge not ready (state="${this.connectionState()}")`);
      console.warn('[JupyterBridgeService] listCommands() called but bridge is not ready.');
      return [];
    }

    const commands = await this.bridge.listCommands();
    this.log('listCommands', `returned ${commands.length} command(s)`);
    return commands;
  }

  /**
   * Cleanup on component teardown.
   * Nullifies the bridge instance and resets connection state to idle.
   */
  destroy(): void {
    this.bridge = null;
    this.connectionState.set('idle');
    this.log('destroy', 'bridge instance released');
  }

  /** Append an entry to bridgeLog, keeping at most MAX_LOG_ENTRIES entries. */
  private log(action: string, result: string): void {
    const entry: BridgeLogEntry = { time: new Date(), action, result };
    this.bridgeLog.update((entries) => {
      const updated = [...entries, entry];
      return updated.length > MAX_LOG_ENTRIES
        ? updated.slice(updated.length - MAX_LOG_ENTRIES)
        : updated;
    });
  }
}

// Re-export createProxy so consumers can use it without importing the package directly.
export { createProxy };
