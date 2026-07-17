# TicketRush 팀 컨벤션

> 이 문서(CLAUDE.md)는 Claude Code 작업에 필요한 **아키텍처·운영 정보**를 담는 진입점이다.
> 코딩 컨벤션은 여기서 다루지 않는다(→ 아래 AI 금지규칙 4).
> 도구 중립 진입점·문서 라우팅은 [`AGENTS.md`](AGENTS.md) 참고.

## 🚫 AI 금지규칙 (Hard Rules)

1. **승인 전 코드 수정 금지** — 플랜/PR 승인 게이트를 통과하기 전에는 프로덕션 코드를 변경하지 않는다.
2. **작업 범위 밖 임의 변경 금지** — 이슈·플랜에 없는 리팩토링·파일 변경을 하지 않는다.
3. **추측으로 파일·경로·심볼 단정 금지** — 읽기/검색으로 확인한 뒤에만 단정한다.
4. **컨벤션 SSOT = [`docs/backend-convention.md`](docs/backend-convention.md)** — 컨벤션은 그 문서에서만 관리하고 여기에 재기술하지 않는다.

## 기술 스택

Java · Spring Boot 기반. **버전 등 상세는 [`docs/backend-convention.md`](docs/backend-convention.md) §4 "버전 정보"를 SSOT로 따른다**(여기 중복 기재하지 않음).

---

## 프로젝트 아키텍처

**MSA (Micro Service Architecture)** - Gradle 멀티 모듈. 서비스 내부는 DDD 계층(`app`/`domain`/`in`/`out`/`global`) 구조를 따른다.
디렉토리·DDD 계층 상세는 [`docs/ddd-directory-structure.md`](docs/ddd-directory-structure.md), 공통 모듈 주요 클래스는 [`docs/backend-convention.md`](docs/backend-convention.md) 참고.
아키텍처 결정 근거·이력은 [`docs/adr/`](docs/adr/)에 ADR(아키텍처 결정 기록)로 남긴다.

### 모듈 목록
| 모듈 | 역할 |
|------|------|
| `common` | 전 모듈 공통 코드 (ApiResponse, ErrorStatus, PageInfo, Kafka, Redis 등) |
| `gateway-service` | API Gateway |
| `auth-service` | 인증/인가 (OAuth2, JWT) |
| `user-service` | 회원 도메인 |
| `performance-service` | 공연 도메인 |
| `seat-service` | 좌석 도메인 |
| `booking-service` | 예매 도메인 |
| `payment-service` | 결제 도메인 |
| `ticket-service` | 티켓 도메인 |

---

## 워크플로우

- 이슈/PR 조회: `gh issue view {번호}` 또는 `gh pr view {번호}` 로 직접 가져옴
- 브랜치: **`{이슈 label}/{이슈번호}`** 기준으로 작업 (예: label `refactor` → `refactor/250`). 상세는 [`docs/backend-convention.md`](docs/backend-convention.md) 브랜치 규칙.
- **이슈 생성·PR 생성·PR 리뷰 반영**은 스킬(`.claude/skills/{issue,pr,apply-review}/`)로, "이슈 만들어줘"·"PR 올려줘"·"PR 리뷰 반영해줘" 같은 자연어 요청에 `description` 기반으로 자동 호출된다(별도 라우팅 규칙 불필요). 각 스킬의 "절대 규칙"이 행위 SSOT이며, 승인 게이트는 위 하드룰 1이 강제한다. 상세는 [`docs/ai-workflow-guide.md`](docs/ai-workflow-guide.md) 4장 참고.

---

## 디렉토리 구조 상세

`docs/ddd-directory-structure.md` 참고
