# Feature 008: Custom Notebook Images

> 사용자가 커스텀 Python 환경을 정의하고, Kaniko를 통해 클러스터 내에서 빌드하며, MinIO 백엔드 스토리지를 사용하는 Docker Distribution 레지스트리에 저장한다.

## 개요 및 배경

기본 노트북 이미지에는 포괄적인 ML 스택이 포함되어 있지만, 사용자는 종종 추가 라이브러리(도메인 특화 패키지, 최신 버전, 커스텀 내부 도구)가 필요하다. Feature 008은 사용자가 패키지 목록과 Python 버전을 정의하면, Docker daemon 접근 없이 클러스터 내 Docker 빌드를 트리거할 수 있게 한다. 비특권 컨테이너로 실행되는 Kaniko를 선택했다 — Docker-in-Docker 없음, 특권 pod 없음. 빌드된 이미지는 MinIO를 스토리지로 사용하는 클러스터 내 Docker Distribution 레지스트리(기존 인프라 재사용)에 푸시되며, 사용자는 workspace 실행 시 커스텀 이미지를 선택할 수 있다.

## 아키텍처

```
Portal (Create & Build)
    ↓ POST /api/v1/notebook-images → POST .../builds
Backend (NotebookImageService + ImageBuildService)
    ├── Create NotebookImage entity (PENDING)
    ├── Create ImageBuild entity (QUEUED)
    └── ImageBuildScheduler (10s interval)
        ↓ promote QUEUED → BUILDING if slot available
        ├── Generate Dockerfile (base image + pip install)
        ├── Create ConfigMap with Dockerfile + context
        └── Create K8s Job with Kaniko container
            ↓
Kaniko (gcr.io/kaniko-project/executor)
    ├── Build from generated Dockerfile
    └── Push to registry.ml-platform.svc:5000
        ↓
Docker Distribution (registry:2, MinIO-backed storage)
    ↓
JupyterHub (custom image selected at workspace launch)
    ↓ pre_spawn_hook overrides spawner.image
Notebook Pod (custom image with user's packages)
```

**주요 설계 결정:**

- **클러스터 내 빌드를 위한 Kaniko** — Apache 2.0 라이선스, Docker daemon 불필요, 특권 컨테이너 불필요. 표준 K8s Job으로 실행된다. 대안인 Docker-in-Docker는 특권 모드가 필요하다 (보안 위험).
- **내장 레지스트리로서의 Docker Distribution** — CNCF 프로젝트(`registry:2`), 경량, S3 백엔드를 통해 MinIO에 레이어를 저장한다. 별도의 스토리지 프로비저닝이 필요 없다.
- **데이터베이스 수준 동시성 제어** — 사용자별 한도(1개 활성 빌드)와 클러스터 전체 한도(3개 동시 빌드)를 `SELECT FOR UPDATE` 쿼리로 강제한다. 간단하고 분산 잠금이 필요 없다.
- **FIFO 빌드 큐** — QUEUED 빌드는 `createdAt` 순서로 처리된다. `ImageBuildScheduler`가 10초마다 실행하여 다음 대기 중인 빌드를 승격한다.
- **플랫폼 제약 파일** — 기본 이미지의 `/opt/ml-platform/platform-constraints.txt`에 핀된 플랫폼 패키지가 나열된다. 커스텀 빌드가 `pip install --constraint`를 사용하여 사용자가 핵심 의존성(streamlit, jupyter-server-proxy, mlflow 등)을 깨뜨리는 것을 방지한다.
- **스케줄러 기반 폴링** — `ImageBuildScheduler`에 3가지 작업이 있다: 큐 처리(10초), K8s API에서 활성 빌드 새로고침(30초), 타임아웃 강제(60초). WebSocket 복잡성 없음.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `service/NotebookImageService.java` | Image CRUD, 삭제 가드, Python 버전 리스팅 |
| Backend | `service/ImageBuildService.java` | 빌드 트리거, Kaniko Job 생성, 로그/진행률 파싱 |
| Backend | `service/ImageBuildScheduler.java` | 큐 처리, 상태 새로고침, 타임아웃 강제 |
| Backend | `service/ContainerRegistryService.java` | 레지스트리 health check, 이미지 삭제, 기본 이미지 해석 |
| Backend | `controller/NotebookImageController.java` | 이미지, 빌드, 로그용 REST 엔드포인트 |
| Backend | `config/NotebookImageConfig.java` | 레지스트리, 빌드, 기본 이미지 설정 |
| Frontend | `features/notebook-images/notebook-images.component.ts` | 빌드 중 5초 자동 새로고침이 있는 이미지 목록 |
| Frontend | `features/notebook-images/create-image-dialog.component.ts` | Python 버전, 패키지, 추가 pip 인덱스 |
| Frontend | `features/notebook-images/build-detail.component.ts` | 진행 단계, 경과 시간, 로그 뷰어 |
| Infra | `k8s/kserve/kaniko-rbac.yaml` | Job/ConfigMap/Secret 작업을 위한 ServiceAccount + Role |
| Infra | `k8s/kserve/registry-deployment.yaml` | MinIO S3 스토리지 백엔드를 사용하는 Docker Distribution |
| DB | `V009__create_notebook_images_and_builds.sql` | notebook_images + image_builds 테이블 |
| DB | `V010__add_notebook_image_id_to_workspaces.sql` | workspace에서 커스텀 이미지로의 FK |

**빌드 Job 생성:** `ImageBuildService.createKanikoJob()`이 즉석에서 Dockerfile을 생성한다: `FROM {base-image-for-python-version}`, `COPY platform-constraints.txt`, `COPY custom-requirements.txt`, `RUN pip install --constraint ... -r ...`. Dockerfile과 requirements는 ConfigMap에 저장되어 Kaniko 컨테이너에 마운트된다. `initContainer`(busybox)가 Kaniko가 읽을 수 있도록 ConfigMap 심볼릭 링크를 해제한다. 이미지 이름은 `custom/{userId-prefix}-{imageName}:{buildId-prefix}` 형식을 따른다.

**진행 단계 감지:** `ImageBuildService.parseProgressStage()`가 Kaniko 로그 출력을 휴리스틱하게 파싱하여 현재 단계를 결정한다: 기본 빌드, 패키지 설치, 이미지 푸시. 프론트엔드의 `build-detail.component.ts`가 5단계 진행률 표시기(Queued → Building Base → Installing Packages → Pushing Image → Complete)로 이를 미러링한다.

**동시성 강제:** `ImageBuildService.triggerBuild()`가 사용자의 QUEUED+BUILDING 빌드 수(최대 1)와 전역(최대 3)을 카운트한다. 한도를 초과하면, 빌드는 슬롯이 열릴 때까지 QUEUED 상태를 유지한다. `ImageBuildScheduler.processQueue()`가 10초마다 가장 오래된 QUEUED 빌드를 선택한다.

**타임아웃 강제:** `ImageBuildScheduler.enforceTimeouts()`가 `timeoutMinutes`(기본 60)보다 오래 BUILDING 상태인 빌드를 확인하고 취소한다. K8s Job에도 하드 리밋으로 `activeDeadlineSeconds`가 있다.

## 과제 및 해결

- **ConfigMap 심볼릭 링크** — Kubernetes가 ConfigMap 파일을 심볼릭 링크로 마운트한다. Kaniko가 이를 따라갈 수 없다. 해결: busybox `initContainer`가 Kaniko 시작 전에 ConfigMap 마운트에서 일반 `emptyDir` 볼륨으로 파일을 복사.
- **푸시를 위한 레지스트리 자격 증명** — Kaniko가 푸시하려면 Docker auth가 필요하다. 해결: `/kaniko/.docker/config.json`에 `.dockerconfigjson`이 있는 K8s Secret을 마운트. 배포 스크립트가 클러스터 내 레지스트리에 적합한 auth를 생성한다.
- **Python 버전별 기본 이미지** — 다른 Python 버전에는 다른 기본 이미지가 필요하다. `ContainerRegistryService.getBaseImageReference()`가 설정에서 적절한 기본 이미지를 해석하며, 3.10, 3.11, 3.12를 지원한다.
- **사용 중 삭제 가드** — `NotebookImageService.deleteImage()`가 현재 이미지를 참조하는 workspace가 있는지 확인하고, 명확한 오류 메시지와 함께 삭제를 차단한다.

## 제한 사항

- **패키지 구문 사전 검증 없음** — 잘못된 pip spec은 빌드 시간에만 감지된다 (잠재적으로 수 분의 빌드 후). 프론트엔드가 모든 텍스트를 수용한다.
- **데이터베이스 TEXT 컬럼에 저장되는 빌드 로그** — Kaniko 로그는 10-500KB가 될 수 있다. 잘라내기나 외부 로그 스토리지 없음. 대용량 로그가 `image_builds` 테이블을 비대하게 만든다.
- **진행 단계 파싱이 휴리스틱** — Kaniko 로그 출력의 특정 문자열에 의존한다. Kaniko의 출력 형식 변경 시 단계 감지가 깨진다.
- **공유/팀 이미지 없음** — 이미지는 사용자 범위만. 관리자가 조직 전체 커스텀 이미지를 게시하는 메커니즘 없음.
- **이미지 참조 파싱이 취약** — `ImageBuildService.deleteImage()`가 이미지 이름과 태그를 추출하기 위해 문자열 분할을 사용하며, `/`를 포함하는 경로가 있는 레지스트리에서 실패한다.
- **이미지 생성 후 빌드 트리거의 조용한 실패** — 생성 다이얼로그의 빌드 트리거 실패가 삼켜진다; 사용자는 성공을 보지만 빌드가 큐에 들어가지 않았을 수 있다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| Docker-in-Docker (DinD) | 특권 컨테이너가 필요하다 — 공유 클러스터에서 주요 보안 위험. |
| Buildah | Kaniko보다 Kubernetes 네이티브가 아니다. Kaniko는 클러스터 내 빌드를 위해 만들어졌다. |
| BuildKit | 더 복잡한 설정. Kaniko가 필요한 단일 스테이지 빌드에 더 간단하다. |
| 사전 빌드 이미지 카탈로그 | 사용자 정의 패키지를 허용하지 않는다. 도메인 특화 라이브러리를 위해 커스텀 환경이 필요하다. |
| 외부 레지스트리 (Docker Hub, Harbor) | 외부 의존성 추가. MinIO 스토리지를 갖춘 클러스터 내 레지스트리가 자체 완결적이다. |
| conda 환경 (Docker 빌드 없음) | JupyterHub KubeSpawner는 Docker 이미지를 기대한다. conda는 컨테이너 이미지를 변경하지 않는다. |

## 향후 개선 사항

- **패키지 사전 검증** — 명백한 오류에 대해 빠르게 실패하기 위해 빌드 Job 제출 전에 requirements.txt 구문 검증.
- **빌드 로그 스트리밍** — 데이터베이스를 폴링하는 대신 Kaniko 로그를 프론트엔드에 실시간으로 스트리밍.
- **외부 로그 스토리지** — 데이터베이스 비대화를 줄이기 위해 빌드 로그를 PostgreSQL에서 MinIO/S3로 이동.
- **공유 이미지 라이브러리** — 관리자가 모든 사용자에게 제공되는 큐레이션된 이미지를 게시할 수 있도록 허용.
- **이미지 레이어 캐싱** — 일부 패키지만 변경될 때 재빌드 속도를 높이기 위해 Kaniko의 레이어 캐시 설정.
- **빌드 완료 알림** — 알림 엔드포인트가 API 계약에 참조되지만 완전히 구현되지 않았다. 토스트 알림을 위해 완성.
