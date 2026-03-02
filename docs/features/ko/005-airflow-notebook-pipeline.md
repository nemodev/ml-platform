# Feature 005: Airflow Notebook Pipeline

> 인터랙티브 노트북을 Airflow의 KubernetesExecutor와 Papermill을 사용하여 프로덕션 파이프라인으로 전환하며, 선택적으로 Spark-on-K8s 분산 처리를 지원한다.

## 개요 및 배경

노트북은 실험에는 적합하지만, 프로덕션에서는 재현 가능하고, 스케줄링되며, 확장 가능해야 한다. Apache Airflow를 통합하여 Papermill을 통해 노트북을 파이프라인 작업으로 실행한다. 핵심 인사이트는 **환경 동일성**이다: 인터랙티브 노트북을 실행하는 동일한 Docker 이미지가 파이프라인 워커와 Spark executor도 실행한다. 사용자가 포털에서 파이프라인을 트리거하면, backend가 노트북을 MinIO에 복사(불변 스냅샷)하고, Airflow의 `notebook_runner` DAG를 트리거하며, 사용자는 포털 UI를 통해 진행 상황을 모니터링한다. Airflow UI는 사용자에게 노출되지 않으며 — backend가 모든 것을 프록시한다.

## 아키텍처

```
Portal (trigger button)
    ↓ POST /api/v1/pipelines
Backend (PipelineService)
    ├── Copy notebook from JupyterHub → MinIO (input snapshot)
    ├── Create PipelineRun entity (PENDING)
    └── Trigger Airflow DAG via REST API
        ↓
Airflow (KubernetesExecutor)
    ↓ notebook_runner DAG
    ├── prepare_command (PythonOperator) — build papermill command
    └── run_notebook (KubernetesPodOperator)
        ↓ ml-platform-notebook:latest
        papermill input.ipynb output.ipynb -p key value
        ↓ (optional: Spark executors spawned as K8s pods)
        ↓
MinIO: s3://ml-platform-pipelines/{username}/{runId}/output.ipynb
```

**주요 설계 결정:**

- **KubernetesExecutor** — 각 Airflow 작업이 격리된 K8s pod로 실행된다. Celery 브로커나 Redis가 필요 없다. Pod는 온디맨드로 생성되고 완료 후 정리된다.
- **재사용 가능한 단일 DAG** — 하나의 `notebook_runner` DAG가 모든 파이프라인 실행을 처리한다. 설정(노트북 경로, 파라미터, Spark 플래그)은 `dag_run.conf`를 통해 전달된다. DAG 증식을 피하고 관리를 단순화한다.
- **파라미터화 실행을 위한 Papermill** — Papermill이 `parameters`로 태그된 셀을 읽고 제공된 값으로 오버라이드한다. 출력 노트북은 디버깅과 감사를 위해 모든 셀 출력을 유지한다.
- **불변 노트북 스냅샷** — Airflow를 트리거하기 전에 backend가 JupyterHub에서 MinIO로 노트북을 복사한다. 이로써 사용자가 이후에 노트북을 수정하더라도 파이프라인이 고정된 버전에 대해 실행되도록 보장한다.
- **Spark client 모드** — `enable_spark=true`일 때, 파이프라인 pod가 Spark 드라이버(client mode)로 실행되고 동일 네임스페이스에서 executor pod를 생성한다. Spark Operator CRD 불필요 — pod/service 생성을 위한 RBAC만 있으면 된다.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `controller/PipelineController.java` | 트리거, 목록, 상세, 출력 URL, 노트북 리스팅 |
| Backend | `service/PipelineService.java` | 트리거 플로우 오케스트레이션, Airflow와 상태 동기화 |
| Backend | `service/AirflowService.java` | basic auth를 사용하는 Airflow REST API 클라이언트 |
| Backend | `service/NotebookStorageService.java` | MinIO 복사, pre-signed URL 생성 |
| Backend | `model/PipelineRun.java` | 상태 생명주기를 가진 JPA 엔티티 |
| Frontend | `features/pipelines/pipelines.component.ts` | 자동 새로고침, 상태 필터링을 포함한 실행 목록 |
| Frontend | `features/pipelines/trigger-dialog/` | 노트북 선택기, Spark 토글, 파라미터 편집기 |
| Frontend | `features/pipelines/run-detail/` | 실행 메타데이터, 출력 뷰어, 오류 표시 |
| Infra | `k8s/airflow/dag-configmap.yaml` | `notebook_runner` DAG (PythonOperator + KubernetesPodOperator) |
| Infra | `k8s/airflow/spark-rbac.yaml` | pod/service 생성 권한이 있는 ServiceAccount |
| Infra | `helm/airflow/local-values.yaml` | KubernetesExecutor, MinIO 원격 로깅 |
| Infra | `helm/airflow/pod-template.yaml` | 노트북 이미지를 사용하는 작업 pod 템플릿 |

**트리거 플로우:** `PipelineService.triggerPipeline()`이 노트북 경로(.ipynb)를 검증하고, JWT에서 사용자를 동기화하며, JupyterHub의 Content API에서 노트북 바이트를 가져오고, MinIO(`{prefix}/{userId}/{runId}/input.ipynb`)에 복사하고, `PipelineRun` 엔티티를 생성하고, conf 페이로드와 함께 `AirflowService.triggerDagRun()`을 호출한 후, Airflow DAG run ID로 실행을 업데이트한다. 어느 단계에서든 실패하면 실행은 오류 메시지와 함께 FAILED로 표시된다.

**상태 동기화:** 프론트엔드가 실행 상태를 요청하면, `PipelineService`가 Airflow의 REST API에서 최신 DAG run 상태를 폴링하고 매핑한다: `queued/scheduled/deferred` → PENDING, `running` → RUNNING, `success` → SUCCEEDED, 기타 → FAILED.

**Spark 환경 설정:** DAG의 `prepare_command` 작업이 설정에서 `enable_spark`를 확인한다. true이면, papermill 명령에 환경 변수 내보내기를 앞에 추가한다: `SPARK_HOME`, `SPARK_MASTER` (k8s://...), executor 설정 (2개 인스턴스, 1 CPU, 2Gi 각각), S3A 파일시스템 설정. Pod 템플릿에 executor pod와 service 생성을 위한 RBAC가 있는 `airflow-spark-sa` service account가 포함된다.

**출력 보기:** 완료 후 `getOutputUrl()`이 출력 노트북에 대한 15분짜리 pre-signed MinIO URL을 생성한다. 프론트엔드가 `window.open()`으로 새 탭에서 연다.

## 과제 및 해결

- **Airflow DAG 기본 일시 중지** — 새 DAG는 일시 중지 상태이다. `AirflowService.triggerDagRun()`이 트리거 전에 먼저 PATCH를 통해 DAG를 일시 중지 해제한다.
- **MinIO 경로 살균** — 특수 문자가 포함된 사용자 ID를 S3 객체 경로용으로 영숫자 + 하이픈으로 살균한다.
- **Airflow 인증 변환** — 포털은 JWT를 사용하고, Airflow는 basic auth를 사용한다. Backend가 둘 사이를 변환하여, Airflow의 관리자 자격 증명이 프론트엔드에 도달하지 않는다.
- **MinIO에 원격 로깅** — Airflow 로그가 S3 로깅 연결을 통해 `s3://ml-platform-pipelines/ml-platform/airflow-logs`에 기록되어, pod 생명주기를 넘어서 지속된다.

## 제한 사항

- **DAG 스케줄링 없음** — 파이프라인은 온디맨드 트리거만 가능하다. 포털을 통한 cron 기반 스케줄링은 노출되지 않는다.
- **단일 DAG 패턴** — 모든 파이프라인이 `notebook_runner`를 사용한다. 복잡한 다단계 DAG (노트북 A → 노트북 B)는 지원되지 않는다.
- **재시도 로직 없음** — Airflow 트리거가 실패하면, 실행이 즉시 FAILED로 표시된다. 백오프가 있는 자동 재시도가 없다.
- **하드코딩된 Spark 기본값** — 2개 executor, 1 CPU, 2Gi 각각. 사용자가 포털을 통해 Spark 설정을 커스터마이징할 수 없다.
- **DAG ConfigMap에 하드코딩된 MinIO 자격 증명** — `minioadmin:minioadmin`이 Spark S3A 설정에 나타난다. K8s Secrets를 사용해야 한다.
- **15분 출력 URL 만료** — 출력 노트북의 pre-signed URL이 만료된다. 느린 연결의 사용자는 다시 가져와야 할 수 있다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| CeleryExecutor | Redis 브로커가 필요하다. KubernetesExecutor가 더 간단하고 더 나은 pod 격리를 제공한다. |
| 노트북별 DAG | DAG 증식. `dag_run.conf`를 사용하는 단일 템플릿 DAG가 더 깔끔하고 확장성이 있다. |
| Papermill 대신 nbconvert | 파라미터화를 지원하지 않는다. Papermill의 셀 태깅이 표준이고 잘 문서화되어 있다. |
| Spark Operator (CRD) | 추가 CRD 설치와 복잡성. 파이프라인 pod에서의 client-mode Spark가 PoC에 충분하다. |
| Direct KubernetesPodOperator (Papermill 없음) | 노트북 특화 기능을 잃는다: 셀 수준 출력 캡처, 파라미터 주입, 재현 가능한 실행. |
| 사용자에게 Airflow UI 노출 | Airflow의 UI는 강력하지만 복잡하다. 간소화된 포털 뷰가 대상 사용자에게 더 적합하다. |

## 향후 개선 사항

- **DAG 스케줄링** — 반복 파이프라인 실행을 위한 cron 스케줄을 사용자가 설정할 수 있도록 허용.
- **다단계 파이프라인** — 여러 노트북 단계와 데이터 의존성이 있는 DAG 지원.
- **커스터마이징 가능한 Spark 프로파일** — workspace 프로파일과 유사하게, 사용자가 Spark executor 수와 리소스를 선택할 수 있도록 허용.
- **파이프라인 템플릿** — 사용자가 커스터마이징할 수 있는 사전 구축 파이프라인 패턴 (학습 → 평가 → 배포).
- **실시간 로그 스트리밍** — 완료를 기다리는 대신 Airflow 작업 로그를 포털에 실시간으로 스트리밍.
- **MinIO 자격 증명 외부화** — 모든 자격 증명을 K8s Secrets로 이동하고 DAG ConfigMap에서 참조.
