# ml-platform Development Guidelines

Last updated: 2026-02-21

## Quick Reference

For the comprehensive project reference (architecture, data model, API, infrastructure), see **[PROJECT_REFERENCE.md](PROJECT_REFERENCE.md)**.

## Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Backend | Spring Boot (Java 21) | 3.5.x |
| Frontend | Angular (TypeScript 5.4+) | 17.3 |
| Identity | Keycloak | 26.1 |
| Notebooks | JupyterHub (Z2JH) | 4.3.2 |
| Experiments | MLflow | 3.10.0 |
| Pipelines | Apache Airflow | 2.10.3 |
| Model Serving | KServe | 0.16.x |
| Data Format | Delta Lake | >= 0.22.0 |
| Storage | MinIO (S3-compatible) | latest |
| Database | PostgreSQL | latest |
| Processing | Apache Spark | 4.0.1 |

## Project Structure

```text
backend/             # Spring Boot 3.5.x, Java 21 (REST API, orchestration)
frontend/            # Angular 17, TypeScript 5.4+ (SPA portal)
infrastructure/      # Helm charts, K8s manifests, Docker images, deploy scripts
  docker/            # Notebook image Dockerfile + requirements.txt
  helm/              # jupyterhub/, mlflow/, minio/, airflow/
  k8s/               # keycloak/, airflow/, sample-data/, platform/
  scripts/           # deploy-full-stack.sh, port-forward.sh
specs/               # Feature specifications (001-007, speckit format)
docs/                # ARCHITECTURE.md — detailed architecture reference
```

## Commands

```bash
# Backend
cd backend && ./gradlew build

# Frontend
cd frontend && npm install && npm run build

# Notebook image
docker build -t ml-platform-notebook:latest infrastructure/docker/notebook-image/

# Full stack deployment (r1 cluster)
bash infrastructure/scripts/deploy-full-stack.sh
```

## Code Style

- **Java 21**: Standard Spring Boot conventions, JPA entities, records for DTOs
- **TypeScript 5.4+**: Angular standalone components, services with HttpClient + RxJS
- **Python 3.11**: Notebooks with papermill parameters tag

## Features (all implemented)

| # | Feature | Key Backend | Key Frontend |
|---|---------|-------------|-------------|
| 001 | Keycloak Auth & Portal | AuthController, SecurityConfig | auth.service, oidc-auth.module |
| 002 | JupyterHub Notebooks | WorkspaceController, JupyterHubService | notebooks.component, workspace.service |
| 003 | MLflow Experiments | ExperimentController, MlflowService | experiments.component, experiment.service |
| 004 | Sample Delta Data | (none) | (none) |
| 005 | Airflow Pipelines | PipelineController, PipelineService | pipelines, trigger-dialog, run-detail |
| 006 | Model Serving | ModelController, ServingController, ServingService | models, deploy-dialog, predict-dialog |
| 007 | Notebook UI Customization | AnalysisController, AnalysisService | analyses, analysis-layout |

## Key Architecture Notes

- All API endpoints use `/api/v1/` prefix
- Workspaces and experiments are **analysis-scoped**: `/api/v1/analyses/{analysisId}/workspaces`, `/api/v1/analyses/{analysisId}/experiments`
- Test users: `user1`/`password1`, `user2`/`password2`
- Database: 5 PostgreSQL databases (keycloak, ml_platform, mlflow, airflow, jupyterhub), 8 Flyway migrations
- KServe runs in Standard mode (non-Knative Kubernetes deployment mode) in `ml-platform-serving` namespace
- Single notebook image used for: JupyterHub servers, Airflow pipeline workers, Spark executors, data provisioning

## Deployment Contexts

| Context | Description | Access |
|---------|------------|--------|
| `local` | Rancher Desktop (ARM) | localhost ports via port-forward.sh |
| `r1` | Remote multi-node (amd64) | 172.16.100.10:30080 (NodePort) |

On r1, images use `ttl.sh/<name>:24h` ephemeral registry.

## Active Technologies
- Java 21 (backend), TypeScript 5.4+ (frontend) + Spring Boot 3.5.x, Angular 17, Kubernetes Java Client (io.kubernetes.client), Kaniko (gcr.io/kaniko-project/executor), Docker Distribution (registry:2) (008-custom-notebook-images)
- PostgreSQL (entities: notebook_images, image_builds), Container Registry (built images), MinIO (registry storage backend) (008-custom-notebook-images)

## Recent Changes
- 008-custom-notebook-images: Added Java 21 (backend), TypeScript 5.4+ (frontend) + Spring Boot 3.5.x, Angular 17, Kubernetes Java Client (io.kubernetes.client), Kaniko (gcr.io/kaniko-project/executor), Docker Distribution (registry:2)
