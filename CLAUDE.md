# TicketRush 팀 컨벤션

> **팀 컨벤션 단일 출처:** Git/이슈/PR, 네이밍·파일명, API, 응답 통일(ApiResponse),
> 주석, `@Transactional`, Swagger 등 **모든 개발 컨벤션은 [`docs/backend-convention.md`](docs/backend-convention.md)
> 를 단일 출처(Single Source of Truth)로 따른다.** 중복 관리를 피하기 위해 이 문서(CLAUDE.md)는
> 컨벤션을 다시 적지 않고, Claude Code 작업에 필요한 **아키텍처·운영 정보만** 보강한다.

## 기술 스택

- Java 21
- Spring Boot 4.0.3 (LTS)

---

## 프로젝트 아키텍처

**MSA (Micro Service Architecture)** - Gradle 멀티 모듈

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

### 서비스 내부 DDD 계층 구조
```
boundedcontext/{도메인}/
├── app/
│   ├── facade/          # UseCase 조합 진입점 (트랜잭션 없음)
│   ├── usecase/         # 비즈니스 로직 + @Transactional
│   ├── dto/
│   │   ├── request/
│   │   └── response/
│   ├── mapper/          # DTO ↔ Entity 변환
│   └── support/         # 공통 유틸 (enum 변환, 파싱 등)
├── domain/
│   ├── entity/          # JPA Entity
│   ├── types/           # Enum
│   └── policy/          # 도메인 정책
├── in/
│   ├── api/v1/          # REST Controller (버전별 분리)
│   │   └── swagger/     # Swagger 어노테이션 분리
│   ├── eventlistener/   # Kafka Consumer
│   └── scheduler/       # 스케줄러
└── out/
    ├── repository/      # JPA Repository
    └── apiclient/       # 외부 API 연동

global/                  # 서비스별 전역 설정 (SecurityConfig 등)
```

### 공통 모듈 주요 클래스
- `ApiResponse` — 응답 래퍼 (`onSuccess(status)`, `onSuccess(status, result)`, `onSuccess(status, Page<T>)`)
- `PageInfo` — 오프셋 페이징 정보 record (`pageIndex, size, hasNext, totalElements, totalPages`)
- `CursorInfo` — 커서 페이징 정보 record (`hasNext, nextCursor, size`)
- `ErrorStatus` — 에러 코드 enum (형식: `모듈_상태코드_세자리번호`)
- `SuccessStatus` — 성공 코드 enum
- `BusinessException` — 비즈니스 예외 (GlobalExceptionHandler가 처리)
- `AutoIdBaseEntity` — auto increment PK 기반 entity 상위 클래스
- `BaseTimeEntity` — createdAt, updatedAt 포함

### 워크플로우
- 이슈/PR 조회: `gh issue view {번호}` 또는 `gh pr view {번호}` 로 직접 가져옴
- 브랜치: `feature/{이슈번호}` 기준으로 작업
- **개발 사이클 자동 진행:** 사용자가 **"이슈 N번 개발 진행하자"**(또는 "N번 이슈 개발 시작", "N번 작업하자" 등 이슈 번호 + 개발 시작 의도)라고 하면, `/dev-cycle N` 커맨드(`.claude/commands/dev-cycle.md`)를 실행해 권장 작업 사이클(조사→계획→승인→구현→검증→커밋→PR)을 순서대로 진행한다. 자세한 흐름은 `docs/ai-workflow-guide.md` 6장 참고.
- **이슈 생성:** 사용자가 **"이슈 만들어줘"**(또는 "이번 작업 이슈로", "이슈 올려줘" 등 이슈 생성 의도)라고 하면 `/issue` 커맨드(`.claude/commands/issue.md`)를 실행한다.
- **PR 생성:** 사용자가 **"PR 올려줘"**(또는 "PR 만들어줘", "풀리퀘 올려" 등 PR 생성 의도)라고 하면 `/pr` 커맨드(`.claude/commands/pr.md`)를 실행한다. 초안을 보여주고 승인받은 뒤에만 Draft PR을 생성한다.

---

## 디렉토리 구조 상세

`docs/ddd.md` 참고
