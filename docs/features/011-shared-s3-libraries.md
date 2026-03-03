# Feature 011: Shared S3 Libraries & Data Navigator

> Provides a centralized S3-backed shared storage area accessible via a REST API, a frontend file browser, and as a read-only FUSE mount inside all runtime pods (JupyterHub, Airflow, Spark).

## What & Why

Data scientists and ML engineers need a shared location for datasets, Python modules, and configuration files that all team members can access from notebooks, pipelines, and model training jobs. Without this, teams resort to ad-hoc sharing via S3 CLI commands or manual file uploads — no discoverability, no preview, no governance. Feature 011 introduces three capabilities:

1. **Backend REST API** for listing, uploading, downloading, previewing, and deleting objects under a configurable shared S3 prefix, built on the existing MinioClient/ConfigurationProperties pattern.
2. **Frontend file browser** as a new top-level portal page ("Shared Libraries") with folder navigation, breadcrumbs, file actions, upload with progress, client-side filtering, and inline preview for text/image/tabular files (including Parquet).
3. **Runtime pod mount** via s3fs-fuse sidecar containers so shared resources appear at `/home/jovyan/shared/` (read-only) inside notebook pods, Airflow workers, and Spark executors. The s3fs sidecar infrastructure was built as part of the workspace isolation work — this feature adds a second read-only mount.

## Architecture

```
Frontend (Shared Libraries page)
    ↓ GET/POST/DELETE /api/v1/shared-library/**
Backend (SharedLibraryController → SharedLibraryService)
    ↓ MinIO Java SDK (listObjects, putObject, getObject, removeObject, presignedGetObject)
MinIO (S3-compatible)
    ↑ s3://{bucket}/{shared-prefix}/...
    ↑
s3fs-fuse sidecar (read-only mount at /home/jovyan/shared/)
    ↑
Runtime pods (JupyterHub, Airflow workers, Spark executors)
```

**Key decisions:**

- **Reuse existing MinioClient bean and ConfigurationProperties** — The project already has `StorageConfig.java` with a `MinioClient` bean configured via `services.minio.*` properties. Shared library adds new properties under the same prefix: `shared-bucket`, `shared-prefix`, `shared-preview-max-bytes`, `shared-upload-max-bytes`. The new `SharedLibraryService` follows the same pattern as `NotebookStorageService`.
- **Custom-built Angular file browser** — Evaluated available Angular file browser libraries (ngx-explorer, ng6-file-man, ngx-voyage, angular-filemanager). All are either archived, incompatible with Angular 17, or unmaintained. A custom component is the standard approach: a list view with breadcrumb navigation, file actions, and a preview panel.
- **Presigned URLs for download, streaming for preview** — Downloads use presigned URLs (15-min expiry, existing pattern). Previews stream through the backend for content-type detection, size bounding (1 MB text, 100 rows tabular), and Parquet row extraction. Upload uses multipart form data through the backend (not direct-to-S3 presigned POST) for simplicity and 500 MB size validation.
- **s3fs-fuse sidecar for runtime mount** — Reuses the same sidecar image built for workspace isolation. A second mount is added as read-only at `/home/jovyan/shared/` in all runtime pods. Users can `import shared.my_module` or `pd.read_csv('/home/jovyan/shared/datasets/data.csv')` directly in notebooks.
- **Backend-side Parquet preview** — Uses `org.apache.parquet:parquet-hadoop` to read the first N rows server-side and return as JSON. Avoids shipping a Parquet parser to the browser.
- **Java record DTOs** — Following project convention (e.g., `PortalSectionResponse`, `WorkspaceResponse`). Records for: `SharedResourceResponse`, `SharedResourceListResponse`, `SharedResourcePreviewResponse`, `UploadResultResponse`, `DownloadUrlResponse`, `CreateFolderRequest`.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `controller/SharedLibraryController.java` | REST endpoints: list, upload, download, preview, delete, create folder |
| Backend | `service/SharedLibraryService.java` | S3 operations via MinioClient |
| Backend | `config/StorageConfig.java` | Extended: shared-bucket, shared-prefix, preview/upload limits |
| Backend | `dto/shared/*.java` | Java record DTOs for all API responses |
| Backend | `controller/PortalController.java` | Modified: add shared-libraries navigation section |
| Frontend | `features/shared-libraries/shared-libraries.component.ts` | Main file browser component |
| Frontend | `features/shared-libraries/shared-library.service.ts` | HTTP client for shared library API |
| Frontend | `features/shared-libraries/components/` | Breadcrumb, file-list, file-preview, upload-dialog, create-folder-dialog |
| Frontend | `app.routes.ts` | Add lazy-loaded route for `/shared-libraries` |
| Frontend | `app.component.ts` | Add fallback navigation entry |
| Infra | `helm/jupyterhub/values.yaml` | Add second s3fs mount (read-only shared) to pre_spawn_hook |
| Infra | `helm/airflow/local-values.yaml` | Add s3fs sidecar + shared volume to worker pods |
| Infra | `installer/templates/jupyterhub-values.yaml` | Mirror JH values with installer placeholders |
| Infra | `installer/templates/airflow-values.yaml` | Mirror Airflow values with installer placeholders |

**Navigation integration:** The frontend follows the existing pattern — a `sections` array in `app.component.ts` (static fallback) and `PortalController.sections()` (backend source of truth). Adding "Shared Libraries" requires an entry in both places plus a lazy-loaded route in `app.routes.ts`.

**Preview pipeline:** The preview endpoint detects file type by extension, then:
- **Text** (`.py`, `.csv`, `.json`, `.yaml`, `.txt`, `.md`): Returns first 1 MB of content as UTF-8 string
- **Image** (`.png`, `.jpg`, `.gif`, `.svg`): Returns presigned URL for direct browser rendering
- **Tabular** (`.parquet`): Reads first 100 rows via `parquet-hadoop`, returns column names, types, and row data as JSON
- **Other**: Returns metadata only (size, content type, last modified)

**Runtime mount pattern:** The s3fs sidecar already runs in each notebook pod for the workspace mount. For shared libraries, a second sidecar (or extended entrypoint) mounts the shared S3 prefix read-only at a separate `emptyDir` volume:
```
sidecar: s3fs-shared → /mnt/shared (emptyDir, Bidirectional)
main:    /mnt/shared → /home/jovyan/shared (HostToContainer, readOnly: true)
```

## Challenges & Solutions

- **S3 directory semantics** — S3 is a flat key-value store; "folders" are just common prefixes. The backend synthesizes folder entries from `listObjects()` results and creates zero-byte directory marker objects (`application/x-directory` content type) when users create folders. s3fs requires these markers for prefix validation with `compat_dir` mode.
- **Large file upload through backend** — Proxying 500 MB uploads through Spring Boot requires configuring `spring.servlet.multipart.max-file-size` and `spring.servlet.multipart.max-request-size`. The backend streams directly to MinIO (`putObject` with known size) rather than buffering the entire file in memory.
- **Parquet dependency weight** — `parquet-hadoop` pulls in a significant transitive dependency tree from `hadoop-common`. Only the minimal Parquet reader and Hadoop filesystem abstraction are needed. Exclusions and dependency management keep the footprint manageable.
- **s3fs read-only mount for shared prefix** — The shared mount uses s3fs `ro` option to prevent writes from runtime pods. Users manage shared content exclusively through the web UI / REST API.
- **CSP for iframe embedding** — Already solved in workspace isolation work. The nginx-level CSP override (`proxy_hide_header Content-Security-Policy` + `add_header "frame-ancestors 'self'" always`) on the `/user/` location applies to all JupyterHub iframe content.

## Limitations

- **No access control per folder** — All authenticated users can read, upload, and delete any file in the shared library. No per-folder or per-file permissions.
- **500 MB upload limit** — Backend-proxied uploads have a practical size limit. Larger datasets must be uploaded via CLI or SDK directly to S3.
- **No versioning** — Overwriting a file replaces it permanently. No version history or trash/recycle bin.
- **s3fs latency for large directories** — Listing directories with thousands of files via s3fs may be slow. The web UI uses paginated API calls which are faster than filesystem listing.
- **Single shared prefix** — All shared content lives under one S3 prefix. No support for multiple shared libraries or team-scoped prefixes.
- **Parquet preview limited to first 100 rows** — No support for querying or filtering Parquet data. Preview is a sampling mechanism, not an analysis tool.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| OSS Angular file browser library | All evaluated options (ngx-explorer, ng6-file-man, ngx-voyage) are archived, incompatible, or unmaintained for Angular 17. |
| Embedded elFinder or similar JS file manager | Style inconsistency with Angular Material theme, complex auth bridging, heavy JavaScript bundle. |
| Direct-to-S3 presigned uploads | Adds complexity (presign → upload → confirm flow) without significant benefit at the 500 MB scale. |
| S3 CSI driver instead of s3fs sidecar | Requires cluster-level installation. The s3fs sidecar is already proven from workspace isolation and is self-contained per pod. |
| Periodic sync (CronJob → PVC) | Stale data, shared PVC complexity, doesn't meet the 5-minute sync requirement. |
| Client-side Parquet parsing (parquet-wasm) | Requires shipping a WASM parser to the browser. Server-side parsing is simpler and supports bounded reads. |

## Potential Improvements

- **Per-folder access control** — RBAC-based permissions on shared library prefixes, integrated with Keycloak roles.
- **File versioning** — Enable MinIO bucket versioning and expose version history in the UI.
- **Drag-and-drop upload** — Support drag-and-drop from desktop to the file browser.
- **Full-text search** — Index file content for search across the shared library.
- **Team-scoped libraries** — Multiple shared prefixes scoped to Keycloak groups/teams.
- **Larger uploads via chunked/resumable protocol** — Tus or S3 multipart upload for files > 500 MB.
- **Notebook integration** — A JupyterLab extension that provides a sidebar view of shared libraries within the notebook interface.
