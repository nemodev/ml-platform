# Quickstart: Notebook Resource Profiles

## Prerequisites

- Backend running (`cd backend && ./gradlew bootRun`)
- Frontend running (`cd frontend && npm start`)
- JupyterHub deployed with updated pre_spawn_hook (see Infrastructure section)
- For resource metrics (P3): `metrics-server` installed on the cluster

## Configuration

### 1. Define resource profiles in backend

Edit `backend/src/main/resources/application.yaml`:

```yaml
workspace:
  default-notebook: ${WORKSPACE_DEFAULT_NOTEBOOK:}
  profiles:
    - id: exploratory
      name: Exploratory
      description: Interactive data exploration and small experiments
      default: true
      cpu-request: "1"
      cpu-limit: "2"
      memory-request: "2G"
      memory-limit: "4G"
    - id: standard
      name: Standard
      description: Typical ML workflows and medium-sized datasets
      cpu-request: "2"
      cpu-limit: "4"
      memory-request: "4G"
      memory-limit: "8G"
    - id: compute-intensive
      name: Compute-Intensive
      description: Large dataset processing and model training
      cpu-request: "4"
      cpu-limit: "8"
      memory-request: "8G"
      memory-limit: "16G"
```

### 2. Update JupyterHub pre_spawn_hook

In `infrastructure/helm/jupyterhub/values.yaml`, extend the `pre_spawn_hook` to apply resource overrides from user_options:

```python
async def pre_spawn_hook(spawner):
    username = spawner.user.name
    spawner.environment['MLFLOW_MODEL_PREFIX'] = f'{username}/'

    server_name = spawner.name
    if server_name:
        spawner.environment['ANALYSIS_ID'] = server_name
        spawner.environment['ANALYSIS_S3_PATH'] = f's3://ml-platform-data/ml-platform/analysis/{username}/{server_name}/'
        spawner.environment['MLFLOW_EXPERIMENT_PREFIX'] = f'{username}/{server_name}/'

    user_options = spawner.user_options or {}

    # Feature 008: Custom image override
    custom_image = user_options.get('image')
    if custom_image:
        spawner.image = custom_image

    # Feature 010: Resource profile override
    cpu_guarantee = user_options.get('cpu_guarantee')
    cpu_limit = user_options.get('cpu_limit')
    mem_guarantee = user_options.get('mem_guarantee')
    mem_limit = user_options.get('mem_limit')
    if cpu_guarantee is not None:
        spawner.cpu_guarantee = float(cpu_guarantee)
    if cpu_limit is not None:
        spawner.cpu_limit = float(cpu_limit)
    if mem_guarantee is not None:
        spawner.mem_guarantee = mem_guarantee
    if mem_limit is not None:
        spawner.mem_limit = mem_limit
```

## Verification

### API verification

```bash
# List profiles
curl -s http://localhost:8080/api/v1/analyses/{analysisId}/workspaces/profiles \
  -H "Authorization: Bearer $TOKEN" | jq .

# Launch with Standard profile
curl -s -X POST http://localhost:8080/api/v1/analyses/{analysisId}/workspaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"profile": "standard"}' | jq .

# Check metrics (P3)
curl -s http://localhost:8080/api/v1/analyses/{analysisId}/workspaces/metrics \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Frontend verification

1. Log in as `user1`/`password1`
2. Create or open an analysis
3. Navigate to the Notebooks tab
4. Verify the profile selector shows all three profiles with descriptions and resource info
5. Select "Standard" and launch
6. Once running, verify the active profile is displayed in the toolbar
7. Switch profile to "Compute-Intensive" — confirm the restart warning appears
8. After restart, verify the new profile is active

### Infrastructure verification

```bash
# Check pod resource limits after launch
kubectl get pod -n ml-platform -l component=singleuser-server -o json | \
  jq '.items[].spec.containers[0].resources'

# Verify metrics-server (for P3)
kubectl top pod -n ml-platform -l component=singleuser-server
```

## Files changed

### Backend
- `backend/src/main/java/com/mlplatform/config/WorkspaceProfileProperties.java` — NEW
- `backend/src/main/java/com/mlplatform/dto/ComputeProfileDto.java` — MODIFIED (add `default` field)
- `backend/src/main/java/com/mlplatform/dto/WorkspaceMetricsDto.java` — NEW (P3)
- `backend/src/main/java/com/mlplatform/service/WorkspaceService.java` — MODIFIED
- `backend/src/main/java/com/mlplatform/service/JupyterHubService.java` — MODIFIED
- `backend/src/main/java/com/mlplatform/controller/WorkspaceController.java` — MODIFIED (P3: metrics endpoint)
- `backend/src/main/resources/application.yaml` — MODIFIED

### Frontend
- `frontend/src/app/features/notebooks/notebooks.component.ts` — MODIFIED
- `frontend/src/app/features/notebooks/notebooks.component.html` — MODIFIED
- `frontend/src/app/features/notebooks/notebooks.component.scss` — MODIFIED
- `frontend/src/app/core/services/workspace.service.ts` — MODIFIED (P3: metrics method)

### Infrastructure
- `infrastructure/helm/jupyterhub/values.yaml` — MODIFIED (pre_spawn_hook)
