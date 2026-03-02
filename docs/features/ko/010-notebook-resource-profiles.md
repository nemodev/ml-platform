# Feature 010: Notebook Resource Profiles

> 사용자가 workspace의 CPU/메모리 리소스 프로파일을 선택하고, JupyterHub의 KubeSpawner를 통해 적용하며, Kubernetes Metrics API에서 실시간 사용량 메트릭을 표시한다.

## 개요 및 배경

모든 ML 작업에 동일한 리소스가 필요한 것은 아니다. 작은 CSV에 대한 데이터 탐색은 대규모 데이터셋에서 gradient-boosted 모델을 학습하는 것보다 훨씬 적은 CPU와 메모리가 필요하다. 프로파일 없이는, 모든 workspace가 동일한 고정 할당을 받는다 — 가벼운 작업에는 낭비이거나 무거운 계산에는 부족하다. Feature 010은 사용자가 실행 시 선택하는 설정 기반 리소스 프로파일을 도입한다. 프로파일은 `application.yaml`에 정의되어 (데이터베이스가 아님), 버전 관리되고 간단하게 유지된다. 실시간 리소스 메트릭으로 사용자가 올바른 프로파일을 선택했는지 확인할 수 있다. 이것은 workspace 생명주기 체인의 마지막 Feature로, Feature 002, 008, 007의 spawn 플로우를 확장한다.

## 아키텍처

```
Portal (profile selector in notebooks toolbar)
    ↓ GET /api/v1/analyses/{id}/workspaces/profiles
    ↓ POST /api/v1/analyses/{id}/workspaces {profile: "standard"}
Backend (WorkspaceService)
    ├── Resolve profile from WorkspaceProfileProperties
    ├── Build spawn options: cpu_guarantee, cpu_limit, mem_guarantee, mem_limit
    └── Pass spawn options to JupyterHubService.spawnNamedServer()
        ↓
JupyterHub (pre_spawn_hook in values.yaml)
    ├── Read user_options: cpu_guarantee, cpu_limit, mem_guarantee, mem_limit
    └── Override spawner.cpu_guarantee, cpu_limit, mem_guarantee, mem_limit
        ↓
KubeSpawner → Notebook Pod (with resource requests/limits applied)

Portal (metrics display)
    ↓ GET /api/v1/analyses/{id}/workspaces/metrics (every 15s)
Backend (WorkspaceService)
    ↓ Kubernetes Metrics API (metrics.k8s.io/v1beta1)
    ↓ Parse CPU nanocores + memory bytes
Portal (usage bars: CPU 0.5/2.0, Memory 1.2G/4G)
```

**주요 설계 결정:**

- **데이터베이스가 아닌 설정 기반 프로파일** — 프로파일이 `application.yaml`의 `workspace.profiles` 아래에 정의된다. 런타임에 불변, 버전 관리됨, 스키마 마이그레이션 없음. 프로파일 추가에 backend 재시작이 필요하다는 트레이드오프가 있지만 — 프로파일 정의가 드물게 변경되는 PoC에서는 허용 가능하다.
- **기존 user_options를 통한 spawn options** — Feature 008이 커스텀 이미지에 사용하는 동일한 `user_options` 메커니즘을 재사용한다. `pre_spawn_hook`이 user_options에서 `cpu_guarantee`, `cpu_limit`, `mem_guarantee`, `mem_limit`를 읽어 spawner에 적용한다. 새로운 JupyterHub 확장 프로그램 불필요.
- **프로파일 전환 시 종료 후 재실행** — Kubernetes는 pod의 in-place 리소스 변경을 지원하지 않는다. 프로파일 전환은 workspace를 종료하고 새 리소스 한도로 재실행해야 한다. 프론트엔드가 이를 확인 다이얼로그와 함께 단일 사용자 동작으로 처리한다.
- **Prometheus가 아닌 Kubernetes Metrics API** — `metrics.k8s.io/v1beta1` API가 모든 표준 클러스터에 이미 배포된 `metrics-server`에서 즉시 CPU/메모리 사용량을 제공한다. 새로운 인프라 의존성 없음. 시점 표시에는 충분하지만, 이력 추세에는 부족하다.
- **데이터베이스 마이그레이션 없음** — `workspace.profile` 컬럼이 이전 스키마 설계에서 이미 존재했다. Feature 추가에 대해 새 마이그레이션이 필요 없다는 것은 드문 경우이다.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `config/WorkspaceProfileProperties.java` | 시작 검증을 포함한 `@ConfigurationProperties` |
| Backend | `service/WorkspaceService.java` | 프로파일 해석, spawn options 구성, 메트릭 조회 |
| Backend | `controller/WorkspaceController.java` | `/profiles` 및 `/metrics` 엔드포인트 |
| Backend | `application.yaml` | 리소스 한도가 있는 3개 프로파일 정의 |
| Frontend | `features/notebooks/notebooks.component.ts` | 프로파일 선택기, 전환 플로우, 메트릭 폴링 |
| Frontend | `core/services/workspace.service.ts` | 프로파일, 실행, 메트릭용 HTTP 클라이언트 |
| Infra | `helm/jupyterhub/values.yaml` | `pre_spawn_hook` 리소스 오버라이드 로직 |

**3개의 사전 정의된 프로파일:** Exploratory (1-2 CPU, 2-4G RAM, 기본값), Standard (2-4 CPU, 4-8G RAM), Compute-Intensive (4-8 CPU, 8-16G RAM). 각각 Kubernetes 리소스 모델 관습에 따라 request(보장)와 limit(버스트 가능) 값을 별도로 정의한다.

**시작 검증:** `WorkspaceProfileProperties`가 `@PostConstruct`에서 검증한다: 최소 하나의 프로파일 존재, 중복 ID 없음 (대소문자 무시), 각각 비어있지 않은 ID 보유, 정확히 하나가 기본값으로 표시. 검증 실패 시 애플리케이션이 시작되지 않는다 — 잘못된 설정에 대한 fail-fast.

**프로파일 해석 플로우:** `WorkspaceService.launchWorkspace()`가 프로파일 ID를 소문자로 정규화하고, `WorkspaceProfileProperties`에서 조회하며, 찾을 수 없으면 사용 가능한 프로파일 목록과 함께 400 BAD_REQUEST를 throw한다. 해석된 프로파일의 리소스 값이 JupyterHub 관습에 맞는 snake_case 키(`cpu_guarantee`, `cpu_limit`, `mem_guarantee`, `mem_limit`)로 spawn options 맵에 담겨, `JupyterHubService.spawnNamedServer()`에 전달된다.

**JupyterHub pre_spawn_hook:** `values.yaml`의 hook이 `spawner.user_options`에서 리소스 값을 읽고 타입 변환과 함께 적용한다 — CPU 값은 `float`로 캐스팅 (KubeSpawner가 float을 기대), 메모리는 문자열 유지 (KubeSpawner가 "4G"와 같은 Kubernetes 형식을 수용). user_options가 제공되지 않으면 (예: 이전 workspace 레코드), JupyterHub의 `singleuser` 기본값이 적용된다 (1 CPU, 2G RAM — Exploratory 프로파일과 일치).

**프론트엔드의 프로파일 전환:** workspace가 RUNNING 또는 IDLE 상태에서 사용자가 다른 프로파일을 선택하면, `onProfileChange()`가 커널이 중단될 것이라는 확인 다이얼로그를 표시한다. 확인 시, `switchingProfile = true`를 설정하고, workspace를 종료(DELETE)하고, 종료를 대기하고, 새 프로파일로 재실행(POST)하고, RUNNING까지 폴링한 후, 플래그를 해제한다. workspace가 STOPPED 또는 FAILED이면, 다음 실행을 위해 `selectedProfileId`만 업데이트한다.

**메트릭 통합:** workspace가 실행 중일 때 프론트엔드가 15초마다 `/workspaces/metrics`를 폴링한다. Backend가 `CustomObjectsApi.getNamespacedCustomObject()`를 통해 `metrics.k8s.io/v1beta1`을 조회하여 pod의 컨테이너 메트릭을 파싱한다. CPU 값은 nanocores (`500000000n` → `0.5`) 또는 millicores (`500m` → `0.5`)로 온다. 메모리는 Ki/Mi/Gi로 온다. 응답에 사용량과 한도가 모두 포함되어 프론트엔드가 사용량 바를 렌더링할 수 있다. metrics-server가 사용 불가하면, 엔드포인트가 `metricsAvailable: false`와 null 사용량 값을 반환한다 — 우아한 성능 저하.

## 과제 및 해결

- **JupyterHub 경계에서의 CPU 타입 변환** — KubeSpawner가 `cpu_guarantee`를 Python float으로 기대하지만, user_options는 JupyterHub API에서 문자열로 도착한다. `pre_spawn_hook`이 `float()`로 명시적 캐스팅한다. 메모리는 KubeSpawner가 Kubernetes에 직접 전달하므로 문자열 유지.
- **동시 프로파일 전환 경쟁** — 프론트엔드의 `switchingProfile` 플래그가 전환 중 프로파일 선택기를 비활성화하고, backend의 409 CONFLICT 확인이 중복 실행을 방지한다. 함께 빠른 프로파일 변경으로 인한 경쟁 조건을 방지한다.
- **Metrics API 형식 파싱** — Kubernetes가 CPU를 nanocores로, 메모리를 바이너리 접미사(Ki, Mi, Gi)로 반환한다. `WorkspaceService`에 양쪽 모두를 위한 형식별 파서가 포함된다. 알 수 없는 형식은 raw 문자열 표현으로 통과된다.
- **관리자에 의한 프로파일 제거** — 실행 중인 workspace가 사용하는 프로파일을 관리자가 제거하면, workspace는 계속 실행된다 (Kubernetes가 리소스를 회수하지 않음). 다음 재실행 시 사용자는 현재 프로파일에서 선택해야 한다. 마이그레이션 메커니즘 없음.

## 제한 사항

- **핫 리사이징 없음** — 프로파일 전환에 전체 workspace 재시작이 필요하다. In-place pod 리소스 변경은 Kubernetes에서 지원되지 않는다 (VPA 없이). 사용자가 커널 상태를 잃는다.
- **GPU 프로파일 범위 밖** — `gpuLimit` 필드가 DTO에 존재하지만 0으로 하드코딩됨. GPU node selector와 리소스 요청에 추가 Kubernetes 설정이 필요하다.
- **사용자별 프로파일 제한 없음** — 모든 프로파일이 모든 사용자에게 표시된다. Compute-Intensive 프로파일을 특정 사용자나 팀으로 제한하는 메커니즘 없음.
- **metrics-server 의존성** — `metrics-server`가 배포되지 않았거나 RBAC가 backend의 조회를 허용하지 않으면, 메트릭이 조용히 사용 불가로 성능 저하된다. 명시적인 health check나 경고 없음.
- **고정 15초 메트릭 폴링** — 적응형 백오프 없음. 부하 시 많은 동시 workspace가 메트릭을 폴링하면 metrics API에 압력을 추가할 수 있다.
- **프로파일 정의에 재시작 필요** — 프로파일이 `application.yaml`에 있으므로, 프로파일 추가나 수정에 backend 재시작이 필요하다. 런타임 설정 API 없음.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| 관리자 UI가 있는 데이터베이스 저장 프로파일 | 복잡성 추가 (마이그레이션, 관리 엔드포인트, CRUD UI). YAML 설정이 변경이 드문 PoC에 더 간단하다. |
| 메트릭용 Prometheus | Prometheus 스택 배포가 필요하다. Kubernetes Metrics API가 이미 사용 가능하고 시점 표시에 충분하다. |
| In-place pod 리사이징 (VPA) | Kubernetes VPA가 alpha/beta이고, KubeSpawner가 지원하지 않는다. 종료-재실행이 안정적이고 잘 이해되어 있다. |
| 슬라이더 기반 커스텀 리소스 | 너무 많은 자유가 과다 프로비저닝으로 이어진다. 큐레이션된 한도가 있는 이름 있는 프로파일이 사용자를 적절한 선택으로 안내한다. |
| 오토스케일링 프로파일 | 상당한 복잡성 추가 (모니터링, 결정 로직, pod 중단). 메트릭 표시가 있는 고정 프로파일이 사용자의 자기 서비스를 가능하게 한다. |

## 향후 개선 사항

- **GPU 프로파일** — GPU 노드가 있는 클러스터를 위해 node affinity 규칙과 함께 GPU 리소스 요청 추가.
- **프로파일 사용 쿼터** — 리소스 고갈을 방지하기 위해 팀 또는 사용자별 동시 Compute-Intensive workspace 제한.
- **이력 메트릭** — 추세 표시를 위한 메트릭 스냅샷 저장, 또는 시계열 데이터를 위한 Prometheus 통합.
- **동적 프로파일 관리** — Backend 재시작 없이 프로파일을 생성/수정할 수 있는 관리자 API.
- **리소스 추천** — 메트릭 이력을 분석하여 특정 워크로드에 최적의 프로파일을 제안.
