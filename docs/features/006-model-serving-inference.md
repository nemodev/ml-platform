# Feature 006: Model Serving & Inference

> Deploys trained models as REST inference endpoints via KServe in Standard (non-Knative) mode, with V2 inference protocol and backend-proxied predictions.

## What & Why

The end goal of the ML workflow is a deployed model serving predictions. After training in notebooks (Feature 002), tracking experiments (Feature 003), and productionizing via pipelines (Feature 005), users need to deploy models as HTTP endpoints. We chose KServe because it's the Kubernetes-native standard for model serving, supports multiple frameworks, and provides the V2 inference protocol. We run KServe in **Standard mode** (raw Kubernetes deployments) rather than Knative mode to avoid the heavy Knative/Istio dependency stack — a pragmatic choice for a PoC that aligns with YAGNI.

## Architecture

```
Portal (Deploy button)
    ↓ POST /api/v1/serving/deployments
Backend (ServingService)
    ├── Fetch model version from MLflow Registry
    ├── Resolve storage URI (mlflow-artifacts:/ → s3://)
    ├── Create ModelDeployment entity (DEPLOYING)
    └── Create InferenceService CRD via K8s Java Client
        ↓
KServe Controller (ml-platform-serving namespace)
    ├── Storage initializer downloads model from MinIO
    └── Creates predictor Deployment + Service
        ↓
MLServer (kserve-mlserver runtime)
    ↓ V2 inference protocol
Portal (Predict button)
    ↓ POST /api/v1/serving/deployments/{id}/predict
Backend → Kubernetes API proxy → MLServer pod
    ↓
PredictionResponse {modelName, modelVersion, outputs}
```

**Key decisions:**

- **KServe Standard mode** — No Knative, no Istio, no KNative Serving. InferenceService CRDs are annotated with `serving.kserve.io/deploymentMode: Standard`, creating raw Kubernetes Deployments. Significantly simpler to install and debug.
- **Dedicated serving namespace** — `ml-platform-serving` separates serving pods from the main `ml-platform` namespace. Cleaner RBAC and resource isolation.
- **K8s Java Client for CRD management** — Uses `CustomObjectsApi` to create, read, and delete InferenceService custom resources. The same K8s client pattern is used in Features 008 (Kaniko) and 010 (metrics).
- **Backend-proxied inference** — Predictions go through the backend, which proxies via the Kubernetes API server. This avoids exposing KServe services externally and maintains JWT-based access control.
- **Soft-delete for deployments** — `ModelDeployment` uses `deleted_at` timestamps instead of hard deletes. A partial unique index on `endpoint_name WHERE deleted_at IS NULL` allows name reuse after deletion (V007 migration).
- **MLflow artifact URI resolution** — MLflow 3.x returns `models:/m-{id}` URIs that KServe can't download. `ModelRegistryService.resolveModelStorageUri()` calls MLflow's download-uri API and converts to actual S3 paths.

## Key Implementation

| Layer | Key Files | Purpose |
|-------|-----------|---------|
| Backend | `controller/ServingController.java` | Deploy, list, detail, delete, predict endpoints |
| Backend | `service/ServingService.java` | Deployment lifecycle, status refresh, endpoint naming |
| Backend | `service/KServeService.java` | K8s CRD operations, pod failure detection, inference proxy |
| Backend | `service/ModelRegistryService.java` | MLflow Model Registry API, URI resolution |
| Backend | `config/KServeConfig.java` | K8s ApiClient bean, KServe properties |
| Backend | `model/ModelDeployment.java` | JPA entity with soft-delete support |
| Frontend | `features/models/models.component.ts` | Model registry (MLflow iframe) + endpoints tabs |
| Frontend | `features/models/deploy-dialog/` | Version selection, deploy trigger |
| Frontend | `features/models/deployments/` | Deployment list with 5s auto-refresh |
| Frontend | `features/models/predict-dialog/` | V2 inference request editor with JSON validation |
| Infra | `k8s/kserve/serving-namespace.yaml` | Dedicated namespace |
| Infra | `k8s/kserve/service-account.yaml` | `kserve-s3-sa` for MinIO access |
| Infra | `k8s/kserve/s3-secret.yaml` | MinIO credentials with KServe S3 annotations |

**Status refresh pattern:** Every list/get operation refreshes deployment status from KServe before returning. `ServingService.refreshDeploymentStatus()` fetches the InferenceService CRD, checks the `Ready` condition, and detects pod failures (image pull errors, crash loops) via `detectPodFailureMessage()`. This ensures users always see real KServe state, not stale DB state.

**Endpoint naming:** `ServingService.buildEndpointName()` generates DNS-compatible names from `{username}-{modelName}-v{version}`, normalized to lowercase alphanumeric + hyphens, max 63 chars minus space for KServe's `-predictor-` suffix.

**Inference proxy:** `KServeService.proxyPredict()` routes through the Kubernetes API server: `/api/v1/namespaces/{ns}/services/{serviceName}:80/proxy/v2/models/{endpointName}/infer`. It tries both `{endpointName}` and `{endpointName}-predictor` service names to handle KServe naming variations. Error responses with keywords like "shape", "feature", or "datatype" are remapped from 500 to 400 BAD_REQUEST for better user feedback.

**MLflow Model Registry integration:** `ModelsComponent` embeds the MLflow Model Registry UI via iframe (same CSS injection pattern as Feature 003). It pre-sets localStorage to show the "Model training" mode and forces light theme. The deploy dialog loads model versions from `ModelService`, and users select which version to deploy.

## Challenges & Solutions

- **MLflow 3.x URI format change** — Model version `source` changed from S3 paths to `models:/m-{id}`. KServe's storage initializer can't download from this URI. Solution: `resolveModelStorageUri()` calls MLflow's download-uri API and converts `mlflow-artifacts:/{path}` to `s3://ml-platform-mlflow/artifacts/{path}`. See `docs/TROUBLESHOOTING.md` item 11.
- **MLServer scikit-learn version mismatch** — Default MLServer 1.3.2 bundles scikit-learn 1.2.2, incompatible with models trained with 1.6.x. Solution: patch `kserve-mlserver` ClusterServingRuntime to use MLServer 1.6.1. See `docs/TROUBLESHOOTING.md` item 12.
- **V2 inference content_type hint** — MLServer needs `"parameters": {"content_type": "np"}` to decode inputs for sklearn models. Without it, the raw InferenceRequest object is passed to `predict()`. See `docs/TROUBLESHOOTING.md` item 13.
- **Partial unique index for endpoint name reuse** — Simple UNIQUE constraint on `endpoint_name` prevented redeployment after deletion. V007 migration replaces it with a partial unique index excluding soft-deleted records.

## Limitations

- **No autoscaling** — Single replica per deployment. No HPA or KServe's built-in autoscaling (which requires Knative).
- **No GPU support** — Resource defaults are CPU-only (1 CPU, 2Gi RAM). GPU serving would require node selectors and resource limits configuration.
- **No A/B testing or canary** — Single model version per endpoint. No traffic splitting between versions.
- **MLflow iframe DOM manipulation is fragile** — The models component directly manipulates the MLflow iframe to switch to "Model training" mode and hide the sidebar. Breaks if MLflow UI changes.
- **No model monitoring** — No drift detection, prediction logging, or performance metrics for deployed models.
- **Inference proxy adds latency** — Going through the K8s API server adds a hop. Direct service access would be faster but requires external network access.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|-------------|
| KServe Knative mode | Requires Knative Serving + Istio. Massive infrastructure overhead for a PoC. |
| Seldon Core | Less community momentum than KServe. KServe is becoming the K8s standard. |
| BentoML | Requires building custom serving containers. KServe + MLServer handles MLflow models natively. |
| Direct Flask/FastAPI serving | No model lifecycle management, health checks, or storage initialization. Would need to build from scratch. |
| TorchServe / TF Serving | Framework-specific. MLServer supports multiple frameworks through one runtime. |

## Potential Improvements

- **Horizontal Pod Autoscaler** — Add HPA configuration to InferenceService resources for traffic-based scaling.
- **GPU profiles** — Support GPU resource requests for deep learning model inference.
- **Model monitoring dashboard** — Log predictions and ground truth for drift detection and accuracy monitoring.
- **Canary deployments** — Support deploying a new model version alongside the current one with traffic splitting.
- **Direct service access** — Expose KServe endpoints via Ingress or NodePort for lower-latency inference, with proper authentication.
