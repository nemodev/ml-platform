# Feature 002: JupyterHub Notebook Embedding

> SSO passthrough, named server 격리, 5단계 workspace 생명주기를 갖춘 JupyterHub 노트북 서버를 Angular 포털에 임베딩한다.

## 개요 및 배경

데이터 과학자는 별도의 로그인이 필요한 독립 애플리케이션이 아닌, 플랫폼에 통합된 인터랙티브 노트북이 필요하다. JupyterHub를 iframe으로 임베딩하여 사용자가 탭을 클릭하면 모든 ML 라이브러리가 사전 구성된 노트북 환경을 바로 사용할 수 있는 원활한 경험을 제공한다. 각 analysis는 고유한 named JupyterHub server를 받아, 사용자별 오버헤드 없이 파일 격리를 제공한다. JupyterHub의 GenericOAuthenticator가 Feature 001의 동일한 Keycloak realm에 위임하므로, 별도의 로그인이 필요 없다. 이 Feature는 이후 Feature들에서 반복되는 두 가지 패턴을 확립한다: **workspace 생명주기 상태 머신** (PENDING→RUNNING→IDLE→STOPPED/FAILED)과 **iframe 임베딩 접근 방식**.

## 아키텍처

```
Angular Portal
    ↓ (iframe: /user/{username}/{serverName}/lab)
JupyterHub Proxy (Z2JH Helm)
    ↓
KubeSpawner → Named Server Pod (per analysis)
    ↓
ml-platform-notebook:latest image
    (Python 3.11, ML libs, Java 17, Spark 4.0.1)
```

**주요 설계 결정:**

- **Z2JH Helm chart** — 공식 Zero to JupyterHub Helm chart는 KubeSpawner, idle culler, 설정 가능한 proxy를 번들링한다. 커스텀 K8s 매니페스트 대신 선택한 이유는, 멀티 유저 노트북 관리의 복잡성을 처리해 주기 때문이다.
- **Analysis별 named server** — 각 analysis UUID가 JupyterHub server name(`/user/{username}/{analysisId}`)이 된다. 사용자는 독립된 파일 시스템과 커널 상태를 가진 여러 analysis를 동시에 실행할 수 있다.
- **Workspace 생명주기 상태 머신** — Backend가 매 요청마다 JupyterHub API와 조정되는 `Workspace` 엔티티 status를 유지한다. 상태: PENDING (생성 중), RUNNING (준비됨, 활성), IDLE (준비됨, 비활성), STOPPED (종료됨), FAILED (오류). 실행 시, 클러스터 재시작으로 인한 오래된 DB 레코드를 감지하여 새 spawn 전에 STOPPED로 표시한다.
- **GenericOAuthenticator + auto_login** — JupyterHub가 Keycloak OIDC를 통해 인증한다. `auto_login: true`는 활성 Keycloak 세션이 있는 사용자가 JupyterHub 로그인 페이지를 완전히 건너뛸 수 있음을 의미한다.
- **단일 노트북 이미지** — 하나의 Docker 이미지(`ml-platform-notebook:latest`)가 JupyterHub 사용자 서버, Airflow 파이프라인 워커, Spark executor, 데이터 프로비저닝 Job으로 사용된다. 이로써 플랫폼 전체에서 환경 동일성을 보장한다.
- **상대 iframe URL** — `JupyterHubService`가 상대 경로(`/user/{username}/{server}/lab`)를 반환하므로, URL 재작성 없이 localhost, NodePort, 프로덕션 도메인에서 iframe이 작동한다.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `service/WorkspaceService.java` | 생명주기 관리, 상태 조정, spawn options |
| Backend | `service/JupyterHubService.java` | JupyterHub REST API 클라이언트 (2개 WebClient: hub + proxy) |
| Backend | `controller/WorkspaceController.java` | Analysis 범위의 REST 엔드포인트 |
| Backend | `model/Workspace.java` | WorkspaceStatus enum을 가진 JPA 엔티티 |
| Frontend | `features/notebooks/notebooks.component.ts` | Iframe 로딩, bridge 초기화, 폴링, profile/image 전환 |
| Frontend | `core/services/workspace.service.ts` | `watchStatusUntilStable()` 폴링을 포함한 HTTP 클라이언트 |
| Infra | `helm/jupyterhub/values.yaml` | Z2JH 설정: OAuth, CSP, idle culler, named servers |
| Infra | `docker/notebook-image/Dockerfile` | scipy-notebook + Java + Spark + ML libs + JupyterLab 커스터마이징 |

**실행 시 상태 조정:** 새 workspace를 생성하기 전에, `WorkspaceService.launchWorkspace()`가 각 "활성" DB 레코드에 대해 JupyterHub를 조회한다. JupyterHub가 서버를 중지됨으로 보고하면 (예: 모든 사용자 pod를 종료한 Helm 업그레이드 이후), DB 레코드를 STOPPED로 표시한다. 이로써 오래된 상태로 인한 CONFLICT 오류를 방지하고 클러스터 재시작에 대해 시스템이 자체 복구되도록 한다.

**SSO 재인증 감지:** iframe이 로드될 때, `notebooks.component.ts`가 콘텐츠에서 "403 Forbidden" 텍스트를 검사한다. 감지되면 (다른 사용자에 대한 오래된 세션), `/hub/logout?next={workspace-url}`을 통해 리다이렉트하여 새로운 Keycloak OAuth 플로우를 트리거한다. `reauthAttempted` 플래그가 무한 루프를 방지한다.

**Idle culling:** Z2JH idle culler가 30분간 비활성인 서버를 종료한다 (5분마다 확인). Named server는 자동으로 제거되어, 클러스터 리소스를 사용 가능하게 유지한다.

## 과제 및 해결

- **클러스터 재시작 후 오래된 workspace 레코드** — JupyterHub pod가 종료되었지만 DB에는 여전히 RUNNING으로 표시된다. 해결: 매 실행 시도마다 DB를 JupyterHub API와 조정하고, 고아 레코드를 자동으로 STOPPED로 표시.
- **iframe 임베딩을 위한 CSP** — JupyterHub의 기본 CSP가 프레이밍을 차단한다. 해결: Helm values에서 포털의 origin(localhost:4200, 프로덕션 도메인 등)을 허용하도록 `frame-ancestors` 설정.
- **하드코딩된 Keycloak authorize URL** — JupyterHub의 `authorize_url`은 브라우저 접근 가능해야 한다 (in-cluster DNS 아님). 현재 r1 클러스터 IP `172.16.100.10:30080`으로 하드코딩됨. Token/userdata URL은 서버 간 통신이므로 in-cluster DNS를 올바르게 사용한다.
- **JupyterHubService의 Thread.sleep** — `fetchContents()`가 일시적 실패 시 `Thread.sleep(200)`으로 재시도한다. 이는 executor 스레드를 차단한다; reactive retry가 더 나을 것이다.

## 제한 사항

- **리소스 핫 리사이징 불가** — compute profile 변경은 workspace 재시작이 필요하다. JupyterHub의 KubeSpawner는 라이브 리소스 변경을 지원하지 않는다.
- **사용자당 10Gi 스토리지** — 동적 PVC 프로비저닝이 고정 10Gi를 할당한다. Analysis별 스토리지 쿼터 없음.
- **Helm values에 하드코딩된 Keycloak URL** — authorize URL이 특정 IP를 사용하여, 배포 컨텍스트 간 이식성을 깨뜨린다.
- **디스크 사용량 경고 없음** — PVC가 가득 차도, 플랫폼에서 사전 경고를 제공하지 않는다.
- **커널 상태가 폴링 방식** — 프론트엔드가 5초마다 폴링한다. WebSocket 기반 커널 상태가 더 반응적이겠지만 복잡성이 추가된다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| JupyterHub 독립 실행 (iframe 없음) | 통합 포털 경험을 깨뜨린다. 사용자가 2개의 별도 앱을 관리해야 한다. |
| ContainDS Dashboards | 제한적인 커뮤니티 지원. Z2JH가 JupyterHub의 표준 Kubernetes 배포 방식이다. |
| BinderHub | 영구 사용자 workspace가 아닌, 임시 환경용으로 설계되었다. |
| Direct KubeSpawner (Helm 없음) | Idle culling, proxy 관리, OAuth 설정을 재구현해야 한다. Z2JH가 이 모든 것을 처리한다. |
| 사용자별 서버 (named 아님) | 각 사용자가 서버 1개만 가진다. Named server는 analysis별 격리를 허용한다 — Feature 007에 필수적이다. |

## 향후 개선 사항

- **WebSocket 커널 상태** — 5초 폴링을 JupyterHub의 WebSocket API로 대체하여 실시간 커널 상태 변경 감지.
- **Keycloak URL 파라미터화** — 멀티 환경 배포 지원을 위해 authorize URL에 Helm values 또는 환경 변수 사용.
- **스토리지 쿼터 및 알림** — PVC 사용량을 모니터링하고 10Gi 한도에 접근하는 사용자에게 경고.
- **사전 생성된 서버 풀** — 할당 대기 중인 idle 노트북 pod 풀을 유지하여 cold-start 시간 단축.
- **우아한 세션 핸드오프** — 403 감지 + 리다이렉트 대신, JupyterHub의 세션 관리를 사용하여 사용자 전환을 깔끔하게 처리.
