# Feature 011: Shared S3 Libraries & Data Navigator

> S3 기반의 중앙 집중식 공유 스토리지 영역을 REST API, 프론트엔드 파일 브라우저, 그리고 모든 런타임 pod(JupyterHub, Airflow, Spark) 내 읽기 전용 FUSE 마운트로 제공한다.

## 개요 및 배경

데이터 과학자와 ML 엔지니어는 모든 팀원이 노트북, 파이프라인, 모델 학습 작업에서 접근할 수 있는 데이터셋, Python 모듈, 설정 파일의 공유 저장소가 필요하다. 이것 없이는 팀이 S3 CLI 명령이나 수동 파일 업로드를 통한 임시 공유에 의존하게 된다 — 검색 가능성, 미리보기, 거버넌스가 없다. Feature 011은 세 가지 기능을 도입한다:

1. **백엔드 REST API** — 설정 가능한 공유 S3 prefix 아래의 오브젝트를 목록 조회, 업로드, 다운로드, 미리보기, 삭제하기 위한 API. 기존 MinioClient/ConfigurationProperties 패턴을 기반으로 구축.
2. **프론트엔드 파일 브라우저** — 새로운 최상위 포털 페이지("Shared Libraries")로, 폴더 탐색, 브레드크럼, 파일 액션, 진행률이 있는 업로드, 클라이언트 사이드 필터링, 텍스트/이미지/테이블(Parquet 포함) 파일의 인라인 미리보기를 제공한다.
3. **런타임 pod 마운트** — s3fs-fuse 사이드카 컨테이너를 통해 공유 리소스가 노트북 pod, Airflow 워커, Spark executor 내 `/home/jovyan/shared/`에 읽기 전용으로 나타난다. s3fs 사이드카 인프라는 workspace 격리 작업의 일부로 구축되었으며 — 이 Feature는 두 번째 읽기 전용 마운트를 추가한다.

## 아키텍처

```
Frontend (Shared Libraries 페이지)
    ↓ GET/POST/DELETE /api/v1/shared-library/**
Backend (SharedLibraryController → SharedLibraryService)
    ↓ MinIO Java SDK (listObjects, putObject, getObject, removeObject, presignedGetObject)
MinIO (S3 호환)
    ↑ s3://{bucket}/{shared-prefix}/...
    ↑
s3fs-fuse sidecar (/home/jovyan/shared/에 읽기 전용 마운트)
    ↑
런타임 pod (JupyterHub, Airflow 워커, Spark executor)
```

**주요 설계 결정:**

- **기존 MinioClient bean 및 ConfigurationProperties 재사용** — 프로젝트에 이미 `services.minio.*` 속성으로 구성된 `MinioClient` bean을 가진 `StorageConfig.java`가 있다. Shared library는 동일한 prefix 아래에 새 속성을 추가한다: `shared-bucket`, `shared-prefix`, `shared-preview-max-bytes`, `shared-upload-max-bytes`. 새 `SharedLibraryService`는 `NotebookStorageService`와 동일한 패턴을 따른다.
- **커스텀 빌드 Angular 파일 브라우저** — 사용 가능한 Angular 파일 브라우저 라이브러리(ngx-explorer, ng6-file-man, ngx-voyage, angular-filemanager)를 평가했다. 모두 아카이브되었거나, Angular 17과 호환되지 않거나, 유지보수되지 않는다. 커스텀 컴포넌트가 표준 접근 방식이다: 브레드크럼 탐색, 파일 액션, 미리보기 패널이 있는 목록 뷰.
- **다운로드는 Presigned URL, 미리보기는 스트리밍** — 다운로드는 presigned URL(15분 만료, 기존 패턴)을 사용한다. 미리보기는 콘텐츠 타입 감지, 크기 제한(1 MB 텍스트, 100행 테이블), Parquet 행 추출을 위해 백엔드를 통해 스트리밍된다. 업로드는 간결성과 500 MB 크기 검증을 위해 백엔드를 통한 multipart form data를 사용한다(직접 S3 presigned POST가 아님).
- **런타임 마운트용 s3fs-fuse 사이드카** — workspace 격리용으로 구축된 동일한 사이드카 이미지를 재사용한다. 모든 런타임 pod에 `/home/jovyan/shared/`에 읽기 전용으로 두 번째 마운트를 추가한다. 사용자가 노트북에서 `import shared.my_module` 또는 `pd.read_csv('/home/jovyan/shared/datasets/data.csv')`를 직접 사용할 수 있다.
- **백엔드 측 Parquet 미리보기** — `org.apache.parquet:parquet-hadoop`을 사용하여 서버 측에서 처음 N행을 읽고 JSON으로 반환한다. 브라우저에 Parquet 파서를 전송하는 것을 방지한다.
- **Java record DTO** — 프로젝트 관례를 따른다(예: `PortalSectionResponse`, `WorkspaceResponse`). Record 사용: `SharedResourceResponse`, `SharedResourceListResponse`, `SharedResourcePreviewResponse`, `UploadResultResponse`, `DownloadUrlResponse`, `CreateFolderRequest`.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `controller/SharedLibraryController.java` | REST 엔드포인트: 목록, 업로드, 다운로드, 미리보기, 삭제, 폴더 생성 |
| Backend | `service/SharedLibraryService.java` | MinioClient를 통한 S3 작업 |
| Backend | `config/StorageConfig.java` | 확장: shared-bucket, shared-prefix, preview/upload 한도 |
| Backend | `dto/shared/*.java` | 모든 API 응답을 위한 Java record DTO |
| Backend | `controller/PortalController.java` | 수정: shared-libraries 탐색 섹션 추가 |
| Frontend | `features/shared-libraries/shared-libraries.component.ts` | 메인 파일 브라우저 컴포넌트 |
| Frontend | `features/shared-libraries/shared-library.service.ts` | Shared library API용 HTTP 클라이언트 |
| Frontend | `features/shared-libraries/components/` | 브레드크럼, file-list, file-preview, upload-dialog, create-folder-dialog |
| Frontend | `app.routes.ts` | `/shared-libraries`에 대한 지연 로딩 라우트 추가 |
| Frontend | `app.component.ts` | 폴백 탐색 항목 추가 |
| Infra | `helm/jupyterhub/values.yaml` | pre_spawn_hook에 두 번째 s3fs 마운트(읽기 전용 shared) 추가 |
| Infra | `helm/airflow/local-values.yaml` | 워커 pod에 s3fs 사이드카 + 공유 볼륨 추가 |
| Infra | `installer/templates/jupyterhub-values.yaml` | installer 플레이스홀더가 있는 JH values 미러 |
| Infra | `installer/templates/airflow-values.yaml` | installer 플레이스홀더가 있는 Airflow values 미러 |

**탐색 통합:** 프론트엔드는 기존 패턴을 따른다 — `app.component.ts`의 `sections` 배열(정적 폴백)과 `PortalController.sections()`(백엔드 진실의 원천). "Shared Libraries" 추가는 양쪽 모두에 항목과 `app.routes.ts`에 지연 로딩 라우트가 필요하다.

**미리보기 파이프라인:** 미리보기 엔드포인트가 확장자로 파일 타입을 감지한 후:
- **텍스트** (`.py`, `.csv`, `.json`, `.yaml`, `.txt`, `.md`): 콘텐츠의 처음 1 MB를 UTF-8 문자열로 반환
- **이미지** (`.png`, `.jpg`, `.gif`, `.svg`): 브라우저 직접 렌더링을 위한 presigned URL 반환
- **테이블** (`.parquet`): `parquet-hadoop`으로 처음 100행을 읽어 컬럼명, 타입, 행 데이터를 JSON으로 반환
- **기타**: 메타데이터만 반환 (크기, 콘텐츠 타입, 최종 수정)

**런타임 마운트 패턴:** s3fs 사이드카가 이미 workspace 마운트를 위해 각 노트북 pod에서 실행된다. Shared libraries를 위해 두 번째 사이드카(또는 확장된 entrypoint)가 공유 S3 prefix를 별도의 `emptyDir` 볼륨에 읽기 전용으로 마운트한다:
```
sidecar: s3fs-shared → /mnt/shared (emptyDir, Bidirectional)
main:    /mnt/shared → /home/jovyan/shared (HostToContainer, readOnly: true)
```

## 과제 및 해결

- **S3 디렉토리 시맨틱스** — S3는 플랫 키-값 저장소이다; "폴더"는 단지 공통 prefix일 뿐이다. 백엔드가 `listObjects()` 결과에서 폴더 항목을 합성하고, 사용자가 폴더를 생성할 때 제로 바이트 디렉토리 마커 오브젝트(`application/x-directory` 콘텐츠 타입)를 생성한다. s3fs는 `compat_dir` 모드에서 prefix 검증을 위해 이 마커를 요구한다.
- **백엔드를 통한 대용량 파일 업로드** — Spring Boot를 통해 500 MB 업로드를 프록시하려면 `spring.servlet.multipart.max-file-size`와 `spring.servlet.multipart.max-request-size` 설정이 필요하다. 백엔드는 전체 파일을 메모리에 버퍼링하지 않고 MinIO에 직접 스트리밍한다(`putObject` with known size).
- **Parquet 의존성 크기** — `parquet-hadoop`이 `hadoop-common`에서 상당한 전이 의존성 트리를 가져온다. 최소한의 Parquet 리더와 Hadoop 파일시스템 추상화만 필요하다. Exclusion과 의존성 관리로 영향을 관리 가능하게 유지한다.
- **공유 prefix를 위한 s3fs 읽기 전용 마운트** — 공유 마운트는 런타임 pod에서의 쓰기를 방지하기 위해 s3fs `ro` 옵션을 사용한다. 사용자는 웹 UI / REST API를 통해서만 공유 콘텐츠를 관리한다.
- **iframe 임베딩을 위한 CSP** — workspace 격리 작업에서 이미 해결됨. `/user/` location에서의 nginx 수준 CSP 오버라이드(`proxy_hide_header Content-Security-Policy` + `add_header "frame-ancestors 'self'" always`)가 모든 JupyterHub iframe 콘텐츠에 적용된다.

## 제한 사항

- **폴더별 접근 제어 없음** — 인증된 모든 사용자가 공유 라이브러리의 모든 파일을 읽기, 업로드, 삭제할 수 있다. 폴더별 또는 파일별 권한 없음.
- **500 MB 업로드 한도** — 백엔드 프록시 업로드에 실용적인 크기 한도가 있다. 더 큰 데이터셋은 CLI나 SDK를 통해 S3에 직접 업로드해야 한다.
- **버전 관리 없음** — 파일 덮어쓰기 시 영구적으로 교체된다. 버전 이력이나 휴지통 없음.
- **대용량 디렉토리에서의 s3fs 지연** — 수천 개의 파일이 있는 디렉토리를 s3fs로 나열하면 느릴 수 있다. 웹 UI는 파일시스템 나열보다 빠른 페이지네이션된 API 호출을 사용한다.
- **단일 공유 prefix** — 모든 공유 콘텐츠가 하나의 S3 prefix 아래에 존재한다. 다중 공유 라이브러리나 팀 범위 prefix 지원 없음.
- **Parquet 미리보기가 처음 100행으로 제한** — Parquet 데이터의 쿼리나 필터링 지원 없음. 미리보기는 분석 도구가 아닌 샘플링 메커니즘이다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| OSS Angular 파일 브라우저 라이브러리 | 평가한 모든 옵션(ngx-explorer, ng6-file-man, ngx-voyage)이 Angular 17에 대해 아카이브되었거나, 호환되지 않거나, 유지보수되지 않는다. |
| 임베디드 elFinder 또는 유사한 JS 파일 매니저 | Angular Material 테마와의 스타일 불일치, 복잡한 인증 브리징, 무거운 JavaScript 번들. |
| S3 직접 presigned 업로드 | 500 MB 규모에서 큰 이점 없이 복잡성이 추가된다(presign → upload → confirm 플로우). |
| s3fs 사이드카 대신 S3 CSI 드라이버 | 클러스터 수준의 설치가 필요하다. s3fs 사이드카는 workspace 격리에서 이미 검증되었으며 pod별로 자체 완결적이다. |
| 주기적 동기화 (CronJob → PVC) | 오래된 데이터, 공유 PVC 복잡성, 5분 동기화 요구사항을 충족하지 못한다. |
| 클라이언트 사이드 Parquet 파싱 (parquet-wasm) | 브라우저에 WASM 파서를 전송해야 한다. 서버 측 파싱이 더 간단하고 제한된 읽기를 지원한다. |

## 향후 개선 사항

- **폴더별 접근 제어** — Keycloak 역할과 통합된 공유 라이브러리 prefix에 대한 RBAC 기반 권한.
- **파일 버전 관리** — MinIO 버킷 버전 관리를 활성화하고 UI에서 버전 이력을 노출.
- **드래그 앤 드롭 업로드** — 데스크탑에서 파일 브라우저로의 드래그 앤 드롭 지원.
- **전문 검색** — 공유 라이브러리 전체에서 파일 콘텐츠 인덱싱 및 검색.
- **팀 범위 라이브러리** — Keycloak 그룹/팀으로 범위가 지정된 다중 공유 prefix.
- **청크/재개 가능 프로토콜을 통한 대용량 업로드** — 500 MB 이상 파일을 위한 Tus 또는 S3 multipart 업로드.
- **노트북 통합** — 노트북 인터페이스 내에서 공유 라이브러리의 사이드바 뷰를 제공하는 JupyterLab 확장.
