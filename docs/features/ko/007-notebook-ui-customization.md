# Feature 007: Notebook UI Customization

> Analysis 엔티티를 도입하여 workspace와 experiment를 범위화하고, 중복된 JupyterLab 크롬을 숨기며, 포털 수준 노트북 제어를 위한 postMessage 커맨드 브릿지를 추가한다.

## 개요 및 배경

이 Feature 없이는, 임베딩된 JupyterLab이 "두 애플리케이션을 붙여놓은 것"처럼 보인다 — 포털에 자체 nav가 있고, JupyterLab에도 자체 메뉴바, 상태바, 툴바가 있다. Feature 007은 JupyterLab의 네이티브 크롬을 숨기고 포털이 제어하는 툴바로 대체함으로써 중복을 제거한다. 또한 **Analysis 엔티티**를 도입하여, workspace, experiment, visualization을 단일 이름 있는 컨텍스트 아래에 그룹화한다. 이것이 아키텍처의 전환점이다 — Feature 002, 003, 005, 009 모두 analysis 범위의 URL(`/api/v1/analyses/{analysisId}/...`)을 참조하여, Analysis를 중심 조직 개념으로 만든다.

## 아키텍처

이 Feature는 3개 수준에서 작동한다:

**1. Analysis 엔티티 (backend):**
```
Analysis (user_id, name, description)
    ├── Workspace (analysis_id) — analysis당 하나의 활성
    ├── Experiments (analysis 접두사가 붙은 MLflow)
    └── Visualizations (workspace pod의 Streamlit 앱)
```

**2. JupyterLab 크롬 제거 (Dockerfile):**
- `page_config.json`이 확장 프로그램을 비활성화: main menu, terminal, extension manager, running sessions, announcements
- CSS injection이 `#jp-menu-panel`과 `.jp-NotebookPanel-toolbar`를 `display:none!important`로 숨김
- 설정 오버라이드가 업데이트 확인과 뉴스를 억제

**3. postMessage 커맨드 브릿지 (frontend ↔ iframe):**
```
Angular (notebooks.component.ts)
    ↓ JupyterBridgeService.execute('notebook:run-all-cells')
    ↓ Comlink RPC via postMessage
JupyterLab (jupyter-iframe-commands extension)
    ↓ executes command
    ↓ returns result
Angular (bridge receives result)
```

**주요 설계 결정:**

- **범위화 엔티티로서의 Analysis** — 모든 workspace와 experiment가 Analysis에 속한다. 사용자가 analysis(예: "Housing Price Study")를 생성하면 모든 작업이 그 아래에 조직된다. 이것은 workspace와 experiment가 사용자 수준이었던 이전의 플랫 모델을 대체한다.
- **이중 안전 크롬 제거** — 확장 프로그램 비활성화(코드 로딩 방지)와 CSS injection(빠져나가는 것 포착) 모두 사용. 확장 프로그램 비활성화가 더 효율적이고; CSS가 JupyterLab 버전 변경에 대한 복원력을 제공한다.
- **REST 대신 postMessage 브릿지** — `jupyter-iframe-commands-host` npm 라이브러리와 JupyterLab 대응부(`jupyter-iframe-commands`)가 postMessage를 통한 RPC에 Comlink을 사용한다. 이로써 포털이 backend를 거치지 않고 모든 JupyterLab 커맨드(셀 실행, 사이드바 토글, 테마 변경)를 실행할 수 있다.
- **display:none을 통한 컴포넌트 보존** — analysis 레이아웃이 탭 전환 시 Notebooks와 Visualization 컴포넌트를 DOM에 유지(`[style.display]`로 숨김)하여 iframe 상태를 보존한다. Experiments 컴포넌트는 신선한 MLflow 데이터를 위해 매 방문 시 재생성된다.
- **파일 브라우저 사이드바는 토글 가능하되 제거하지 않음** — 사용자에게 멀티 파일 워크플로우가 필요하므로, 사이드바는 기본적으로 접혀 있지만 포털 툴바를 통해 토글할 수 있다.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `model/Analysis.java` | JPA 엔티티: UUID PK, user FK, name, description |
| Backend | `service/AnalysisService.java` | 사용자 격리를 갖춘 CRUD, 중복 이름 방지 |
| Backend | `controller/AnalysisController.java` | 표준 REST: POST, GET 목록, GET 단일, DELETE |
| Backend | `service/WorkspaceService.java` | single-doc 모드를 위한 선택적 `notebookPath`를 지원하는 `getWorkspaceUrl()` |
| Backend | `service/JupyterHubService.java` | 집중 노트북 보기를 위한 `getDocUrl()` 및 `getNamedServerDocUrl()` |
| Frontend | `features/analyses/analysis-layout.component.ts` | 탭 컨테이너: Notebooks, Experiments, Visualization |
| Frontend | `core/services/jupyter-bridge.service.ts` | postMessage 브릿지 생명주기, 커맨드 실행, 오류 처리 |
| Frontend | `features/notebooks/notebooks.component.ts` | 툴바 커맨드, 브릿지 초기화, 커널 상태, 사이드바/테마 토글 |
| Frontend | `core/services/analysis.service.ts` | 선택된 analysis 캐싱을 포함한 Analysis CRUD 클라이언트 |
| Infra | `docker/notebook-image/Dockerfile` | 확장 프로그램 비활성화, CSS injection, 설정 오버라이드 |
| DB | `V008__create_analyses_and_link_workspaces.sql` | Analysis 테이블 + workspace.analysis_id FK |

**브릿지 생명주기:** `JupyterBridgeService`가 라이브러리의 `createBridge()`를 래핑하여, 반응형 `connectionState` 시그널(idle → connecting → ready → disconnected)을 관리한다. 상태가 `ready`일 때만 커맨드가 실행된다. 알 수 없는 커맨드 오류는 throw하지 않고 경고로 로깅한다 — 우아한 성능 저하로 브릿지가 실패해도 노트북이 사용 가능하게 유지된다. 디버그 로그는 100개 항목으로 제한된다.

**포털 툴바 커맨드:** notebooks 컴포넌트가 노출하는 것: 모든 셀 실행, 커널 인터럽트, 커널 재시작, 출력 지우기, 저장; 셀 작업(삽입, 이동, undo/redo); 뷰 토글(사이드바, 헤더, 줄 번호, 테마); 그리고 사용 가능한 모든 JupyterLab 커맨드를 나열하는 검색 가능한 커맨드 팔레트.

**Analysis 삭제 가드:** `AnalysisService.deleteAnalysis()`가 활성 workspace(PENDING/RUNNING/IDLE)를 확인하고 존재하면 409 CONFLICT를 throw한다. 사용자는 analysis를 삭제하기 전에 workspace를 종료해야 한다.

**Single-document 모드 (P3):** JupyterLab의 네이티브 `/doc/tree/{path}` URL 패턴이 탭이나 파일 브라우저 없이 단일 노트북에 집중된 뷰를 제공한다. `WorkspaceController`가 URL 엔드포인트에서 선택적 `notebookPath` 쿼리 파라미터를 받고, `JupyterHubService`가 적절한 doc-mode URL을 구성한다.

## 과제 및 해결

- **키보드 단축키는 메뉴 제거 후에도 유지** — 메뉴바 제거는 시각적일 뿐이다. JupyterLab 단축키는 shortcut 확장 프로그램(메뉴 확장 프로그램과 별도)에 의해 등록되므로, Ctrl+S, Ctrl+Shift+Enter 등이 계속 작동한다.
- **테마 동기화** — 포털 테마가 권위적이어야 한다. 브릿지 초기화 시 컴포넌트가 `JupyterLab Light` 또는 `JupyterLab Dark`로 `apputils:change-theme`를 호출하여 동기화한다. JupyterLab은 독립적인 테마 환경설정을 유지하지 않는다.
- **브릿지 실패 시 우아한 성능 저하** — 브릿지 라이브러리가 초기화에 실패하면 (예: JupyterLab 버전 비호환), 노트북은 JupyterLab의 네이티브 키보드 단축키를 통해 완전히 사용 가능하다. 포털 툴바 버튼만 나타나지 않는다.
- **재인증 시 iframe 403** — Feature 002와 동일한 감지 패턴으로 처리: iframe 콘텐츠 확인, `/hub/logout`을 통해 리다이렉트, Keycloak을 통해 재인증.

## 제한 사항

- **CSS injection이 취약** — JupyterLab의 `index.html`에 CSS를 주입하는 `sed` 명령이 특정 파일 구조를 가정한다. JupyterLab 메이저 버전 업그레이드 시 깨질 수 있다.
- **협업 편집 없음** — 각 analysis workspace는 단일 사용자이다. 실시간 협업은 JupyterHub의 RTC 기능이 필요하다.
- **postMessage 브릿지는 same-origin 필요** — 포털과 JupyterLab이 동일한 origin으로 서빙되기 때문에 (nginx proxy를 통해) 브릿지가 작동한다. 다른 호스팅 아키텍처에서는 깨진다.
- **Analysis 공유 없음** — Analysis는 엄격히 사용자 범위이다. Analysis를 공유하거나 사용자 간 소유권을 이전하는 메커니즘이 없다.
- **Dockerfile에서 상태바 비활성화되었지만 문서화되지 않음** — research.md에 `@jupyterlab/statusbar-extension`이 비활성화된 것으로 나열되지만, 실제 Dockerfile의 비활성화 확장 프로그램 목록에는 포함되지 않는다. 상태바가 여전히 보일 수 있다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| 크롬 제거를 위한 커스텀 JupyterLab 확장 프로그램 | 버전에 걸쳐 JupyterLab 플러그인 유지 보수가 필요하다. CSS + config가 더 간단하다. |
| 노트북 커맨드용 REST API | backend를 거치는 더 느린 왕복. postMessage가 iframe과 호스트 간 직접 통신이다. |
| Analysis를 위한 탭 (별도 페이지 아님) | Analysis 관리는 일급 개념이다; 자체 라우트와 목록 뷰를 가질 자격이 있다. |
| iframe sandbox 속성 | 필요한 기능(scripts, same-origin)을 차단한다. JupyterLab과 호환되지 않는다. |
| JupyterLab 완전 제거 (커스텀 노트북 UI) | 막대한 범위. JupyterLab은 성숙하고 기능이 완전하다. 임베딩이 실용적이다. |

## 향후 개선 사항

- **크롬 제거를 위한 JupyterLab 확장 프로그램** — CSS injection을 설정 시스템과 통합되는 적절한 JupyterLab 확장 프로그램으로 대체하여, 업그레이드에도 유지.
- **Analysis 공유 및 협업** — 뷰어/편집자 역할로 다른 사용자를 analysis에 초대할 수 있도록 허용.
- **브릿지 커맨드 텔레메트리** — 사용자가 가장 자주 실행하는 브릿지 커맨드를 추적하여 툴바 디자인 결정에 반영.
- **Analysis별 노트북 템플릿** — Analysis 생성 시 analysis 컨텍스트로 사전 구성된 스타터 노트북 자동 생성.
- **Analysis 내보내기/가져오기** — 이전 또는 보관을 위해 analysis(노트북, 실험 메타데이터, 설정)를 패키지화.
