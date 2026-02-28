# Feature Specification: Model Serving & Inference

**Feature Branch**: `006-model-serving-inference`
**Created**: 2026-02-16
**Status**: Implemented
**Input**: User description: "Model Serving and Inference"

**Depends on**: `003-mlflow-experiment-tracking` (model registry),
`005-airflow-notebook-pipeline` (pipeline DAGs for inference)

## Clarifications

### Session 2026-02-16

- Q: Which model serving technology should be used? → A: KServe (K8s-native, supports MLflow model format, V2 inference protocol)
- Q: How should models get from MLflow to KServe? → A: Enable MLflow Model Registry; users register models there, then deploy registered versions to KServe via the portal
- Q: Which KServe deployment mode — Knative or raw? → A: Raw deployment mode (plain K8s Deployments + Services, no Knative/Istio needed)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Deploy Registered Model to Inference Endpoint (Priority: P1)

A data user has trained a model and registered it in the experiment
tracking system's model registry. They want to deploy this model as a
live inference endpoint. The user selects a registered model version and
initiates deployment. The system creates a serving endpoint that is
ready to accept prediction requests.

**Why this priority**: Model deployment is the prerequisite for
inference. Without a live endpoint, no predictions can be served.

**Independent Test**: Can be verified by registering a simple model
(e.g., scikit-learn classifier), deploying it, and sending a health
check request to confirm the endpoint is live.

**Acceptance Scenarios**:

1. **Given** a model registered in the model registry, **When** the
   user initiates deployment of a specific model version, **Then** the
   system creates an inference endpoint.
2. **Given** a deployment in progress, **When** the user checks the
   status, **Then** they can see whether the endpoint is deploying,
   ready, or failed.
3. **Given** a successfully deployed endpoint, **When** a health check
   request is sent, **Then** the endpoint responds indicating it is
   ready to serve predictions.
4. **Given** a deployment that fails (e.g., invalid model format),
   **When** the user checks the status, **Then** a clear error message
   explains the failure reason.

---

### User Story 2 - Make Inference Call to Deployed Model (Priority: P2)

A deployed model endpoint is live and ready. A pipeline DAG (or a
notebook) sends input data to the endpoint and receives predictions
in response. The predictions are in a structured format that can be
used in downstream processing.

**Why this priority**: Inference is the whole point of model serving.
This closes the ML lifecycle loop from data to prediction.

**Independent Test**: Can be verified by sending a prediction request
with sample input data to the deployed endpoint and receiving a valid
prediction response.

**Acceptance Scenarios**:

1. **Given** a deployed model endpoint, **When** a request with valid
   input data is sent, **Then** the endpoint returns predictions in a
   structured format.
2. **Given** a deployed model endpoint, **When** a request with
   malformed input is sent, **Then** the endpoint returns a clear error
   response indicating the input format issue.
3. **Given** a pipeline DAG that sends data to the inference endpoint,
   **When** the pipeline runs, **Then** the predictions are captured
   in the pipeline output.
4. **Given** multiple concurrent inference requests, **When** they are
   sent simultaneously, **Then** the endpoint handles them without
   errors.

---

### User Story 3 - Authenticated Inference Access (Priority: P3)

Inference endpoints are protected by authentication. Only authenticated
platform users (or services with valid tokens) can make inference calls.
Unauthenticated requests are rejected.

**Why this priority**: Security is required for production endpoints.
Without auth, model endpoints are exposed to unauthorized access.

**Independent Test**: Can be verified by sending an inference request
without authentication and confirming it is rejected, then sending one
with a valid token and confirming it succeeds.

**Acceptance Scenarios**:

1. **Given** a deployed model endpoint, **When** an unauthenticated
   request is sent, **Then** the request is rejected with a 401/403
   response.
2. **Given** a deployed model endpoint, **When** a request with a
   valid authentication token is sent, **Then** the prediction is
   returned successfully.
3. **Given** a pipeline DAG calling the inference endpoint, **When**
   the pipeline has valid service credentials, **Then** the inference
   call succeeds without manual token management.

---

### Edge Cases

- What happens when the model endpoint is under heavy load? The
  endpoint queues requests and responds within timeout limits; if
  overwhelmed, returns a 503 with a "service overloaded" message.
- What happens when the underlying model version is deleted from the
  registry while the endpoint is live? The endpoint continues serving
  with the already-deployed model; it does not auto-update or crash.
- What happens when the user deploys a model format that the serving
  system does not support? Deployment fails with a clear error
  explaining the unsupported format.
- What happens when the cluster runs out of resources for the serving
  pod? Deployment stays in "pending" state and the user sees a
  "waiting for resources" status.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST enable the MLflow Model Registry (built
  into the MLflow tracking server from feature 003) for users to
  register trained models with version numbers.
- **FR-001a**: The system MUST allow users to deploy a registered model
  version from the MLflow Model Registry to a live KServe inference
  endpoint via the portal UI.
- **FR-002**: The inference endpoint MUST accept structured input data
  and return predictions in a structured response format.
- **FR-003**: The inference endpoint MUST be accessible via HTTP(S)
  from within the cluster (for pipeline DAGs) and optionally from the
  portal backend.
- **FR-004**: The inference endpoint MUST require authentication —
  unauthenticated requests MUST be rejected.
- **FR-005**: The system MUST provide a way to check the status of a
  deployed endpoint (deploying, ready, failed).
- **FR-006**: The system MUST allow pipeline DAGs to make inference
  calls to deployed endpoints.
- **FR-007**: The serving system MUST use KServe to run inference pods
  on the Kubernetes cluster with resource limits. KServe
  InferenceService CRDs manage the model serving lifecycle.
- **FR-008**: The system MUST support deploying models logged in the
  standard mlflow model format (at minimum, scikit-learn models).

### Key Entities

- **Model Version**: A specific trained model artifact registered in
  the MLflow Model Registry. Has a version number, stage (None/Staging/
  Production/Archived), and associated metadata. Model artifacts are
  stored in MinIO.
- **Inference Endpoint**: An HTTP service serving predictions for a
  specific model version. Has a URL, status (deploying/ready/failed),
  and resource allocation.
- **Prediction Request**: An input payload sent to the inference
  endpoint. Contains feature values matching the model's expected
  input schema.
- **Prediction Response**: The output from the inference endpoint.
  Contains predicted values and optionally confidence scores.
- **Inference DAG**: A pipeline workflow that sends data to an
  inference endpoint and captures results.

### Assumptions

- The model serving system (KServe) runs on the same Kubernetes
  cluster in raw deployment mode. No Knative Serving or Istio is
  required — KServe creates plain K8s Deployments and ClusterIP
  Services for inference endpoints.
- For MVP, only scikit-learn model format needs to be supported for
  serving.
- The inference endpoint URL is discoverable by pipeline DAGs via
  environment configuration or the portal backend API.
- Scale-to-zero and canary deployments are out of scope for MVP.
- A single model endpoint per model version is sufficient for MVP (no
  A/B testing).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can deploy a registered model and have the
  inference endpoint ready to serve within 5 minutes.
- **SC-002**: An inference request with valid input returns a
  prediction response in under 2 seconds.
- **SC-003**: An unauthenticated inference request is rejected with an
  appropriate error response.
- **SC-004**: A pipeline DAG can call the inference endpoint, receive
  predictions, and complete the pipeline run with results captured.
- **SC-005**: The deployed model endpoint remains available and serves
  correct predictions over at least 1 hour of continuous availability.
