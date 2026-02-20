# Research: Customized Notebook UI Embedding

**Feature**: 007-notebook-ui-customization
**Date**: 2026-02-17

## Decision 1: Method for Hiding JupyterLab Menu Bar and Status Bar

**Decision**: Use `page_config.json` with `disabledExtensions` to permanently disable the menu bar and status bar extensions at the JupyterLab configuration level.

**Rationale**: This is the officially supported mechanism in JupyterLab 4.x for controlling which extensions load. Disabling extensions via `page_config.json` prevents them from loading entirely, which means:
- Keyboard shortcuts remain fully functional (shortcuts are registered by the shortcut extension, not the menu extension)
- No CSS hacks or fragile DOM manipulation required
- Zero runtime overhead — the extensions simply don't load
- Works identically whether accessed via iframe or directly

**Alternatives considered**:
- CSS injection (`display: none` on `#jp-menu-panel`): Fragile, depends on internal CSS selectors that may change between JupyterLab versions. Hides visually but still loads the extension.
- `overrides.json` settings: More granular but doesn't fully disable extensions — the menu bar still renders, just with empty menus.
- Custom JupyterLab theme extension: Over-engineered for this use case.

**Configuration**:
```json
// /opt/conda/share/jupyter/lab/static/page_config.json
{
  "disabledExtensions": {
    "@jupyterlab/mainmenu-extension": true,
    "@jupyterlab/statusbar-extension": true,
    "@jupyterlab/apputils-extension:announcements": true
  }
}
```

## Decision 2: Method for Toggleable Sidebar (File Browser)

**Decision**: Use `jupyter-iframe-commands` bridge to toggle the sidebar via `application:toggle-left-area` command from the Angular portal toolbar.

**Rationale**: The file browser sidebar must be toggleable (not permanently disabled) because users need multi-file workflows. The `jupyter-iframe-commands` bridge provides the `application:toggle-left-area` command which maps directly to JupyterLab's built-in command for collapsing/expanding the left sidebar. On initial load, the bridge sends this command to collapse the sidebar for a clean default view.

**Alternatives considered**:
- Disabling `@jupyterlab/filebrowser-extension`: Would permanently remove the file browser — unacceptable since users need it.
- Using `/doc/tree/` URL mode: Hides sidebar but only works for single-document mode, not the general workspace view.
- `overrides.json` to set sidebar collapsed: JupyterLab doesn't expose a "sidebar collapsed by default" setting via overrides.

## Decision 3: Communication Bridge Library

**Decision**: Use `jupyter-iframe-commands` (PyPI v0.3.0) and `jupyter-iframe-commands-host` (npm v0.3.0) by TileDB/QuantStack.

**Rationale**:
- Only known library specifically designed for iframe-to-JupyterLab command communication
- Prebuilt JupyterLab 4 extension — no JupyterLab rebuild needed, just `pip install`
- Uses Comlink (by Google Chrome Labs) for transparent RPC over `postMessage`
- v0.3.0 adds `bridge.ready` promise for reliable initialization detection
- MIT/BSD-3 licensed
- Small footprint: host package is 6.1 kB unpacked

**Key API**:
```typescript
import { createBridge, createProxy } from 'jupyter-iframe-commands-host';
const bridge = createBridge({ iframeId: 'jupyter-iframe' });
await bridge.ready;
await bridge.execute('application:toggle-left-area');
await bridge.execute('apputils:change-theme', { theme: 'JupyterLab Dark' });
const commands = await bridge.listCommands();
```

**Alternatives considered**:
- Custom `postMessage` implementation: More work, no `bridge.ready` handshake, must manually serialize/deserialize.
- Building a custom JupyterLab extension: Would provide maximum control but significantly more complex to develop and maintain.

## Decision 4: Theme Synchronization Approach

**Decision**: Use the bridge's `apputils:change-theme` command on iframe load to sync the portal's current theme.

**Rationale**: The portal theme is authoritative (per clarification). After `bridge.ready` resolves, the Angular component sends a `apputils:change-theme` command with either `'JupyterLab Light'` or `'JupyterLab Dark'` based on the portal's current theme state. This happens once on load and again whenever the user changes theme via the portal toolbar.

**Theme mapping**:
- Portal light theme → `'JupyterLab Light'`
- Portal dark theme → `'JupyterLab Dark'`

## Decision 5: Single-Document Mode URL Pattern

**Decision**: Extend `JupyterHubService.getLabUrl()` to support an optional notebook path parameter. When a path is provided, construct a `/doc/tree/{path}` URL instead of `/lab`.

**Rationale**: JupyterLab 4.x natively supports `/doc/tree/{path}` URLs which open in single-document mode — no tabs, no sidebar, no file browser. This requires zero JupyterLab configuration and is a simple URL change in the backend service.

**URL patterns**:
- Default (workspace view): `/user/{username}/lab`
- Single-document (specific notebook): `/user/{username}/doc/tree/{notebookPath}`

## Decision 6: Kernel Status Communication

**Decision**: Use `createProxy` from `jupyter-iframe-commands-host` to register a callback that receives kernel status updates from JupyterLab.

**Rationale**: The bridge supports `createProxy()` which wraps callback functions via Comlink so they can be invoked from within the iframe. This allows JupyterLab to push kernel status changes (busy/idle/disconnected) to the Angular host without polling.

**Alternatives considered**:
- Polling via bridge commands: Higher latency, unnecessary network overhead.
- WebSocket direct connection to Jupyter kernel: Requires bypassing JupyterLab's kernel management, complex authentication.

## Decision 7: Announcement Suppression

**Decision**: Use `overrides.json` to disable update checks and news fetching, in addition to disabling the announcements extension via `page_config.json`.

**Rationale**: Belt-and-suspenders approach. The `page_config.json` disables the extension entirely, but `overrides.json` ensures the settings are also off in case a future JupyterLab version changes how announcements are delivered.

**Configuration**:
```json
// /opt/conda/share/jupyter/lab/settings/overrides.json
{
  "@jupyterlab/apputils-extension:announcements": {
    "checkForUpdates": false,
    "fetchNews": "false"
  }
}
```

## Compatibility Verification

| Component | Current Version | Bridge Requirement | Compatible |
|-----------|----------------|-------------------|------------|
| JupyterLab | 4.3.4 | >= 4.0.0 | Yes |
| Notebook | 7.3.2 | >= 7.0.0 | Yes |
| Python | 3.11 | >= 3.8 | Yes |
| Angular | 17.3 | Any (framework-agnostic npm) | Yes |
