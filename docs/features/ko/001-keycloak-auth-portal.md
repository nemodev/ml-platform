# Feature 001: Keycloak Auth & Portal

> Keycloak OIDC를 통한 Single Sign-On과 통합 Angular 포털로, 모든 플랫폼 컴포넌트의 인증 기반을 구축한다.

## 개요 및 배경

ML Platform은 독립적으로 개발된 6개 시스템(JupyterHub, MLflow, Airflow, KServe, MinIO, 포털 자체)에 걸친 단일 인증 경계가 필요하다. Keycloak을 OIDC provider로 선택한 이유는 오픈소스이고, realm 기반 멀티테넌시를 지원하며, 모든 하위 컴포넌트와의 네이티브 통합을 제공하기 때문이다. 이 Feature는 로그인 플로우, 사용자 동기화, 그리고 다른 모든 Feature를 호스팅하는 포털 셸을 구축한다. 이후의 모든 Feature는 001이 제공하는 JWT 토큰과 사용자 ID에 의존한다.

## 아키텍처

인증 플로우는 Authorization Code + PKCE(권장 SPA 패턴)를 사용한다:

```
Browser → Angular SPA → Keycloak (PKCE) → JWT issued
                ↓
        Spring Boot Backend (validates JWT via cached JWKS)
                ↓
        User synced to PostgreSQL (oidc_subject as canonical ID)
```

**주요 설계 결정:**

- **Stateless JWT 검증** — Backend는 Keycloak의 JWKS 공개키를 캐시하고 토큰을 로컬에서 검증한다. 세션 상태 없음, 요청마다 Keycloak 의존성 없음.
- **첫 API 호출 시 사용자 동기화** — `UserService.syncFromJwt()`가 JWT 클레임(`sub`, `preferred_username`, `email`)으로부터 사용자 레코드를 생성 또는 업데이트한다. `oidc_subject` 컬럼(Keycloak의 `sub` 클레임 / 사용자 UUID)이 정규 식별자이므로, Keycloak에서 username을 변경해도 ID가 깨지지 않는다.
- **3개의 Spring profile** — `dev` (mock JWT, H2 database, 인프라 불필요), `local` (port-forward를 통한 실제 Keycloak), default (in-cluster K8s DNS).
- **Dev profile mocking** — `DevSecurityConfig`가 매 요청마다 가짜 JWT를 주입하여, Keycloak 없이 전체 기능 개발이 가능하다. 이 패턴은 모든 Feature에서 반복된다 — 모든 외부 서비스 통합에 `isDevProfile()` 폴백이 있다. 여기서 도입되어 002–010에서 참조된다.

**하위 시스템으로의 토큰 전파:**

| 컴포넌트 | 인증 방식 |
|-----------|-------------|
| Backend API | JWT Bearer (JWKS로 검증) |
| JupyterHub | GenericOAuthenticator (Keycloak OIDC) |
| MLflow | 직접 인증 없음; backend를 통해 프록시 |
| Airflow | Basic auth, backend를 통해 프록시 |
| MinIO | AWS credentials (K8s secrets, 사용자 토큰 아님) |
| KServe | Inference는 backend를 통해 프록시 |

## 주요 구현

| 레이어 | 주요 파일 | 용도 |
|-------|-----------|---------|
| Backend | `config/SecurityConfig.java` | OAuth2 resource server, JWKS endpoint, stateless sessions |
| Backend | `config/DevSecurityConfig.java` | dev profile용 mock JWT 필터 |
| Backend | `service/UserService.java` | JWT → user 동기화 (per-subject 잠금) |
| Backend | `controller/AuthController.java` | `/api/v1/auth/userinfo` 및 `/api/v1/auth/logout` |
| Backend | `config/CorsConfig.java` | profile별 allowed origins |
| Frontend | `core/auth/oidc-auth.module.ts` | OIDC 클라이언트 설정 (WebCrypto 감지를 통한 PKCE) |
| Frontend | `core/services/auth.service.ts` | 인증 상태 관리, login/logout 오케스트레이션 |
| Frontend | `core/guards/auth.guard.ts` | OIDC callback 감지를 포함한 라우트 가드 |
| Frontend | `core/interceptors/auth.interceptor.ts` | JWT Bearer 첨부, dev stub 토큰 |
| Infra | `k8s/keycloak/configmap.yaml` | Realm 설정: 3개 클라이언트, 2명 테스트 사용자 |
| Infra | `k8s/keycloak/deployment.yaml` | Keycloak 26.1 (`--import-realm` 포함) |

**OIDC 플로우 상세:** Angular 프론트엔드는 `angular-auth-oidc-client`를 조건부 PKCE와 함께 사용한다 — `window.crypto.subtle` 가용성을 확인하고, 구형 브라우저에서는 implicit flow로 폴백한다. Silent token refresh로 세션을 유지한다. `auth.guard.ts`는 활성 OIDC callback(code flow `?code=...&state=...` 및 implicit flow `#id_token=...`)을 감지하여 리다이렉트를 차단하지 않는다.

**사용자 동기화 동시성:** `UserService`는 `ConcurrentHashMap`과 per-OIDC-subject 잠금을 사용하여 여러 탭에서의 동시 로그인을 처리한다. 동기화는 `PROPAGATION_REQUIRES_NEW`에서 실행되어 원자적 사용자 생성을 보장한다.

**Keycloak realm 사전 구성:** ConfigMap은 3개 클라이언트를 정의한다: `ml-platform-portal` (public, PKCE), `ml-platform-jupyterhub` (confidential, Feature 002용), `ml-platform-cli` (public, direct grants, 향후 CLI용). Redirect URI는 모든 배포 컨텍스트를 포함한다 — localhost, K8s NodePort, r1 클러스터 IP, 프로덕션 도메인.

## 과제 및 해결

- **Keycloak 26 읽기 전용 username** — Keycloak 26에서 `username`이 읽기 전용 사용자 속성이 되었다 (관리자도 마찬가지). 테스트 사용자 이름 변경은 PostgreSQL 직접 업데이트와 Keycloak 재시작이 필요했다. `docs/TROUBLESHOOTING.md` 항목 4–5에 문서화됨.
- **username 변경 후 사용자 ID** — `users` 테이블의 `oidc_subject`가 Keycloak 사용자 UUID와 일치하지 않으면, `syncFromJwt()`가 중복 사용자 레코드를 생성하고 기존 analysis가 보이지 않게 된다. 수정: `oidc_subject`가 항상 Keycloak `sub` 클레임과 일치하도록 보장.
- **3개 profile에 걸친 CORS** — 각 profile마다 다른 allowed origins가 필요하다. profile별 YAML 파일의 `app.cors.allowed-origins`로 외부화했다.

## 제한 사항

- **RBAC 없음** — 모든 인증된 사용자가 동일한 접근 권한을 가진다. JWT에서 role 클레임을 추출하지 않는다. 역할 추가 시 토큰에서 `realm_access.roles` 추출이 필요하다.
- **유휴 세션 타임아웃 없음** — 브라우저를 닫아도 토큰이 24시간 동안 유효하다. 로그아웃은 사용자가 로그아웃 버튼을 클릭할 때만 발생한다.
- **K8s 매니페스트에 하드코딩된 자격 증명** — Keycloak 관리자 비밀번호(`admin/admin`)와 JupyterHub 클라이언트 시크릿이 Secrets가 아닌 ConfigMap에 있다.
- **감사 로깅 없음** — 로그인/로그아웃 이벤트가 기록되지 않는다. `syncFromJwt()`가 `last_login`을 업데이트하지만 감사 추적 항목을 생성하지 않는다.
- **허용적인 redirect URI** — Keycloak ConfigMap이 모든 배포 컨텍스트를 지원하기 위해 여러 IP 범위에서의 리다이렉트를 허용하며, 프로덕션에서는 강화가 필요하다.

## 검토한 대안

| 대안 | 미채택 사유 |
|-------------|-------------|
| OAuth2 Proxy (sidecar) | 인프라 복잡성 증가; 각 컴포넌트에 sidecar가 필요하다. Backend proxy 패턴이 PoC에 더 간단하다. |
| Spring Session (stateful) | 서버 측 세션은 상태 관리 부담을 추가하고 스케일링을 복잡하게 한다. Stateless JWT가 더 간단하다. |
| Dex / Auth0 | 외부 서비스는 비용이나 복잡성을 추가한다. Keycloak은 셀프 호스팅이 가능하고 기능이 완전하다. |
| Shared cookie auth | 쿠키는 서로 다른 서비스 도메인/포트에서 작동하지 않는다. JWT Bearer 토큰은 어디서나 작동한다. |

## 향후 개선 사항

- **자격 증명용 Kubernetes Secrets** — Keycloak 관리자 비밀번호와 클라이언트 시크릿을 ConfigMap에서 적절한 K8s Secrets로 이동.
- **역할 기반 접근 제어** — 관리자/뷰어 구분을 위해 JWT 클레임에서 Keycloak realm role 추출.
- **유휴 세션 관리** — 프론트엔드 세션 타임아웃 경고 및 자동 재인증 구현.
- **감사 추적** — 보안 컴플라이언스를 위한 인증 이벤트(로그인, 로그아웃, 실패한 시도) 기록.
- **토큰 갱신 모니터링** — 조용한 갱신만이 아닌, 포털 UI에서 토큰 만료 경고를 표시.
