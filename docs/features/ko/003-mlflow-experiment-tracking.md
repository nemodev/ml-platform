# Feature 003: MLflow Experiment Tracking

> 사용자/analysis별 격리, CSS injection을 통한 iframe 임베딩 UI, 노트북-MLflow 통신을 위한 backend proxy를 갖춘 MLflow 실험 추적 통합.

## 개요 및 배경

데이터 과학자는 노트북에서 반복 작업하면서 실험(파라미터, 메트릭, 모델)을 추적해야 한다. MLflow가 사실상의 표준이므로, 커스텀 추적 시스템을 구축하는 대신 MLflow 3.10.0을 공유 서비스로 배포하고 플랫폼에 통합한다. 과제는 멀티테넌시이다: MLflow에는 내장 사용자 격리가 없다. 이를 username/analysis 접두사가 붙은 실험 이름으로 해결하며, backend proxy가 이를 강제한다. MLflow UI는 iframe을 통해 포털에 임베딩되고, CSS injection으로 중복 크롬을 제거한다 — 이것은 플랫폼의 3가지 iframe 패턴 중 두 번째이다 (JupyterLab은 Feature 007에서 postMessage bridge, Streamlit은 Feature 009에서 polling startup을 사용).

## 아키텍처

```
Notebook Pod
    ↓ mlflow.log_params(), mlflow.log_model()
    ↓ (MLFLOW_TRACKING_URI → backend proxy)
Backend (/api/v1/mlflow-proxy/**)
    ↓ (auto-prefixes experiment names with username/analysisId)
MLflow Tracking Server (ClusterIP, port 5000)
    ├── PostgreSQL (mlflow database) — metadata
    └── MinIO (ml-platform-mlflow bucket) — artifacts
```

**주요 설계 결정:**

- **사용자 접두사 실험 이름** — 실험은 MLflow에 `{username}/{analysisId}/{experiment-name}`으로 저장된다. Backend proxy가 자동으로 이 접두사를 추가하고 제거하므로, 사용자는 비수식 이름만 본다. MLflow 내부를 수정할 필요 없는 가장 단순한 격리 메커니즘이다.
- **Backend proxy 패턴** — 노트북은 MLflow API 호출을 MLflow가 아닌 backend(`/api/v1/mlflow-proxy/**`)로 보낸다. Proxy가 실험 이름을 재작성하고, 사용자 접두사를 추가하며, 응답을 필터링한다. OAuth2 sidecar를 배포하지 않고 접근 제어를 중앙화한다.
- **iframe 커스터마이징을 위한 CSS injection** — Angular 컴포넌트가 MLflow iframe의 `contentDocument`에 `<style>` 요소를 주입하여 사이드바, 헤더 breadcrumb를 숨기고 라이트 테마를 강제한다. MLflow의 DOM 구조가 변경되면 깨질 수 있지만, MLflow 자체를 수정하는 것을 피한다.
- **`--serve-artifacts` 플래그** — MLflow가 클라이언트에 직접 MinIO 접근을 제공하는 대신 자체 API를 통해 아티팩트를 서빙한다. 자격 증명 관리가 단순해진다 — MLflow만 MinIO 자격 증명이 필요하다.
- **네트워크 격리** — MLflow는 ClusterIP로 실행된다 (외부 접근 불가). 모든 접근은 backend proxy 또는 in-cluster DNS를 통한다.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `controller/ExperimentController.java` | CRUD 엔드포인트 + 와일드카드 proxy (`/mlflow-proxy/**`) |
| Backend | `service/MlflowService.java` | 사용자 접두사 관리를 포함한 MLflow REST API 클라이언트 |
| Backend | `config/MlflowConfig.java` | 5s 연결 / 60s 읽기 타임아웃의 RestTemplate |
| Backend | `service/ModelRegistryService.java` | Feature 006용 Model Registry API |
| Frontend | `features/experiments/experiments.component.ts` | Iframe 임베딩, CSS injection, localStorage 프리셋 |
| Frontend | `core/services/experiment.service.ts` | Analysis 범위의 HTTP 클라이언트 |
| Infra | `helm/mlflow/values.yaml` | MLflow 3.10.0 설정, PostgreSQL, MinIO 백엔드 |
| Infra | `helm/mlflow/templates/deployment.yaml` | psycopg2 + boto3를 설치하는 InitContainer |

**Proxy 재작성:** `ExperimentController`가 특정 MLflow API 경로를 인터셉트한다. `experiments/create`에서는 사용자/analysis 접두사를 주입하도록 요청 본문을 재작성한다. `get-by-name`에서는 쿼리 파라미터를 재작성한다. 검색 엔드포인트에서는 사용자 접두사와 일치하는 실험만 필터링한 후 이름에서 접두사를 제거한다. 헤더 필터링은 누출을 방지하기 위해 Host, Authorization, transfer-encoding 헤더를 제거한다.

**CSS injection 접근 방식:** experiments 컴포넌트는 iframe 로드 시 `hideMlflowSidebar()`, `hideMlflowHeader()`, `forceLightTheme()`를 호출한다. 각 메서드는 특정 MLflow DOM 셀렉터(`aside`, `[role="complementary"]`, `main > div > div:first-child`)를 대상으로 `<style>` 요소를 주입한다. 500ms 재시도가 React 재렌더링을 처리한다. 테마 제어를 위해, 컴포넌트는 iframe 로딩 전에 localStorage 키(`_mlflow_dark_mode_toggle_enabled=false`, `mlflow.workflowType_v1=model_training`)를 사전 설정한다. 다크 모드 수정 상세는 `docs/TROUBLESHOOTING.md` 항목 1–2 참조.

**MLflow Helm 배포:** 커스텀 Helm chart는 공식 MLflow Docker 이미지에 `psycopg2-binary`와 `boto3`를 공유 `emptyDir` 볼륨(`PYTHONPATH`에 추가)에 설치하는 initContainer를 사용한다. 커스텀 MLflow 이미지 빌드를 피한다. MinIO용 AWS 자격 증명은 K8s Secret에서 주입된다.

## 과제 및 해결

- **임베딩된 iframe에서의 MLflow 다크 모드** — MLflow 3.10.0의 Dubois CSS-in-JS가 `window.matchMedia` 전에 localStorage에서 `_mlflow_dark_mode_toggle_enabled`를 읽는다. nginx `sub_filter`를 통해 `<script>`를 주입하여 `localStorage.setItem`을 인터셉트하고 라이트 모드 값을 강제한다. 오래된 HTML 방지를 위해 `no-store` 캐시 헤더도 필요하다.
- **iframe에서의 MLflow CORS** — MLflow가 포털 `Origin` 헤더가 있는 요청을 거부한다. 해결: nginx가 `proxy_set_header Origin ""`로 Origin 헤더를 제거.
- **MLflow API가 max_results를 요구** — `/experiments/search`에 빈 POST 본문을 보내면 `max_results`가 0으로 기본 설정되어 실패한다. Backend가 항상 `"max_results": 100`을 포함한다.
- **MLflow 3.x model URI 변경** — 모델 버전 `source` 필드가 S3 경로에서 `models:/m-{id}` registry URI로 변경되었다. `ModelRegistryService.resolveModelStorageUri()`가 download-uri API를 호출하여 실제 S3 경로로 변환한다. Feature 006에 필수적이다 (KServe는 S3 URI가 필요).

## 제한 사항

- **CSS 셀렉터 취약성** — iframe 커스터마이징이 특정 MLflow DOM 구조(`main > div > div:nth-child(2)`, `aside`)를 대상으로 한다. MLflow UI 업데이트 시 이 셀렉터들이 깨진다.
- **실험 삭제 없음** — 플랫폼이 MLflow의 실험 삭제 API를 노출하지 않는다. 실험이 무한히 누적된다.
- **대소문자 구분 접두사 필터링** — 프로덕션 필터링은 정확한 대소문자 구분 접두사 매칭을 사용하고, dev profile은 대소문자 무시를 사용한다. 사소한 불일치이다.
- **일시적 MLflow 장애에 대한 재시도 로직 없음** — `MlflowService`가 circuit breaker나 재시도를 구현하지 않는다. MLflow가 잠시 사용 불가하면 요청이 즉시 실패한다.
- **Helm values에 하드코딩된 PostgreSQL 비밀번호** — `values.yaml`의 `localdevpassword`는 Secret 참조여야 한다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| MLflow용 OAuth2 proxy sidecar | 인프라 복잡성 증가. Backend proxy가 더 간단하고 실험 필터링에 대한 더 세밀한 제어를 제공한다. |
| MLflow 내장 인증 | MLflow의 인증 플러그인은 실험적이고 Keycloak OIDC를 네이티브로 지원하지 않는다. |
| Weights & Biases / Neptune | 상용 제품은 PoC에 비용과 외부 의존성을 추가한다. |
| 커스텀 추적 백엔드 | 바퀴를 재발명하는 것이다. MLflow가 표준이고, 실제 프로젝트에서도 사용할 가능성이 높다. |
| 노트북에서 MLflow 직접 접근 | Proxy 레이어 없이 사용자 격리를 강제할 수 없다. 노트북에 관리자 MinIO 자격 증명이 필요해진다. |

## 향후 개선 사항

- **서버 측 CSS/테마 설정** — 더 안정적인 iframe 커스터마이징을 위해 클라이언트 측 CSS injection 대신 MLflow의 `--static-prefix` 또는 커스텀 nginx 재작성 사용.
- **MLflow 호출용 circuit breaker** — 일시적 MLflow/MinIO 장애를 우아하게 처리하기 위해 resilience4j 또는 Spring Retry 추가.
- **실험 보관/삭제** — 정리를 위해 포털을 통해 MLflow의 생명주기 관리 노출.
- **아티팩트 탐색** — MLflow iframe만이 아닌, 포털에서 직접 실험 아티팩트(플롯, 모델) 탐색 허용.
- **배치 실험 작업** — Analysis 간 실험 비교 또는 보고를 위한 메트릭 내보내기 지원.
