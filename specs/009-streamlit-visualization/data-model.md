# Data Model: Streamlit Visualization

**Feature Branch**: `009-streamlit-visualization`
**Date**: 2026-02-28

## Overview

This feature introduces **no new database entities**. All state is ephemeral and
managed in-memory by the Jupyter server extension running within each notebook pod.

## Ephemeral State (In-Memory, Per Pod)

### StreamlitProcess

Tracked by the `ml_platform_streamlit` Jupyter server extension within the notebook pod.
Not persisted to any database.

| Field | Type | Description |
|-------|------|-------------|
| file_path | string | Path relative to workspace root (e.g., `visualize/dashboard.py`) |
| port | int | TCP port Streamlit is listening on |
| pid | int | OS process ID of the Streamlit process |
| status | enum | `starting`, `running`, `errored`, `stopped` |
| started_at | datetime | When the process was started |
| error_message | string | Last error output if status is `errored` |

**Lifecycle**:
```
(none) → starting → running
                   → errored
running → stopped (explicit stop or file switch)
errored → stopped (explicit stop or file switch)
stopped → starting (new file selected)
```

**Constraints**:
- At most one StreamlitProcess active per notebook pod at any time.
- Process lifecycle tied to workspace session — stops when workspace stops.
- Process stays alive when user navigates away from Visualization tab.

---

## File Detection (Read-Only, No Storage)

### Streamlit File Scan

The extension scans the `visualize/` directory on each request. No file metadata
is stored persistently.

**Detection criteria**: A `.py` file in `visualize/` whose content contains
`import streamlit` or `from streamlit`.

**Returned per file**:

| Field | Type | Description |
|-------|------|-------------|
| name | string | Filename (e.g., `dashboard.py`) |
| path | string | Relative path (e.g., `visualize/dashboard.py`) |
| size_bytes | int | File size |
| last_modified | datetime | Last modification time |

---

## No Database Migration

This feature does not add any tables to PostgreSQL. All visualization state
resides in-memory within the notebook pod. No Flyway migration script is needed.

---

## Cross-Feature Dependencies

| Feature | Dependency | Type |
|---------|-----------|------|
| 002 - JupyterHub Notebooks | Workspace must be running | Runtime |
| 004 - Sample Delta Data | California Housing dataset in MinIO | Data |
| 007 - Notebook UI Customization | Analysis layout tab structure | Frontend |
