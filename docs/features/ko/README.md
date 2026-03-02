# Feature 문서

ML Platform PoC의 10개 Feature에 대한 개발자 친화적 요약. 각 문서는 아키텍처 결정, 주요 구현 상세, 과제, 제한 사항, 프로덕션 버전을 위해 투자해야 할 사항을 다룬다.

이것은 **스펙이 아니다**. 사용자 스토리와 인수 기준은 `specs/NNN-*/spec.md`를 참조.

## Feature 의존성 체인

```
001 Keycloak Auth ─────────────────────────────────────────────┐
    ↓                                                          │
002 JupyterHub Notebooks ──┬───────────────────────────────────┤
    ↓                      ↓                                   │
003 MLflow Experiments   004 Sample Delta Data                 │
    ↓                                                          │
007 Analysis Entity & UI Customization                         │
    ↓                      ↓                  ↓                │
005 Airflow Pipelines    009 Streamlit Viz  008 Custom Images  │
    ↓                                         ↓                │
006 Model Serving                           010 Resource       │
                                                Profiles       │
                                                               │
All features depend on ────────────────────────────────────────┘
```

## Features

| # | Feature | 요약 | 의존성 |
|---|---------|------|--------|
| 001 | [Keycloak Auth & Portal](001-keycloak-auth-portal.md) | OIDC PKCE 인증, 사용자 동기화, JWT 전파, dev profile mocking | 기반 |
| 002 | [JupyterHub Notebooks](002-jupyterhub-notebook.md) | Workspace 생명주기, named server, SSO, idle culling | 기반 |
| 003 | [MLflow Experiment Tracking](003-mlflow-experiment-tracking.md) | 사용자 접두사 실험, backend proxy, CSS iframe injection | 002에 의존 |
| 004 | [Sample Delta Data](004-sample-delta-data.md) | MinIO에 Delta Lake 테이블을 프로비저닝하는 K8s Job | 002에 의존 |
| 005 | [Airflow Notebook Pipeline](005-airflow-notebook-pipeline.md) | KubernetesExecutor, Papermill, 불변 스냅샷, 선택적 Spark | 007에 의존 |
| 006 | [Model Serving & Inference](006-model-serving-inference.md) | KServe Standard 모드, V2 protocol, backend 프록시 예측 | 003, 005에 의존 |
| 007 | [Analysis Entity & UI Customization](007-notebook-ui-customization.md) | Analysis 범위화, JupyterLab 크롬 제거, postMessage bridge | 002, 003에 의존 |
| 008 | [Custom Notebook Images](008-custom-notebook-images.md) | Kaniko 클러스터 내 빌드, Docker Distribution 레지스트리, 빌드 큐 | 002에 의존 |
| 009 | [Streamlit Visualization](009-streamlit-visualization.md) | jupyter-server-proxy, 커스텀 extension, polling startup detection | 007에 의존 |
| 010 | [Resource Profiles](010-notebook-resource-profiles.md) | 설정 기반 CPU/메모리 프로파일, K8s Metrics API, 프로파일 전환 | 002, 008에 의존 |

## 공통 패턴

이 패턴들은 여러 Feature에서 반복된다. 각각 한 번 소개되고 이후 문서에서 참조된다:

**Dev profile mocking** ([001](001-keycloak-auth-portal.md)에서 도입) — 모든 외부 서비스 통합이 `isDevProfile()`을 확인하고 인프라 없이 로컬 실행 시 mock 데이터를 반환한다. Keycloak, JupyterHub, MLflow, Airflow, KServe 없이 프론트엔드 개발이 가능하다.

**Analysis 범위 아키텍처** ([007](007-notebook-ui-customization.md)에서 도입) — Analysis 엔티티가 workspace, experiment, visualization을 단일 이름 있는 컨텍스트 아래에 그룹화한다. 모든 workspace 및 experiment API가 `/api/v1/analyses/{analysisId}/...`로 범위화된다.

**3가지 iframe 임베딩 패턴** — 각 임베딩 UI가 다른 통합 전략을 사용한다:
- JupyterLab: Comlink RPC를 통한 postMessage 커맨드 브릿지 ([007](007-notebook-ui-customization.md))
- MLflow: 네이티브 크롬을 숨기는 CSS injection ([003](003-mlflow-experiment-tracking.md))
- Streamlit: 상태 머신을 갖춘 polling startup detection ([009](009-streamlit-visualization.md))

**Kubernetes Java Client** — Backend에서 K8s 리소스를 관리하는 공유 패턴: InferenceService CRD ([006](006-model-serving-inference.md)), Kaniko Job과 ConfigMap ([008](008-custom-notebook-images.md)), pod 메트릭 조회 ([010](010-notebook-resource-profiles.md)).

**Workspace 생명주기 상태 머신** ([002](002-jupyterhub-notebook.md)에서 도입) — PENDING → RUNNING → IDLE → STOPPED/FAILED. 커스텀 이미지 ([008](008-custom-notebook-images.md)), Streamlit 프로세스 생명주기 ([009](009-streamlit-visualization.md)), 프로파일 전환 ([010](010-notebook-resource-profiles.md))에서 확장됨.

**단일 노트북 이미지** ([002](002-jupyterhub-notebook.md)에서 도입) — 하나의 Docker 이미지가 JupyterHub 서버, Airflow 파이프라인 워커, Spark executor, 데이터 프로비저닝 컨테이너로 사용된다. 커스텀 이미지 ([008](008-custom-notebook-images.md))가 이 base를 확장한다.

## 주요 참고 자료

- [ARCHITECTURE.md](../ARCHITECTURE.md) — 시스템 다이어그램, 데이터 모델, API 참조
- [TROUBLESHOOTING.md](../TROUBLESHOOTING.md) — 13개 문서화된 배포 이슈 및 수정
- [PROJECT_REFERENCE.md](../../PROJECT_REFERENCE.md) — 종합 프로젝트 참조
- `specs/NNN-*/spec.md` — 사용자 스토리와 인수 기준이 포함된 공식 스펙
