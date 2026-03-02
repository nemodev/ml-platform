# Feature 006: Model Serving & Inference

> 학습된 모델을 KServe Standard (non-Knative) 모드를 통해 REST 추론 엔드포인트로 배포하며, V2 inference protocol과 backend 프록시 예측을 사용한다.

## 개요 및 배경

ML 워크플로우의 최종 목표는 예측을 서빙하는 배포된 모델이다. 노트북에서 학습하고 (Feature 002), 실험을 추적하고 (Feature 003), 파이프라인으로 프로덕션화한 (Feature 005) 후, 사용자는 모델을 HTTP 엔드포인트로 배포해야 한다. Kubernetes 네이티브 모델 서빙 표준이고 여러 프레임워크를 지원하며 V2 inference protocol을 제공하는 KServe를 선택했다. 무거운 Knative/Istio 의존성 스택을 피하기 위해, Knative 모드 대신 **Standard 모드**(raw Kubernetes deployment)로 KServe를 실행한다 — YAGNI에 부합하는 PoC를 위한 실용적 선택이다.

## 아키텍처

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

**주요 설계 결정:**

- **KServe Standard 모드** — Knative, Istio, Knative Serving 없음. InferenceService CRD에 `serving.kserve.io/deploymentMode: Standard` 어노테이션을 부여하여, raw Kubernetes Deployment를 생성한다. 설치와 디버깅이 상당히 간단해진다.
- **전용 서빙 네임스페이스** — `ml-platform-serving`이 서빙 pod를 메인 `ml-platform` 네임스페이스와 분리한다. 더 깔끔한 RBAC와 리소스 격리.
- **CRD 관리를 위한 K8s Java Client** — `CustomObjectsApi`를 사용하여 InferenceService 커스텀 리소스를 생성, 조회, 삭제한다. 동일한 K8s 클라이언트 패턴이 Feature 008 (Kaniko)과 010 (메트릭)에서도 사용된다.
- **Backend 프록시 추론** — 예측이 backend를 통과하며, Kubernetes API server를 통해 프록시된다. KServe 서비스를 외부에 노출하지 않고 JWT 기반 접근 제어를 유지한다.
- **배포의 소프트 삭제** — `ModelDeployment`가 하드 삭제 대신 `deleted_at` 타임스탬프를 사용한다. `endpoint_name WHERE deleted_at IS NULL`에 대한 부분 유니크 인덱스로 삭제 후 이름 재사용이 가능하다 (V007 마이그레이션).
- **MLflow 아티팩트 URI 해석** — MLflow 3.x가 KServe가 다운로드할 수 없는 `models:/m-{id}` URI를 반환한다. `ModelRegistryService.resolveModelStorageUri()`가 MLflow의 download-uri API를 호출하여 실제 S3 경로로 변환한다.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `controller/ServingController.java` | 배포, 목록, 상세, 삭제, 예측 엔드포인트 |
| Backend | `service/ServingService.java` | 배포 생명주기, 상태 새로고침, 엔드포인트 이름 지정 |
| Backend | `service/KServeService.java` | K8s CRD 작업, pod 실패 감지, 추론 프록시 |
| Backend | `service/ModelRegistryService.java` | MLflow Model Registry API, URI 해석 |
| Backend | `config/KServeConfig.java` | K8s ApiClient bean, KServe 속성 |
| Backend | `model/ModelDeployment.java` | 소프트 삭제를 지원하는 JPA 엔티티 |
| Frontend | `features/models/models.component.ts` | Model Registry (MLflow iframe) + 엔드포인트 탭 |
| Frontend | `features/models/deploy-dialog/` | 버전 선택, 배포 트리거 |
| Frontend | `features/models/deployments/` | 5초 자동 새로고침이 있는 배포 목록 |
| Frontend | `features/models/predict-dialog/` | JSON 검증을 포함한 V2 inference 요청 편집기 |
| Infra | `k8s/kserve/serving-namespace.yaml` | 전용 네임스페이스 |
| Infra | `k8s/kserve/service-account.yaml` | MinIO 접근을 위한 `kserve-s3-sa` |
| Infra | `k8s/kserve/s3-secret.yaml` | KServe S3 어노테이션이 있는 MinIO 자격 증명 |

**상태 새로고침 패턴:** 모든 list/get 작업이 반환 전에 KServe에서 배포 상태를 새로고침한다. `ServingService.refreshDeploymentStatus()`가 InferenceService CRD를 가져오고, `Ready` 조건을 확인하며, `detectPodFailureMessage()`를 통해 pod 실패(이미지 풀 오류, 크래시 루프)를 감지한다. 사용자가 항상 오래된 DB 상태가 아닌 실제 KServe 상태를 보도록 보장한다.

**엔드포인트 이름 지정:** `ServingService.buildEndpointName()`이 `{username}-{modelName}-v{version}`에서 DNS 호환 이름을 생성하며, 소문자 영숫자 + 하이픈으로 정규화하고, KServe의 `-predictor-` 접미사를 위한 공간을 남겨 최대 63자이다.

**추론 프록시:** `KServeService.proxyPredict()`가 Kubernetes API server를 통해 라우팅한다: `/api/v1/namespaces/{ns}/services/{serviceName}:80/proxy/v2/models/{endpointName}/infer`. KServe 이름 지정 변형을 처리하기 위해 `{endpointName}`과 `{endpointName}-predictor` 서비스 이름을 모두 시도한다. "shape", "feature", "datatype" 등의 키워드가 포함된 오류 응답은 더 나은 사용자 피드백을 위해 500에서 400 BAD_REQUEST로 재매핑된다.

**MLflow Model Registry 통합:** `ModelsComponent`가 iframe을 통해 MLflow Model Registry UI를 임베딩한다 (Feature 003과 동일한 CSS injection 패턴). "Model training" 모드를 표시하도록 localStorage를 사전 설정하고 라이트 테마를 강제한다. 배포 다이얼로그가 `ModelService`에서 모델 버전을 로드하고, 사용자가 배포할 버전을 선택한다.

## 과제 및 해결

- **MLflow 3.x URI 형식 변경** — 모델 버전 `source`가 S3 경로에서 `models:/m-{id}`로 변경되었다. KServe의 storage initializer가 이 URI에서 다운로드할 수 없다. 해결: `resolveModelStorageUri()`가 MLflow의 download-uri API를 호출하여 `mlflow-artifacts:/{path}`를 `s3://ml-platform-mlflow/artifacts/{path}`로 변환. `docs/TROUBLESHOOTING.md` 항목 11 참조.
- **MLServer scikit-learn 버전 불일치** — 기본 MLServer 1.3.2가 scikit-learn 1.2.2를 번들하여, 1.6.x로 학습된 모델과 호환되지 않는다. 해결: MLServer 1.6.1을 사용하도록 `kserve-mlserver` ClusterServingRuntime 패치. `docs/TROUBLESHOOTING.md` 항목 12 참조.
- **V2 inference content_type 힌트** — MLServer가 sklearn 모델의 입력을 디코딩하기 위해 `"parameters": {"content_type": "np"}`가 필요하다. 이것 없이는 raw InferenceRequest 객체가 `predict()`에 전달된다. `docs/TROUBLESHOOTING.md` 항목 13 참조.
- **엔드포인트 이름 재사용을 위한 부분 유니크 인덱스** — `endpoint_name`에 대한 단순 UNIQUE 제약은 삭제 후 재배포를 방지했다. V007 마이그레이션이 소프트 삭제 레코드를 제외하는 부분 유니크 인덱스로 대체한다.

## 제한 사항

- **오토스케일링 없음** — 배포당 단일 레플리카. HPA나 KServe의 내장 오토스케일링 (Knative 필요) 없음.
- **GPU 지원 없음** — 리소스 기본값이 CPU 전용 (1 CPU, 2Gi RAM). GPU 서빙은 node selector와 리소스 한도 설정이 필요하다.
- **A/B 테스트 또는 카나리 없음** — 엔드포인트당 단일 모델 버전. 버전 간 트래픽 분할 없음.
- **MLflow iframe DOM 조작이 취약** — 모델 컴포넌트가 "Model training" 모드로 전환하고 사이드바를 숨기기 위해 MLflow iframe을 직접 조작한다. MLflow UI 변경 시 깨진다.
- **모델 모니터링 없음** — 배포된 모델에 대한 드리프트 감지, 예측 로깅, 성능 메트릭 없음.
- **추론 프록시가 레이턴시를 추가** — K8s API server를 거치면 한 홉이 추가된다. 직접 서비스 접근이 더 빠르지만 외부 네트워크 접근이 필요하다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| KServe Knative 모드 | Knative Serving + Istio가 필요하다. PoC에 대한 막대한 인프라 오버헤드. |
| Seldon Core | KServe보다 커뮤니티 모멘텀이 적다. KServe가 K8s 표준이 되고 있다. |
| BentoML | 커스텀 서빙 컨테이너 빌드가 필요하다. KServe + MLServer가 MLflow 모델을 네이티브로 처리한다. |
| Direct Flask/FastAPI 서빙 | 모델 생명주기 관리, health check, storage initialization이 없다. 처음부터 구축해야 한다. |
| TorchServe / TF Serving | 프레임워크 특화. MLServer가 하나의 런타임으로 여러 프레임워크를 지원한다. |

## 향후 개선 사항

- **Horizontal Pod Autoscaler** — 트래픽 기반 스케일링을 위해 InferenceService 리소스에 HPA 설정 추가.
- **GPU 프로파일** — 딥러닝 모델 추론을 위한 GPU 리소스 요청 지원.
- **모델 모니터링 대시보드** — 드리프트 감지와 정확도 모니터링을 위한 예측 및 ground truth 로깅.
- **카나리 배포** — 트래픽 분할과 함께 현재 버전 옆에 새 모델 버전 배포 지원.
- **직접 서비스 접근** — 적절한 인증과 함께 저레이턴시 추론을 위해 Ingress 또는 NodePort를 통한 KServe 엔드포인트 노출.
