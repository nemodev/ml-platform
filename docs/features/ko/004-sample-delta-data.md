# Feature 004: Sample Delta Lake Data

> California Housing 데이터셋을 MinIO에 Delta Lake 테이블로 프로비저닝하여, 노트북에서 현실적인 ML 데이터에 즉시 접근할 수 있게 한다.

## 개요 및 배경

PoC에는 시연할 데이터가 필요하다. 평가자에게 자체 데이터셋 업로드를 요청하는 대신, 잘 알려진 ML 벤치마크(California Housing, 20,640행, 8개 수치 feature + 1개 타겟)를 MinIO의 S3 호환 스토리지에 Delta Lake 테이블로 사전 프로비저닝한다. 이 Feature는 인프라 전용이다 — backend나 frontend 코드가 없다. Kubernetes Job이 배포 시 한 번 실행되어 Delta 테이블을 작성하고, 노트북 pod는 읽기 전용 자격 증명으로 접근한다. 원시 Parquet 대신 Delta Lake를 선택한 이유는 실제 프로젝트에서 사용할 계획이고, `deltalake` Python 라이브러리가 노트북 환경에서 원활하게 작동함을 입증하는 것이 PoC의 가치에 포함되기 때문이다.

## 아키텍처

```
provision-job.yaml (K8s Job, one-shot)
    ↓
ml-platform-notebook:latest image
    ↓ (python provision.py)
    ↓ sklearn.fetch_california_housing() → pandas → write_deltalake()
    ↓
MinIO: s3://ml-platform-sample-data/california-housing/
    ├── _delta_log/00000000000000000000.json
    └── part-00000-*.parquet
    ↓ (read-only credentials injected into notebook pods)
Notebook environment: DeltaTable(path).to_pandas()
```

**주요 설계 결정:**

- **`delta-spark` 대신 `deltalake` 라이브러리** — 순수 Python 리더는 2MB 데이터셋에 대한 Spark 오버헤드를 피한다. Spark 지원은 존재하지만 (Feature 005), 데이터 접근에는 필요하지 않다.
- **프로비저닝용 K8s Job** — 멱등적(`mode="overwrite"`), in-cluster에서 실행, 노트북 이미지 재사용, 10분 후 자동 정리 (`ttlSecondsAfterFinished: 600`).
- **읽기 전용 MinIO 사용자** — `sample-data-readonly` 사용자에 sample-data 버킷의 `s3:GetObject` 및 `s3:ListBucket`으로 제한된 S3 정책. 노트북 pod는 K8s Secret을 통해 이 자격 증명을 받아, 우발적인 데이터 수정을 방지한다.
- **California Housing 데이터셋** — 잘 알려진 scikit-learn 벤치마크, 퍼블릭 도메인, 메모리에 들어감, 수치 feature와 회귀 타겟을 모두 가짐. 학습, 추적, 서빙 워크플로우 시연에 이상적이다.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Infra | `k8s/sample-data/provision-job.yaml` | 프로비저닝 스크립트를 실행하는 K8s Job |
| Infra | `k8s/sample-data/provision-script-configmap.yaml` | Python 스크립트: fetch, 변환, Delta 작성, 검증 |
| Infra | `k8s/sample-data/read-only-secret.yaml` | 노트북 pod용 MinIO RO 자격 증명 |
| Infra | `k8s/sample-data/sample-notebook-configmap.yaml` | 예제 노트북: 데이터 로드, 모델 학습, MLflow 로깅 |
| Infra | `k8s/sample-data/batch-inference-notebook-configmap.yaml` | KServe 평가를 위한 배치 추론 노트북 |
| Infra | `k8s/sample-data/sample-visualization-configmap.yaml` | 데이터 탐색을 위한 Streamlit 대시보드 |
| Infra | `docker/notebook-image/requirements.txt` | `deltalake>=0.22.0` 의존성 |
| Infra | `helm/minio/local-values.yaml` | 버킷 생성 및 읽기 전용 정책 |

**프로비저닝 스크립트 흐름:** ConfigMap의 Python 스크립트가 scikit-learn에서 California Housing을 가져와, pandas DataFrame으로 변환하고, S3 스토리지 옵션과 함께 `write_deltalake()`를 사용하여 MinIO에 Delta 테이블로 작성한 후, 20,640행과 9개 컬럼을 assert하여 검증한다.

**3개의 예제 노트북:** 샘플 노트북은 데이터 로딩, 스키마 검사, 모델 학습(Gradient Boosting), 단계별 예측을 포함한 MLflow 로깅을 시연한다. 배치 추론 노트북은 평가 메트릭과 함께 KServe V2 protocol 호출을 보여준다. Streamlit 대시보드는 사이드바 필터와 지리적 시각화를 통한 인터랙티브 데이터 탐색을 제공한다.

**Delta용 S3 설정:** `deltalake` 라이브러리는 표준 AWS 환경 변수(`AWS_ENDPOINT_URL`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)에서 S3 자격 증명을 읽으며, JupyterHub의 `extraEnv` 설정과 읽기 전용 자격 증명 Secret을 통해 노트북 pod에 주입된다.

## 과제 및 해결

- **MinIO는 path-style S3 접근 필요** — AWS S3와 달리 MinIO는 `AWS_S3_ALLOW_UNSAFE_RENAME=true`와 path-style URL이 필요하다. 스토리지 옵션은 `write_deltalake()`와 `DeltaTable()`에 명시적으로 전달된다.
- **버킷 설정 조율** — MinIO 버킷 생성, 정책, 사용자 설정은 Helm values에서 이루어지고, 프로비저닝 Job은 이후에 실행된다. 배포 순서가 중요하다.

## 제한 사항

- **단일 데이터셋만** — California Housing만 프로비저닝된다. 실제 프로젝트에서는 다양한 도메인의 여러 데이터셋이 필요할 것이다.
- **증분 업데이트 없음** — 프로비저닝 Job은 `mode="overwrite"`를 사용한다 — 전체 테이블을 교체한다. append나 merge 작업은 시연되지 않는다.
- **프로비저닝 Job에 하드코딩된 MinIO 관리자 자격 증명** — Job이 쓰기 접근을 위해 `minioadmin:minioadmin`을 사용한다. PoC에서는 허용되지만 프로덕션에서는 안 된다.
- **Dockerfile에 하드코딩된 Spark 자격 증명** — 노트북 이미지의 Spark 기본값에 Docker 레이어에 평문 MinIO 자격 증명이 포함되어 있다.
- **데이터 리니지나 카탈로그 없음** — 어떤 데이터셋이 사용 가능한지 보여주는 메타데이터 카탈로그가 없다. 사용자가 S3 경로를 알아야 한다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| 프로비저닝용 delta-spark | 2MB 데이터셋에 Spark 클러스터가 필요하다. `deltalake` 순수 Python이 더 간단하다. |
| 원시 Parquet (Delta 없음) | 실제 프로젝트의 목표 형식인 Delta Lake를 시연하지 못한다. |
| PostgreSQL 테이블 | 프로젝트가 계획한 S3 기반 데이터 레이크 아키텍처와 다른 접근 패턴이다. |
| 합성 데이터 생성 | 덜 현실적이다. California Housing은 잘 알려져 있고 재현 가능한 데모를 가능하게 한다. |
| 다수의 데이터셋 | PoC의 범위 초과. 전체 워크플로우를 시연하는 데 하나의 데이터셋이면 충분하다. |

## 향후 개선 사항

- **데이터 카탈로그 API** — 스키마 정보가 포함된 사용 가능한 데이터셋을 나열하는 backend 엔드포인트, 포털에서 발견 가능.
- **사용자 업로드 데이터셋** — 사용자가 자체 CSV/Parquet 파일을 MinIO에 업로드하고 Delta 테이블로 등록할 수 있도록 허용.
- **여러 샘플 데이터셋** — 다양한 ML 워크플로우를 시연하기 위해 시계열, 분류, 텍스트 데이터셋 추가.
- **증분 데이터 작업** — Delta Lake merge, upsert, time-travel 기능 시연.
- **모든 자격 증명 외부화** — MinIO 자격 증명을 Dockerfile과 프로비저닝 Job에서 K8s Secrets로 이동.
