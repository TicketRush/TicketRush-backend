# TicketRush 팀 컨벤션

> **팀 컨벤션 단일 출처:** Git/이슈/PR, 네이밍·파일명, API, 응답 통일(ApiResponse), 주석,
> `@Transactional`, Swagger 등 **모든 개발 컨벤션은 [`docs/backend-convention.md`](docs/backend-convention.md)
> 를 단일 출처(SSOT)로 따른다.** 중복 관리를 피하기 위해 이 문서(CLAUDE.md)는 컨벤션을 다시 적지
> 않고, Claude Code 작업에 필요한 **아키텍처·운영 정보만** 보강한다.
> 도구 중립 진입점·문서 라우팅은 [`AGENTS.md`](AGENTS.md) 참고.

## 🚫 AI 금지규칙 (Hard Rules)

1. **승인 전 코드 수정 금지** — 플랜/PR 승인 게이트를 통과하기 전에는 프로덕션 코드를 변경하지 않는다. (상세: [`docs/ai-workflow-guide.md`](docs/ai-workflow-guide.md) 6장)
2. **작업 범위 밖 임의 변경 금지** — 이슈·플랜에 없는 리팩토링·파일 변경을 하지 않는다.
3. **추측으로 파일·경로·심볼 단정 금지** — 읽기/검색으로 확인한 뒤에만 단정한다.
4. **컨벤션 SSOT = [`docs/backend-convention.md`](docs/backend-convention.md)** — 컨벤션은 그 문서에서만 관리하고 여기에 재기술하지 않는다.

## 기술 스택

- Java 21
- Spring Boot 4.0.3 (LTS)

---

## 프로젝트 아키텍처

**MSA (Micro Service Architecture)** - Gradle 멀티 모듈. 서비스 내부는 DDD 계층(`app`/`domain`/`in`/`out`/`global`) 구조를 따른다.
디렉토리·DDD 계층 상세는 [`docs/ddd.md`](docs/ddd.md), 공통 모듈 주요 클래스·코딩 컨벤션은 [`docs/backend-convention.md`](docs/backend-convention.md) 참고.

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
- **개발 사이클 자동 진행:** 사용자가 **"이슈 N번 개발 진행하자"**(또는 "N번 이슈 개발 시작", "N번 작업하자" 등 이슈 번호 + 개발 시작 의도)라고 하면, `/dev-cycle N` 커맨드(`.claude/commands/dev-cycle.md`)를 실행해 권장 작업 사이클(조사→계획→승인→구현→검증→커밋→PR)을 순서대로 진행한다. 자세한 흐름은 `docs/ai-workflow-guide.md` 6장 참고.
- **이슈 생성:** 사용자가 **"이슈 만들어줘"**(또는 "이번 작업 이슈로", "이슈 올려줘" 등 이슈 생성 의도)라고 하면 `/issue` 커맨드(`.claude/commands/issue.md`)를 실행한다.
- **PR 생성:** 사용자가 **"PR 올려줘"**(또는 "PR 만들어줘", "풀리퀘 올려" 등 PR 생성 의도)라고 하면 `/pr` 커맨드(`.claude/commands/pr.md`)를 실행한다. 초안을 보여주고 승인받은 뒤에만 Draft PR을 생성한다.
- **PR 리뷰 반영:** 사용자가 **"PR 리뷰 반영해줘"**(또는 "코드 리뷰 반영", "리뷰 코멘트 처리해줘" 등)라고 하면 `/apply-review` 커맨드(`.claude/commands/apply-review.md`)를 실행한다. 코멘트를 분류표로 제시하고, 사람이 건별 수용/거절을 결정하면 수용 건만 수정한다(승인 전 코드 수정·답글 금지).

---

## 디렉토리 구조 상세

`docs/ddd.md` 참고
