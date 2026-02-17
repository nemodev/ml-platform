# Research: Model Serving & Inference

**Feature**: `006-model-serving-inference` | **Date**: 2026-02-16

## Summary

Feature 006 adds KServe-based model serving with MLflow Model Registry
integration. Users register trained models in MLflow, deploy them as
KServe InferenceServices via the portal, and call inference endpoints
from notebooks or pipeline DAGs. KServe runs in raw deployment mode
(no Knative/Istio) using the mlserver runtime for V2 inference protocol.

## Decisions

### D1: Model Serving Framework — KServe in Raw Deployment Mode

**Decision**: Use KServe with raw deployment mode. InferenceService
CRDs create plain K8s Deployments and ClusterIP Services. No Knative
Serving or Istio required.

**Rationale**: KServe is the K8s-native standard for model serving,
listed in the constitution's technology stack. Raw deployment mode
avoids heavy dependencies (Knative + Istio) and aligns with Principle
VI (Simplicity & YAGNI). Scale-to-zero is explicitly out of scope
for MVP.

**Alternatives considered**:
- Knative mode: Requires Istio/Kourier + Knative Serving. Adds
  scale-to-zero but introduces significant infrastructure complexity.
- MLflow `models serve`: Simple but no K8s integration, no CRD-based
  lifecycle management.
- Custom Flask/FastAPI: Maximum control but reinvents serving
  infrastructure that KServe provides.

**Installation**: KServe CRDs + controller installed via manifests
(not Helm — KServe doesn't have an official Helm chart for raw mode).

### D2: Model Registry — MLflow Model Registry

**Decision**: Enable the MLflow Model Registry (built into the MLflow
tracking server from feature 003). Users register models via
`mlflow.register_model()` in notebooks, then deploy registered
versions to KServe via the portal.

**Rationale**: MLflow Model Registry is built into the existing
MLflow tracking server — no additional infrastructure needed. It
provides model versioning, stage transitions (None → Staging →
Production → Archived), and artifact URI resolution. The prior
project used this exact pattern.

**Flow**:
1. User trains model and logs with `mlflow.sklearn.log_model()`
2. User registers model: `mlflow.register_model(model_uri, name)`
3. User deploys version via portal → backend creates InferenceService
4. KServe pulls model artifacts from MinIO via the MLflow artifact URI

### D3: Serving Runtime — MLServer with V2 Protocol

**Decision**: Use the `kserve-mlserver` ClusterServingRuntime for
serving MLflow-format models. MLServer supports the V2 inference
protocol (KFServing V2 / Open Inference Protocol).

**Rationale**: MLServer natively supports MLflow model format,
scikit-learn, XGBoost, and LightGBM. The V2 protocol is the modern
standard with a well-defined JSON schema for inference requests and
responses. The prior project configured this exact runtime.

**V2 Inference Protocol endpoints**:
- `POST /v2/models/{model_name}/infer` — prediction request
- `GET /v2/models/{model_name}/ready` — readiness check
- `GET /v2/models/{model_name}` — model metadata

**Request format**:
```json
{
  "inputs": [
    {
      "name": "predict",
      "shape": [1, 8],
      "datatype": "FP64",
      "data": [[8.3252, 41.0, 6.98, 1.02, 322.0, 2.56, 37.88, -122.23]]
    }
  ]
}
```

### D4: Model Artifact Access — KServe StorageUri with MinIO

**Decision**: KServe InferenceService `storageUri` points to the
MLflow model artifact in MinIO. A K8s Secret provides MinIO
credentials to the KServe storage initializer.

**Rationale**: MLflow stores model artifacts in MinIO (feature 003).
KServe's built-in storage initializer downloads artifacts from S3
compatible storage before starting the model server. This is the
standard KServe pattern — no custom download logic needed.

**Storage URI format**: `s3://ml-platform-mlflow/artifacts/{experiment_id}/{run_id}/artifacts/model`

**K8s Secret for S3 access**:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kserve-s3-secret
  annotations:
    serving.kserve.io/s3-endpoint: minio.ml-platform.svc:9000
    serving.kserve.io/s3-usehttps: "0"
type: Opaque
data:
  AWS_ACCESS_KEY_ID: <base64>
  AWS_SECRET_ACCESS_KEY: <base64>
```

### D5: Inference Authentication — Backend Proxy

**Decision**: Inference endpoints are not directly exposed to users.
The backend proxies inference requests, adding authentication. KServe
ClusterIP services are only accessible within the cluster. Pipeline
DAGs call inference endpoints directly within the cluster (service-to-
service, no auth needed for in-cluster calls).

**Rationale**: KServe raw mode doesn't include an auth layer. Rather
than adding a sidecar proxy or service mesh, the backend acts as the
auth gateway for external inference calls (same pattern as MLflow and
Airflow proxies). In-cluster calls from pipeline DAGs don't need auth
since they're within the trusted cluster network.

**Alternatives considered**:
- Istio sidecar for mTLS: Requires Istio. Out of scope for MVP.
- OAuth2 Proxy sidecar: Adds per-pod complexity.
- Token injection via KServe: Not supported in raw mode.

### D6: Backend — Model Deployment Service

**Decision**: The Spring Boot backend provides endpoints for:
- Listing registered models from MLflow Model Registry
- Deploying a model version to KServe (creates InferenceService CRD)
- Checking deployment status (reads InferenceService status)
- Deleting a deployment
- Proxying inference requests to KServe endpoints

**Rationale**: Same proxy pattern used for JupyterHub (feature 002),
MLflow (feature 003), and Airflow (feature 005). The backend uses
the Kubernetes Java client to create/read/delete InferenceService
CRDs.

**K8s API interaction**:
- Create: `POST /apis/serving.kserve.io/v1beta1/namespaces/{ns}/inferenceservices`
- Get: `GET /apis/serving.kserve.io/v1beta1/namespaces/{ns}/inferenceservices/{name}`
- List: `GET /apis/serving.kserve.io/v1beta1/namespaces/{ns}/inferenceservices`
- Delete: `DELETE /apis/serving.kserve.io/v1beta1/namespaces/{ns}/inferenceservices/{name}`

### D7: Frontend — Custom Model Serving UI

**Decision**: Build custom Angular components for the "Models" section
in the portal:
- Model list: Shows registered models from MLflow Model Registry
- Deploy dialog: Select model version, set resource limits
- Endpoints list: Shows deployed endpoints with status
- Inference test: Simple form to send test prediction requests

**Rationale**: Consistent with the custom UI approach used for
Experiments (feature 003) and Pipelines (feature 005). Provides
user isolation and a simplified interface.

### D8: KServe Namespace — Dedicated Serving Namespace

**Decision**: Deploy KServe InferenceServices in a dedicated
`ml-platform-serving` namespace. KServe controller runs in the
`kserve` namespace (installed globally).

**Rationale**: Separating serving pods from the main `ml-platform`
namespace provides clearer resource management and RBAC boundaries.
The backend ServiceAccount needs permissions to manage
InferenceService CRDs in the serving namespace.

### D9: MLflow Model Registry API

**Decision**: The backend accesses MLflow Model Registry via the
MLflow REST API:
- `GET /api/2.0/mlflow/registered-models/search` — list models
- `GET /api/2.0/mlflow/registered-models/get` — get model details
- `GET /api/2.0/mlflow/model-versions/search` — list versions
- `GET /api/2.0/mlflow/model-versions/get` — get version details

**Rationale**: The MLflow tracking server already deployed in feature
003 includes the Model Registry API. No additional infrastructure
needed — just new backend endpoints that proxy these MLflow APIs with
user isolation (prefix filtering).

### D10: Resource Defaults for Inference Pods

**Decision**: Default resource allocation for inference pods:
- 1 CPU / 2Gi RAM (request = limit)
- Single replica (no autoscaling in MVP)

**Rationale**: Sufficient for scikit-learn models serving on CPU.
Matches the prior project's resource configuration. Users do not
configure resources in MVP — defaults are fixed.
