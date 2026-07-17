# AGENTS.md

TicketRush 백엔드의 **도구 중립 AI 진입점**입니다. Claude Code·GitHub Copilot 등 어떤 도구든 여기서 시작해
필요한 문서로 라우팅합니다. 이 파일은 **내용을 담지 않고 포인터만** 둡니다(중복 0).

## 빌드 · 테스트

```bash
./gradlew spotlessCheck                 # 코드 포맷 검사 (CI가 자동 검증; 로컬 사전 확인용)
./gradlew spotlessApply                 # 코드 포맷 자동 수정 (spotlessCheck 실패 시)
./gradlew checkstyleMain checkstyleTest # Checkstyle 검사 (CI 필수 단계; maxWarnings=0)
./gradlew test                          # 테스트 실행
./gradlew build -x test                 # 빌드 (CI Build 단계와 동일; test는 위에서 분리 실행)
```

> Windows PowerShell에서는 `.\gradlew` 또는 `gradlew.bat`을 사용합니다.
> 특정 모듈만: `./gradlew :common:test` 처럼 `:모듈명:태스크`.

## 문서 라우팅 (SSOT 분담)

각 문서가 **단일 출처(SSOT)** 로 책임지는 영역입니다. 정보를 찾을 땐 해당 문서 한 곳만 봅니다.

| 문서 | SSOT로 책임지는 것 |
|------|------|
| [`CLAUDE.md`](CLAUDE.md) | 아키텍처·운영 정보 + **AI 금지규칙** (Claude Code 진입점) |
| [`docs/backend-convention.md`](docs/backend-convention.md) | 모든 코딩 컨벤션 + 공통 모듈 주요 클래스 + 버전 정보 |
| [`docs/ddd-directory-structure.md`](docs/ddd-directory-structure.md) | 디렉토리 구조 · DDD 계층 |
| [`docs/ai-workflow-guide.md`](docs/ai-workflow-guide.md) | AI 워크플로우(에이전트 / 커맨드 / 사이클 / 훅) |
| [`docs/kafka-event-guide.md`](docs/kafka-event-guide.md) | Kafka 이벤트 설계 · 연동 |
| [`docs/mapstruct-guide.md`](docs/mapstruct-guide.md) | MapStruct 매퍼 사용법 |
| [`docs/adr/`](docs/adr/) | 아키텍처 결정 기록(ADR) — 결정 근거·이력 |
| `AGENTS.md` | 진입점 · 빌드 명령 · 문서 라우팅(이 표) |

> **AI 행동 규칙(금지규칙)** 은 [`CLAUDE.md`](CLAUDE.md)의 "AI 금지규칙(Hard Rules)" 섹션을 따릅니다.
