# 실시간 공연 티켓팅 플랫폼 - TicketRush 🎫

## 🔥 프로젝트 소개

### 🗓️ 프로젝트 기간

- 2025년 12월 ~ 진행중

### 👥 프로젝트 팀원 소개

|                            프로필                            | 이름  |                     GitHub                     | 담당 파트                                      |
|:---------------------------------------------------------:|:---:|:----------------------------------------------:|--------------------------------------------|
|    <img src="https://github.com/50h33.png" width="80">    | 김소희 |       [@50h33](https://github.com/50h33)       | 좌석(seat) · 예매(booking) · 티켓(ticket) · 모니터링 |
|  <img src="https://github.com/calla1102.png" width="80">  | 민주  |   [@calla1102](https://github.com/calla1102)   | 공연(performance) · 결제(payment) · CI         |
| <img src="https://github.com/kimhyerim01.png" width="80"> | 김혜림 | [@kimhyerim01](https://github.com/kimhyerim01) | 인증(auth) · 회원(user) · 게이트웨이(gateway) · 배포  |

---

## 🎫 TicketRush 서비스 소개

### 💡 TicketRush 서비스 - 개발 배경

인기 공연의 티켓 오픈은 짧은 시간에 트래픽이 폭증하고, 한정된 좌석에 다수의 사용자가 동시에 몰리는 대표적인 **고(高)동시성** 문제 영역입니다. 이 환경에서 발생하는 핵심
난제는 다음과 같습니다.

- **더블 부킹** — 동일 좌석을 여러 사용자가 동시에 선택할 때 중복 예매가 발생
- **실시간성** — 다른 사용자가 선택 중인 좌석을 즉시 반영하지 못하면 예매 실패·불편으로 이어짐
- **분산 트랜잭션 정합성** — 예매 → 결제 → 발권으로 이어지는 흐름에서 "결제는 됐는데 좌석·티켓이 누락"되는 부분 실패를 막아야 함

**TicketRush**는 이 문제들을 다음과 같이 풀어냅니다.

- **Redis 기반 좌석 선점 락 + 멱등 처리**로 더블 부킹을 차단
- **SSE(Server-Sent Events)** 로 좌석 상태를 실시간 스트리밍
- **Kafka 이벤트(Outbox 패턴) + Saga 보상 트랜잭션**으로 서비스 간 최종 일관성을 보장하고, 중간 단계가 실패하면 앞선 작업을 자동으로 보상(취소)

### 📖 TicketRush 서비스 - 개요

**TicketRush**는 공연 티켓 예매를 제공하는 서비스의 백엔드입니다.
좌석 선점 → 예매 → 결제 → 티켓 발급으로 이어지는 예매 흐름을 회원/인증·공연·좌석·예매·결제·티켓 도메인으로 나누고, 각 도메인을 독립적으로 배포·확장 가능한 **MSA**
구조로 설계했습니다.
서비스 간 통신은 동기 호출을 최소화하고 **Kafka 이벤트**를 중심으로 느슨하게 결합하여, 특정 서비스의 장애가 전체로 전파되지 않도록 했습니다.

### ✨ TicketRush 서비스 - 주요 기능

**회원 · 인증**

- 이메일/비밀번호 회원가입·로그인, 이메일 인증번호 발송·검증
- OAuth2 소셜 로그인 (카카오 · 네이버 · 구글)
- JWT 발급·재발급(access/refresh) 및 로그아웃

**공연**

- 공연 목록 조회(장르·가격·상태 필터 + 페이징) 및 상세 조회
- 공연 등록·수정·삭제 및 상태 관리 (관리자, 메인 이미지·3D 모델 업로드)

**좌석**

- 좌석 배치 및 상태별 수(가능/판매완료/선점) 조회
- SSE 기반 좌석 상태 실시간 스트리밍
- 좌석 선점(락)/해제 — Redis 기반 멱등 처리

**예매**

- 예매 생성(결제 대기) · 취소 · 만료 처리 및 내 예매 내역 조회
- 좌석 선점 실패 시 예매 자동 취소 (Saga 보상 트랜잭션)

**결제**

- Toss Payments 연동 결제 승인 · 취소 · 환불
- PG Webhook 수신 (서명 검증 · 멱등 처리) 및 결제 내역 조회

**티켓 · 입장**

- 결제 완료 이벤트 기반 티켓 자동 발급 (QR 토큰)
- 입장권 QR 조회 및 검증(서명·만료), 입장(검표) 처리 및 중복 입장 방지

## 🛠 TicketRush 기술 스택

### 1️⃣ 개발 환경 및 사용 기술

| 구분           | 사용 기술                                                        |
|--------------|--------------------------------------------------------------|
| Language     | Java 21                                                      |
| Framework    | Spring Boot 4.0.3, Spring Cloud 2025.1.0 (Gateway / WebFlux) |
| Build        | Gradle (멀티모듈)                                                |
| Database     | MySQL · Spring Data JPA · QueryDSL                           |
| Messaging    | Apache Kafka (KRaft, Outbox 패턴)                              |
| Cache / Lock | Redis · Redisson · ShedLock                                  |
| Auth         | Spring Security · JWT · OAuth2                               |
| API Docs     | springdoc-openapi (Swagger)                                  |
| Code Quality | Spotless (google-java-format) · Checkstyle                   |

### 2️⃣ 모듈 구성

| 모듈                    | 역할                                                              |
|-----------------------|-----------------------------------------------------------------|
| `gateway-service`     | API Gateway (Spring Cloud Gateway / WebFlux), Swagger 통합 진입점    |
| `user-service`        | 회원 도메인                                                          |
| `auth-service`        | 인증/인가 (OAuth2 카카오·구글·네이버, JWT)                                  |
| `performance-service` | 공연 도메인                                                          |
| `booking-service`     | 예매 도메인 (DLT 모니터링·Slack 알림)                                      |
| `payment-service`     | 결제 도메인 (Toss Payments)                                          |
| `seat-service`        | 좌석 도메인 (SSE 실시간 스트림, 분산 락)                                      |
| `ticket-service`      | 티켓 도메인 (QR 토큰)                                                  |
| `common`              | 전 모듈 공통 코드 (ApiResponse, ErrorStatus, PageInfo, Kafka, Redis 등) |

<!--
### 3️⃣ 아키텍처 다이어그램

### 4️⃣ CI/CD 파이프라인
-->

## 📚 Deep Dive Docs

| 문서                                                            | 내용                          |
|---------------------------------------------------------------|-----------------------------|
| [AGENTS.md](AGENTS.md)                                        | 도구 중립 AI 진입점 / 문서 라우팅       |
| [CLAUDE.md](CLAUDE.md)                                        | 아키텍처 개요 + AI 작업 규칙          |
| [backend-convention.md](docs/backend-convention.md)           | 코딩·협업 컨벤션, 버전 정보 (SSOT)     |
| [ddd-directory-structure.md](docs/ddd-directory-structure.md) | DDD 디렉토리 구조                 |
| [kafka-event-guide.md](docs/kafka-event-guide.md)             | Kafka 이벤트 / Outbox / DLT 정책 |
| [mapstruct-guide.md](docs/mapstruct-guide.md)                 | MapStruct Mapper 구조         |
| [ai-workflow-guide.md](docs/ai-workflow-guide.md)             | Claude Code AI 개발 워크플로우     |
