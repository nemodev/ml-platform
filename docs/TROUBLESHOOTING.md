# Troubleshooting Guide

Common issues encountered during ML Platform development and deployment, with root causes and fixes.

---

## 1. MLflow Dark Mode Persists Despite Configuration

**Symptom:** MLflow iframe renders in dark mode even after setting `databricks-dark-mode-pref=light` in localStorage.

**Root Cause:** MLflow 3.10.0 uses Databricks' "Dubois" CSS-in-JS (Emotion) design system. The theme is determined by reading `_mlflow_dark_mode_toggle_enabled` from localStorage **first** (`"true"` = dark, `"false"` = light). It only falls back to `window.matchMedia("(prefers-color-scheme: dark)")` when that key is `null`. Setting `databricks-dark-mode-pref` alone is insufficient because the Dubois system checks `_mlflow_dark_mode_toggle_enabled` as the primary flag.

The relevant logic in the minified MLflow bundle (`main.js`):

```javascript
const e = localStorage.getItem(at);
return null !== e ? "true" === e : window.matchMedia("(prefers-color-scheme: dark)").matches || false
// where at = "_mlflow_dark_mode_toggle_enabled"
```

**Fix:** Use nginx `sub_filter` to inject a `<script>` before `</head>` that:
1. Intercepts `localStorage.setItem` calls to force both keys to their light-mode values
2. Pre-sets the keys before React initializes

```nginx
proxy_set_header Accept-Encoding "";
sub_filter_once on;
sub_filter_types text/html;
sub_filter '</head>' '<script>var _si=localStorage.setItem.bind(localStorage);localStorage.setItem=function(k,v){if(k==="_mlflow_dark_mode_toggle_enabled")v="false";if(k==="databricks-dark-mode-pref")v="light";return _si(k,v);};localStorage.setItem("_mlflow_dark_mode_toggle_enabled","false");localStorage.setItem("databricks-dark-mode-pref","light");</script></head>';
```

The `proxy_set_header Accept-Encoding ""` disables gzip so `sub_filter` can operate on plain text.

**Files:** `frontend/nginx/nginx.conf.template`, `infrastructure/k8s/platform/base/frontend-nginx-template-configmap.yaml`

---

## 2. MLflow Theme Fix Not Visible After Deployment

**Symptom:** After deploying the nginx `sub_filter` fix, users still see dark mode in their browser.

**Root Cause:** The browser cached the old MLflow HTML response (without the injected script). MLflow's upstream sends cache-friendly headers, and browsers honor them.

**Diagnosis:**
```bash
# Verify the script IS being injected (bypasses browser cache):
curl -s http://172.16.100.10:30080/mlflow/ | grep '_mlflow_dark_mode_toggle_enabled'
```

**Fix:** Add `no-store` cache headers to the MLflow proxy location so browsers always fetch fresh HTML:

```nginx
proxy_hide_header Cache-Control;
add_header Cache-Control "no-store" always;
```

This only affects the HTML document; static assets (JS, CSS, images) are still cached normally by the browser.

**Files:** Same as above — `nginx.conf.template` and `frontend-nginx-template-configmap.yaml`

---

## 3. nginx ConfigMap vs Docker Image Mismatch

**Symptom:** nginx config changes in `frontend/nginx/nginx.conf.template` don't take effect after deployment.

**Root Cause:** The frontend deployment mounts `frontend-nginx-template` ConfigMap at `/etc/nginx/templates/`, overriding the template baked into the Docker image. If only the Docker image is updated but the ConfigMap still has the old config, the pod uses the old ConfigMap version.

**Fix:** Always update **both**:
1. `frontend/nginx/nginx.conf.template` (source of truth, baked into Docker image)
2. `infrastructure/k8s/platform/base/frontend-nginx-template-configmap.yaml` (runtime override)

After updating the ConfigMap:
```bash
kubectl apply -f infrastructure/k8s/platform/base/frontend-nginx-template-configmap.yaml -n ml-platform
kubectl rollout restart deployment/frontend -n ml-platform
```

---

## 4. Keycloak 26 Username Change Blocked

**Symptom:** Attempting to change a username via the Keycloak Admin REST API returns:
```
400 Bad Request: {"error":"error-user-attribute-read-only"}
```

**Root Cause:** Keycloak 26 marks `username` as a read-only attribute in the default user profile configuration. Even when the profile config shows `"edit": ["admin"]`, the Admin API still rejects username changes.

**Fix (direct PostgreSQL update):**
```bash
# Access Keycloak's PostgreSQL
kubectl exec -it postgresql-0 -n ml-platform -- psql -U keycloak -d keycloak

# Update username
UPDATE user_entity SET username='newuser', email='newuser@ml-platform.local'
  WHERE id='<keycloak-user-uuid>';

# Restart Keycloak to clear caches
kubectl rollout restart deployment/keycloak -n ml-platform
```

**Important:** Also update all dependent systems:
- Backend `users` table (`oidc_subject` should be the Keycloak user UUID, not the old username)
- JupyterHub user records (delete old user — recreated on next OIDC login)
- MLflow experiment prefixes (rename from `olduser/` to `newuser/`)
- Keycloak realm ConfigMap (for future re-imports)

---

## 5. User Identity Mismatch After Username Change

**Symptom:** After changing a Keycloak username, the platform creates a duplicate user record and existing analyses/workspaces are no longer visible.

**Root Cause:** The backend's `UserService.syncFromJwt()` identifies users by `oidc_subject` (the Keycloak `sub` JWT claim = Keycloak user UUID). If the existing user record has a stale `oidc_subject` (e.g., the old username string instead of the UUID), `syncFromJwt()` creates a new user record.

**Diagnosis:**
```sql
-- Check users table for duplicates
SELECT id, username, oidc_subject FROM users ORDER BY created_at;
```

**Fix:** Ensure the `oidc_subject` column matches the Keycloak user UUID:
```sql
UPDATE users SET oidc_subject = '<keycloak-user-uuid>' WHERE username = 'newuser';
```

If data is expendable, the cleanest approach is to delete old users and let `syncFromJwt()` recreate them on next login:
```sql
DELETE FROM workspaces WHERE user_id IN (SELECT id FROM users WHERE username = 'olduser');
DELETE FROM analyses WHERE user_id IN (SELECT id FROM users WHERE username = 'olduser');
DELETE FROM users WHERE username = 'olduser';
```

---

## 6. JupyterHub Named Server Pod Fails to Spawn

**Symptom:** Workspace launch hangs at "Starting notebook server..." or returns an error.

**Diagnosis:**
```bash
# Check JupyterHub logs
kubectl logs deploy/hub -n ml-platform --tail=50

# Check if the pod was created
kubectl get pods -n ml-platform -l component=singleuser-server

# Check pod events
kubectl describe pod jupyter-<username>--<server-name> -n ml-platform
```

**Common causes:**
- **Image pull failure:** The notebook image tag expired (ttl.sh images expire after 24h). Rebuild and push a new image.
- **PVC binding:** StorageClass may not support dynamic provisioning. Check `kubectl get pvc -n ml-platform`.
- **Resource limits:** The node may not have enough resources. Check `kubectl describe node`.

---

## 7. MLflow CORS Errors in iframe

**Symptom:** MLflow iframe shows errors or fails to load with CORS-related messages in the browser console.

**Root Cause:** MLflow's built-in CORS check rejects requests when the `Origin` header doesn't match its configured origins. When served through nginx as a reverse proxy, the browser sends the portal's origin.

**Fix:** Strip the `Origin` header in the nginx proxy config so MLflow sees the request as same-origin:

```nginx
location /mlflow/ {
    proxy_set_header Origin "";
    # ... other proxy settings
}
```

**File:** `frontend/nginx/nginx.conf.template`

---

## 8. curl Not Available in Pods

**Symptom:** `kubectl exec deploy/<service> -- curl ...` fails with "curl not found".

**Root Cause:** Minimal container images (distroless, alpine-based) don't include curl.

**Workaround options:**
1. **Port-forward:** Access the service from your local machine:
   ```bash
   kubectl port-forward svc/mlflow 5000:5000 -n ml-platform &
   curl http://localhost:5000/api/2.0/mlflow/experiments/search
   ```
2. **NodePort:** If the service is exposed via NodePort, access directly:
   ```bash
   curl http://172.16.100.10:30080/mlflow/api/2.0/mlflow/experiments/search
   ```
3. **Debug pod:** Run a temporary pod with curl:
   ```bash
   kubectl run debug --rm -it --image=curlimages/curl -n ml-platform -- sh
   ```

---

## 9. MLflow API Requires max_results

**Symptom:** MLflow experiments/search API returns error:
```
Invalid value 0 for parameter 'max_results'
```

**Root Cause:** MLflow API requires `max_results` to be a positive integer. An empty `{}` body defaults to 0.

**Fix:** Always include `max_results` in the request body:
```bash
curl -X POST http://<mlflow>/api/2.0/mlflow/experiments/search \
  -H 'Content-Type: application/json' \
  -d '{"max_results": 100}'
```

---

## 10. Angular Event Binding Not Responding to Programmatic Clicks

**Symptom:** Clicking buttons via browser automation tools (DevTools MCP, Selenium, etc.) doesn't trigger Angular event handlers, even though the DOM click fires.

**Root Cause:** Angular's Zone.js may not detect clicks dispatched outside Angular's zone. The `(click)` binding relies on Zone.js-patched event listeners.

**Workaround:** Use JavaScript `element.click()` instead of automation tool click commands:
```javascript
document.querySelector('.my-button').click();
```

For form inputs with `[(ngModel)]`, dispatch `input` and `change` events after setting the value:
```javascript
const input = document.getElementById('my-input');
input.value = 'new value';
input.dispatchEvent(new Event('input', { bubbles: true }));
input.dispatchEvent(new Event('change', { bubbles: true }));
```

---

## 11. KServe Storage-Initializer: "Cannot recognize storage type for models:/"

**Symptom:** KServe InferenceService pod stuck in `Init:CrashLoopBackOff`. Storage-initializer logs show:
```
Cannot recognize storage type for models:/m-<model_id>
'gs://', 's3://', 'file://', and 'http(s)://' are the current available storage type.
```

**Root Cause:** MLflow 3.x changed the model version `source` field from a raw S3 path to an internal registry URI (`models:/m-<model_id>`). The backend's `ServingService` passed this URI directly to KServe, but KServe's storage-initializer only understands `s3://`, `gs://`, `http://`, or `file://` protocols.

**Fix:** Added URI resolution in `ModelRegistryService.resolveModelStorageUri()`:
1. Calls MLflow's `GET /api/2.0/mlflow/model-versions/get-download-uri` API to get the `mlflow-artifacts:/` URI
2. Converts `mlflow-artifacts:/<path>` to `s3://ml-platform-mlflow/artifacts/<path>` using the configured `services.mlflow.artifact-destination`
3. `ServingService.deployModel()` now uses the resolved S3 URI instead of the raw `source` field

**Files:** `ModelRegistryService.java`, `ServingService.java`, `MlflowConfig.java`, `application.yaml`

---

## 12. MLServer scikit-learn Version Mismatch

**Symptom:** KServe InferenceService pod crashes after loading model. MLServer logs show:
```
ValueError: node array from the pickle has an incompatible dtype
```

**Root Cause:** The default KServe `ClusterServingRuntime` for `kserve-mlserver` uses `seldonio/mlserver:1.3.2` which bundles scikit-learn 1.2.2 and Python 3.8. Models trained with scikit-learn 1.6.x have an incompatible pickle format (new `missing_go_to_left` field in decision tree nodes).

**Fix:** Update the `kserve-mlserver` ClusterServingRuntime to use MLServer 1.6.1:
```bash
kubectl patch clusterservingruntime kserve-mlserver \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/containers/0/image","value":"docker.io/seldonio/mlserver:1.6.1"}]'
```

MLServer 1.6.1 bundles scikit-learn 1.5.0 and Python 3.10, which is compatible enough with 1.6.x pickles (produces warnings but loads successfully).

---

## 13. MLServer MLflow Runtime: TypeError on V2 Inference

**Symptom:** V2 inference request returns:
```
TypeError: float() argument must be a string or a real number, not 'InferenceRequest'
```

**Root Cause:** MLServer's `mlserver-mlflow` runtime needs a `content_type` hint to know how to decode V2 inputs for the underlying sklearn model. Without the hint, the runtime passes the raw `InferenceRequest` object to the model's `predict()` instead of converting it to a numpy array.

**Fix:** Include `"parameters": {"content_type": "np"}` at the top level of the V2 inference request:
```json
{
  "inputs": [
    {"name": "predict", "shape": [1, 8], "datatype": "FP64", "data": [[8.3252, 41, ...]]}
  ],
  "parameters": {"content_type": "np"}
}
```

The backend's `PredictionRequestDto` was also updated to accept and pass through the `parameters` field to the KServe proxy call.

**Files:** `PredictionRequestDto.java`, `ServingService.java`, `predict-dialog.component.ts`

---

## Quick Reference: Common kubectl Commands

```bash
# Restart a deployment after config changes
kubectl rollout restart deployment/<name> -n ml-platform

# Apply a ConfigMap update
kubectl apply -f <configmap-file> -n ml-platform

# Check pod logs
kubectl logs deploy/<name> -n ml-platform --tail=100

# Access PostgreSQL
kubectl exec -it postgresql-0 -n ml-platform -- psql -U <db-user> -d <db-name>

# List all pods with status
kubectl get pods -n ml-platform -o wide

# Check recent events
kubectl get events -n ml-platform --sort-by='.lastTimestamp' | tail -20
```

## Environment Details

| Component | Access |
|-----------|--------|
| Platform portal | `http://172.16.100.10:30080/` |
| MLflow (via proxy) | `http://172.16.100.10:30080/mlflow/` |
| JupyterHub | `http://172.16.100.10:30080/hub/` |
| Keycloak admin | `http://172.16.100.10:30080/auth/admin/` |
| PostgreSQL | `kubectl exec -it postgresql-0 -n ml-platform -- psql` |
| Platform DB password | `localdevpassword` |
| Test users | `user1/password1`, `user2/password2` |
