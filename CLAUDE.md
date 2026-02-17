# ml-platform Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-02-16

## Active Technologies
- Java 21 (backend), TypeScript 5.4+ (frontend), + Spring Boot 3.5.x, Angular 17, (002-jupyterhub-notebook)
- PostgreSQL (workspace table + JupyterHub DB), (002-jupyterhub-notebook)
- Java 21 (backend), TypeScript 5.4+ (frontend), + Spring Boot 3.5.x, Angular 17, MLflow 2.15.0, (003-mlflow-experiment-tracking)
- PostgreSQL (`mlflow` database for metadata), (003-mlflow-experiment-tracking)
- Python 3.11 (provisioning script + notebooks) + deltalake >= 0.22.0, scikit-learn, pandas, (004-sample-delta-data)
- MinIO (`ml-platform-sample-data` bucket for Delta table) (004-sample-delta-data)
- Java 21 (backend), TypeScript 5.4+ (frontend), + Spring Boot 3.5.x, Angular 17, Airflow 2.10.x (005-airflow-notebook-pipeline)
- PostgreSQL (`airflow` database for metadata), (005-airflow-notebook-pipeline)
- Java 21 (backend), TypeScript 5.4+ (frontend), + Spring Boot 3.5.x, Angular 17, KServe 0.13.x, (006-model-serving-inference)
- PostgreSQL (`model_deployments` table), (006-model-serving-inference)

- Java 21 (backend), TypeScript 5.4+ (frontend) + Spring Boot 3.5.x, Angular 17, Keycloak 26.x, (001-keycloak-auth-portal)

## Project Structure

```text
src/
tests/
```

## Commands

npm test && npm run lint

## Code Style

Java 21 (backend), TypeScript 5.4+ (frontend): Follow standard conventions

## Recent Changes
- 006-model-serving-inference: Added Java 21 (backend), TypeScript 5.4+ (frontend), + Spring Boot 3.5.x, Angular 17, KServe 0.13.x,
- 005-airflow-notebook-pipeline: Added Java 21 (backend), TypeScript 5.4+ (frontend), + Spring Boot 3.5.x, Angular 17, Airflow 2.10.x
- 004-sample-delta-data: Added Python 3.11 (provisioning script + notebooks) + deltalake >= 0.22.0, scikit-learn, pandas,


<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
