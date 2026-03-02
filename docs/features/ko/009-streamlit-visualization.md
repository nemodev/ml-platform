# Feature 009: Streamlit Visualization

> 노트북 pod 내에서 jupyter-server-proxy를 통해 Streamlit 대시보드를 실행하며, 파일 자동 발견과 시작 폴링을 갖춘 세 번째 탭으로 analysis 레이아웃에 임베딩한다.

## 개요 및 배경

노트북은 탐색에는 뛰어나지만 이해관계자에게 결과를 발표하기에는 부족하다. Streamlit은 프론트엔드 코드 없이 Python 스크립트를 인터랙티브 웹 대시보드로 변환한다. Streamlit을 별도 서비스로 배포하는 대신, `jupyter-server-proxy`를 사용하여 기존 노트북 pod 내에서 실행한다 — JupyterLab을 이미 서빙하는 동일한 인프라이다. 사용자가 `visualize/` 디렉토리에 `.py` 파일을 생성하면, 플랫폼이 `import streamlit`을 스캔하여 자동 발견한다. "Visualization" 탭이 analysis 레이아웃에 나타나고, 사용자가 드롭다운에서 파일을 선택하면 Streamlit 앱이 iframe에 로드된다. 이것은 세 번째 iframe 패턴이다: JupyterLab은 postMessage bridge (Feature 007), MLflow는 CSS injection (Feature 003), Streamlit은 **polling startup detection**을 사용한다.

## 아키텍처

```
Analysis Layout (Visualization tab)
    ↓ GET /api/v1/analyses/{id}/visualizations/files
Backend (StreamlitService)
    ↓ proxy to notebook pod
Jupyter Server Extension (ml_platform_streamlit)
    ↓ GET /api/streamlit/files → scan visualize/ for import streamlit
    ↓ POST /api/streamlit/start → subprocess.Popen(streamlit run ...)
    ↓ GET /api/streamlit/status → check if port is listening
    ↓
jupyter-server-proxy
    ↓ /user/{username}/{server}/proxy/{port}/
Angular iframe (Streamlit dashboard)
```

**주요 설계 결정:**

- **서빙을 위한 jupyter-server-proxy** — 노트북 pod에서 임의의 웹 서비스를 프록시하는 표준 JupyterHub 메커니즘. WebSocket 지원, 인증(JupyterHub 세션 통해), URL 라우팅을 처리한다. 인프라 변경 불필요.
- **경량 Jupyter server extension** — `ml_platform_streamlit`은 4개의 Tornado 핸들러(files, start, stop, status)를 가진 커스텀 확장 프로그램이다. 모듈 수준 상태를 사용하여 pod당 단일 Streamlit 서브프로세스를 관리한다. 프로세스 생명주기는 단순하다: 한 번에 하나의 앱, 새 것을 시작하기 전에 이전 것을 종료.
- **텍스트 기반 파일 감지** — `visualize/` 아래의 `.py` 파일에서 `import streamlit` 또는 `from streamlit` 문자열을 스캔한다. 코드 실행 없음 — 안전하고 빠르다.
- **Polling startup detection** — Streamlit 시작 후, 프론트엔드가 포트가 리스닝될 때까지 2초마다 `/api/streamlit/status`를 폴링한다 (상태가 `starting`에서 `running`으로 전환). 오류 표시 전 60초 타임아웃.
- **탭 전환 시 프로세스 유지** — 사용자가 Notebooks나 Experiments 탭으로 전환할 때 Streamlit이 계속 실행된다. 컴포넌트가 `display:none`을 통해 DOM에 보존되어 (Feature 007의 노트북과 동일한 패턴), WebSocket 연결과 앱 상태를 유지한다.
- **플랫폼 패키지 보호** — `platform-constraints.txt`가 커스텀 이미지 빌드(Feature 008)에서 pip의 `--constraint` 플래그를 통해 `streamlit`이나 `jupyter-server-proxy`를 다운그레이드하는 것을 방지한다.

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `controller/VisualizationController.java` | 파일, 시작, 중지, 상태 엔드포인트 (analysis 범위) |
| Backend | `service/StreamlitService.java` | 노트북 pod의 extension API로 요청 프록시 |
| Frontend | `features/visualization/visualization.component.ts` | 상태 머신, 파일 로딩, 시작 폴링, iframe 임베딩 |
| Frontend | `core/services/visualization.service.ts` | visualization 엔드포인트용 HTTP 클라이언트 |
| Frontend | `features/analyses/analysis-layout.component.ts` | Visualization 탭 통합, 컴포넌트 보존 |
| Extension | `ml_platform_streamlit/__init__.py` | 확장 프로그램 등록, localhost IPv6 수정 |
| Extension | `ml_platform_streamlit/handlers.py` | Tornado 핸들러: files, start, stop, status |
| Infra | `docker/notebook-image/Dockerfile` | 확장 프로그램 설치, requirements에 streamlit |
| Infra | `docker/notebook-image/visualize/sample_dashboard.py` | 샘플 California Housing 대시보드 |
| Infra | `docker/notebook-image/platform-constraints.txt` | 핀된 플랫폼 패키지 |

**Extension 핸들러 상세:** `StreamlitFilesHandler`가 `visualize/`를 순회하며 각 `.py` 파일에서 Streamlit import를 확인한다. `StreamlitStartHandler`는 파일 경로를 검증하고 (`..` 거부, `visualize/` 접두사 요구), 실행 중인 프로세스를 종료하고, 사용 가능한 포트를 찾아 (8501부터 시작), `streamlit run`을 플래그와 함께 생성한다: `--server.headless true`, `--server.enableCORS false`, `--server.enableXsrfProtection false`, `--browser.gatherUsageStats false`. `StreamlitStatusHandler`는 `_check_process_status()`를 호출하여, 포트가 사용 중이면 (소켓 프로브) `starting`에서 `running`으로, 프로세스가 죽었으면 (stderr 마지막 20줄 읽기) `errored`로 전환한다.

**localhost IPv6 수정:** `_patch_proxy_localhost()`가 `jupyter_server_proxy.LocalProxyHandler.proxy()`를 몽키패치하여, 일부 시스템에서 `::1`로 해석되어 Streamlit 연결을 끊을 수 있는 `localhost` 대신 `127.0.0.1`을 사용한다.

**프론트엔드 상태 머신:** visualization 컴포넌트가 상태를 관리한다: `loading-files` → `no-files` (가이드 표시) 또는 `starting` → `running` (iframe) 또는 `errored`. Streamlit 파일이 없으면, 코드 예제와 함께 생성 방법을 설명하는 가이드 카드가 표시된다. 파일 선택 드롭다운은 여러 파일이 발견될 때만 나타난다.

**Backend 프록시:** `StreamlitService`가 workspace 컨텍스트(analysis ID에서 username, server name)를 해석하고, workspace가 RUNNING 또는 IDLE인지 확인한 후, `JupyterHubService`의 WebClient를 통해 노트북 pod의 extension API로 요청을 프록시한다. Dev profile은 mock 파일 목록을 반환한다.

**샘플 대시보드:** `sample_dashboard.py`는 사이드바 필터(소득 범위, 건물 연도), feature 분포 차트, scatter plot, 상관행렬, 지리 지도, 원시 데이터 뷰어를 갖춘 California Housing 데이터셋의 현실적인 인터랙티브 대시보드이다. 효율적인 데이터 로딩을 위해 `@st.cache_data`를 사용한다.

## 과제 및 해결

- **iframe에서의 Streamlit CORS 및 XSRF** — Streamlit의 내장 CORS 및 XSRF 보호가 jupyter-server-proxy를 통해 iframe에서 서빙될 때 깨진다. 해결: 둘 다 비활성화 (`--server.enableCORS false`, `--server.enableXsrfProtection false`). 보안은 JupyterHub의 인증 경계에 의해 제공된다.
- **포트 찾기 경쟁 조건** — `_find_available_port()`와 서브프로세스 바인드 사이에 다른 프로세스가 포트를 점유할 수 있다. pod당 하나의 Streamlit 프로세스만 실행되므로 완화된다.
- **Streamlit 시작 지연** — Streamlit은 시작에 3-10초가 걸릴 수 있다. 60초 타임아웃이 있는 2초 폴링 간격으로 사용자 불만 없이 안정적인 시작 감지를 제공한다.
- **프로세스 고아화** — `_kill_process()` 호출 없이 확장 프로그램이 리로드되면, Streamlit 프로세스가 고아가 될 수 있다. 새 프로세스를 시작하기 전에 기존 프로세스를 종료함으로써 완화된다.

## 제한 사항

- **Workspace당 하나의 Streamlit 프로세스** — 한 번에 하나의 앱만 실행된다. 다른 파일을 선택하면 이전 것이 종료된다.
- **앱 선택 지속성 없음** — 다른 탭으로 전환했다 돌아오면 파일을 다시 스캔하고 앱을 재시작할 수 있다.
- **파일 감지가 `visualize/` 디렉토리 필요** — 이 디렉토리 밖의 파일은 발견되지 않는다. 의도적인 설계(명확한 관습)이지만 설정 불가하다.
- **Streamlit 앱 로깅 없음** — 서브프로세스 stdout/stderr가 파일에 캡처되지 않아, 디버깅이 어렵다.
- **Extension의 모듈 수준 상태** — `_process_state`가 모듈 수준 dict이며, 스레드 안전하지 않다. Tornado의 이벤트 루프는 단일 스레드이지만, 동시 async 요청은 이론적으로 경쟁할 수 있다.
- **고정 2초 폴링 간격** — 지수 백오프 없음. 높은 부하에서 폴링이 불필요한 요청을 추가할 수 있다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| 독립 Streamlit 배포 | 앱당 별도 pod가 인프라 복잡성을 추가한다. jupyter-server-proxy가 기존 노트북 pod를 재사용한다. |
| Panel / Dash / Voila | Streamlit이 가장 단순한 개발자 경험을 제공한다 (콜백 없음, 레이아웃 코드 없음). 실제 프로젝트 팀이 선호한다. |
| 시작 감지를 위한 WebSocket | 복잡성 추가. 단순한 HTTP 폴링이 안정적이고 플랫폼의 기존 패턴과 일치한다. |
| Streamlit 파일별 서브 탭 | 더 많은 UI 복잡성. 드롭다운이 더 간단하고 많은 파일에 더 잘 확장된다. |
| 전체 workspace 자동 스캔 | 너무 시끄럽다. `visualize/` 관습이 명확한 의도를 제공하고 false positive를 방지한다. |

## 향후 개선 사항

- **멀티 앱 지원** — 다른 포트에서 파일당 하나씩 여러 Streamlit 프로세스 허용.
- **앱 로그 뷰어** — Streamlit stdout/stderr를 캡처하여 디버깅을 위해 포털에 표시.
- **앱 고정** — analysis별로 사용자의 마지막 선택 Streamlit 파일을 기억.
- **핫 리로드** — 파일 변경을 감지하고 앱 재시작을 제안 (Streamlit은 `--server.runOnSave`를 지원).
- **적응형 폴링** — 브라우저 탭이 비활성일 때 폴링을 줄이기 위해 VisibilityAPI 사용.
