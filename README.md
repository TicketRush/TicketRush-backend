# TicketRush - 대규모 트래픽을 제어하는 MSA 기반 공연 티켓 예매 플랫폼

<!-- 배지에 ?branch= 를 붙이지 마세요. 두 워크플로 모두 pull_request 트리거뿐이라 브랜치 지정 시 "no status" 가 됩니다. -->
[![CI](https://github.com/TicketRush/TicketRush-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/TicketRush/TicketRush-backend/actions/workflows/ci.yml)
[![Schema Validate](https://github.com/TicketRush/TicketRush-backend/actions/workflows/schema-validate.yml/badge.svg)](https://github.com/TicketRush/TicketRush-backend/actions/workflows/schema-validate.yml)

## 🔥 프로젝트 개요

**TicketRush**는 공연 티켓 예매 서비스입니다.
좌석 선점 → 예매 → 결제 → 티켓 발급 흐름을 지원하며, 회원/인증·공연·좌석·예매·결제·티켓 도메인으로 구성됩니다.
각 도메인을 독립적으로 배포·확장할 수 있는 **마이크로서비스 아키텍처(MSA)** 로 설계했습니다.
서비스 간에는 **Kafka 이벤트**를 중심으로 통신합니다. 서비스 간 의존성과 장애 전파를 줄이기 위해 동기 호출을 최소화했습니다.

### 🗓️ 프로젝트 기간

- 2026년 3월 ~ 9월

### 🎬 서비스 시연 영상

[▶️ YouTube에서 서비스 시연 영상 보기](https://youtu.be/YwyYqNGjpws)

---

## 💡 개발 배경과 목표

인기 공연의 예매가 시작되면 짧은 시간에 요청이 집중되고, 여러 사용자가 같은 좌석을 동시에 선택합니다.
이때 다음 문제를 처리해야 합니다.

- **중복 예매(더블 부킹)** — 동일 좌석을 여러 사용자가 동시에 선택할 때 중복 예매가 발생
- **실시간성** — 다른 사용자가 선택 중인 좌석의 상태를 즉시 반영하지 못하면 예매 실패·불편으로 이어짐
- **분산 트랜잭션 정합성** — 예매 → 결제 → 티켓 발급 중 일부 단계만 성공해 결제 후 좌석·티켓이 누락되는 상황에 대응해야 함

TicketRush는 **인스턴스 수나 사양을 늘리지 않는 조건**에서 개발했습니다.
단일 EC2(`m7i-flex.large`, 2 vCPU / 7.6 GiB)에서 애플리케이션 8개(게이트웨이 + 7개 도메인 서비스)와 MySQL·Redis·Kafka·관측 스택을 함께 실행합니다.
이 환경에서 애플리케이션과 아키텍처를 개선해 처리 성능을 높이고, 요청이 집중되어도 **데이터 정합성을 유지하고 서버 중단을 방지하는 것**이 목표입니다.

이를 위해 다음 방식을 적용했습니다.

- **Redis 기반 좌석 선점 락 + 멱등 처리**로 중복 예매를 방지
- **SSE(Server-Sent Events)** 로 좌석 상태를 실시간 스트리밍
- **Kafka 이벤트(Outbox 패턴) + Saga 보상 트랜잭션**으로 서비스 간 상태를 최종적으로 일치시키고, 중간 단계가 실패하면 앞선 작업을 자동으로 보상(취소)
- **대기열 기반 유입 제어**로 서버의 처리 한도를 초과하는 요청을 대기시켜 유입량을 조절

부하 테스트로 **주요 성능 지표를 측정하고 한계를 확인했습니다**.

---

## ✨ 주요 기능

**(1) 회원 · 인증**

- 이메일/비밀번호 회원가입·로그인, 이메일 인증번호 발송·검증
- OAuth2 소셜 로그인 (카카오 · 네이버 · 구글)
- JWT 발급·재발급(access/refresh) 및 로그아웃

**(2) 공연**

- 공연 목록 조회(장르·가격·상태 필터 + 페이징) 및 상세 조회
- 공연 등록·수정·삭제, 예매 오픈 시각에 따른 판매 시작 및 공연 시작 후 자동 판매 마감
- 메인 화면 배너 조회, 공연 등록·수정·삭제 시 배너 정보 함께 반영
- 메인 이미지·갤러리·3D 모델 업로드 및 부분 교체, 유지할 갤러리 이미지 선택·3D 모델 제거
- 3D 캐릭터 구성과 한마디 저장·수정

**(3) 좌석**

- 좌석별 행·열 좌표와 배치 크기를 포함한 좌석맵 및 상태별 좌석 수(가능/판매완료/선점) 조회
- SSE 기반 좌석 상태 실시간 스트리밍
- 좌석 선점(락)/해제 — Redis 기반 멱등 처리

**(4) 예매**

- 예매 생성(결제 대기) · 취소 · 만료 처리 및 내 예매 내역 조회
- 서버에서 공연 판매 상태를 검증해 오픈 전 예매 차단
- 사용자 환불은 공연 시작 7일 전까지 허용하며, 예매 환불·결제 취소 API 양쪽에서 마감 여부 확인
- 좌석 선점 실패 시 예매 자동 취소 (Saga 보상 트랜잭션)

**(5) 결제**

- Toss Payments 연동 결제 승인 · 취소 · 환불
- PG Webhook 수신 (paymentKey로 PG 결제 재조회 검증 · 멱등 처리) 및 결제 내역 조회
- 환불 실패 결과 이벤트를 다시 발행해 예매 상태 복구
- 결제됐지만 예매가 만료된 건을 찾아 자동 환불 (기본 비활성, 별도 활성화 필요)

**(6) 티켓**

- 결제 완료 이벤트 기반 티켓 자동 발급 (QR 토큰)
- 입장권 QR 조회 및 검증(서명·만료), 입장(검표) 처리 및 중복 입장 방지

**(7) 관리자 운영**

- 전체 예매 목록 조회(여러 상태로 필터링·페이징) 및 예매 건수·매출 요약 통계
- 공연별 좌석 상태와 선택한 좌석의 예매 정보 조회
- 환불 통합 목록과 진행 중·완료·미해결 실패 상태별 통계 조회
- 관리자 환불 및 실패한 환불 재시도, 처리가 오래 멈춘 환불 조회·복구

**(8) 유입 제어 · 장애 대응**

- 공연별 대기열 순번·대기 상태 조회, 서버가 안내하는 주기로 폴링하고 입장 토큰으로 진입 제어
- 사용자 또는 IP 기준 API 요청 횟수 제한(Rate Limit), 초과 요청에 HTTP 429 응답
- 결제·티켓 서비스의 예매 조회에 서킷브레이커 적용 — 조회 불가 시 결제·입장 처리 차단

---

## 🖥️ 서비스 이용 흐름

단계별 설명과 화면·시연 GIF를 함께 확인할 수 있습니다.

### 사용자 흐름

공연 탐색 → 좌석 선택 → 예매 및 결제 → QR 티켓 확인 → 예매 확인 및 환불

#### 1. 공연 탐색

공연 목록에서 원하는 공연을 찾고, 상세 정보와 갤러리를 확인합니다.

**공연 목록**

<img src="docs/images/사용자/01_공연_탐색/홈화면.png" width="800" alt="사용자 홈 화면의 공연 목록">

| 공연 상세 정보 | 공연 소개 및 갤러리 |
| :---: | :---: |
| <img src="docs/images/사용자/01_공연_탐색/공연화면1.png" width="400" alt="공연 일시, 장소, 가격 및 예매 현황"> | <img src="docs/images/사용자/01_공연_탐색/공연화면2.png" width="400" alt="공연 소개, 이미지 갤러리 및 3D 캐릭터"> |

#### 2. 좌석 선택

좌석 배치도에서 상태를 확인하고 예매할 좌석을 선택합니다.

<img src="docs/images/사용자/02_좌석_선택/좌석선택.png" width="800" alt="상태별 좌석 배치도와 좌석 선택 화면">

#### 3. 예매 및 결제

선택한 좌석과 예매 정보를 확인한 뒤, 제한 시간 내 결제를 진행합니다.

| 예매 정보 확인 | 결제 수단 선택 |
| :---: | :---: |
| <img src="docs/images/사용자/03_예매_결제/예매중.png" width="400" alt="선점한 좌석, 결제 금액 및 남은 결제 시간"> | <img src="docs/images/사용자/03_예매_결제/결제수단선택.png" width="400" alt="결제 수단 선택과 결제 금액 확인 화면"> |

#### 4. QR 티켓 확인

결제 완료 후 발급된 티켓과 공연장 입장용 QR 코드를 확인합니다.

| 결제 완료 및 티켓 발급 | 입장 QR 코드 |
| :---: | :---: |
| <img src="docs/images/사용자/04_QR_티켓/티켓화면1.png" width="400" alt="결제 완료 후 발급된 공연 티켓"> | <img src="docs/images/사용자/04_QR_티켓/티켓화면2.png" width="400" alt="입장 QR 코드와 유효 시간 확인 화면"> |

#### 5. 예매 확인 및 환불

내 예매 내역에서 티켓을 확인하고 환불을 신청하거나 처리 상태를 확인합니다.

| 내 예매 내역 | 환불 완료 내역 |
| :---: | :---: |
| <img src="docs/images/사용자/05_예매확인_환불/예매확인.png" width="400" alt="내 예매 내역의 티켓 보기 및 환불 신청 화면"> | <img src="docs/images/사용자/05_예매확인_환불/환불신청완료.png" width="400" alt="환불 완료 상태가 표시된 예매 내역"> |


### 관리자 흐름

관리자 대시보드 · 예매 내역 · 좌석 모니터링 · 공연 등록 · 환불 내역 관리

#### 1. 관리자 대시보드

주요 운영 지표와 매출, 공연별 판매 현황을 한곳에서 확인합니다.

| 운영 지표 및 기간별 매출 | 장르별 매출 분포 |
| :---: | :---: |
| <img src="docs/images/관리자/01_관리자_대시보드/관리자_대시보드_1.png" width="400" alt="관리자 대시보드의 운영 지표와 기간별 매출 추이"> | <img src="docs/images/관리자/01_관리자_대시보드/관리자_대시보드_2.png" width="400" alt="장르별 매출 비율과 금액"> |

| 공연별 판매 현황 | 전체 공연 목록 |
| :---: | :---: |
| <img src="docs/images/관리자/01_관리자_대시보드/관리자_대시보드_3.png" width="400" alt="공연별 판매 좌석 수와 매출 현황"> | <img src="docs/images/관리자/01_관리자_대시보드/관리자_대시보드_4.png" width="400" alt="관리자 대시보드의 전체 공연 목록과 관리 메뉴"> |

#### 2. 예매 내역

전체 예매 현황을 확인하고 상태별로 예매 내역을 조회합니다.

<img src="docs/images/관리자/02_예매_내역/예매_내역_관리.png" width="800" alt="전체 예매 지표와 상태별 예매 내역 관리 화면">

#### 3. 좌석 모니터링

공연별 좌석 현황을 조회하고, 선택한 좌석의 예매 정보를 확인합니다.

| 모니터링할 공연 선택 | 좌석 상태 및 예매 정보 |
| :---: | :---: |
| <img src="docs/images/관리자/03_좌석_모니터링/좌석_모니터링_1.png" width="400" alt="좌석 모니터링 대상 공연 목록"> | <img src="docs/images/관리자/03_좌석_모니터링/좌석_모니터링_2.png" width="400" alt="공연별 좌석 배치도, 상태별 집계 및 선택한 좌석의 예매 정보"> |

#### 4. 공연 등록

공연 정보를 입력하고, 공연에 사용할 3D 캐릭터를 제작합니다.

| 공연 등록 | 3D 캐릭터 제작 |
| :---: | :---: |
| <img src="docs/images/관리자/04_공연_등록/공연등록.gif" width="400" alt="공연 정보 입력 및 등록 과정 시연 GIF"> | <img src="docs/images/관리자/04_공연_등록/3D캐릭터제작.gif" width="400" alt="3D 캐릭터 외형 설정과 미리보기 시연 GIF"> |

#### 5. 환불 내역 관리

환불 진행 상태별로 내역을 조회하고 처리 현황을 확인합니다.

<img src="docs/images/관리자/05_환불내역관리/환불내역관리.png" width="800" alt="환불 상태별 집계와 환불 내역 관리 화면">

---

## 🛠 기술 스택과 아키텍처

### 1️⃣ 개발 환경 및 사용 기술

| 구분           | 사용 기술                                                        |
|--------------|--------------------------------------------------------------|
| Language     | Java 21                                                      |
| Framework    | Spring Boot 4.0.3, Spring Cloud 2025.1.0 (Gateway / WebFlux) |
| Build        | Gradle (멀티모듈)                                                |
| Database     | MySQL · Spring Data JPA · QueryDSL                           |
| Messaging    | Apache Kafka (KRaft, Outbox 패턴)                              |
| Cache / Lock | Redis · Redisson · ShedLock                                  |
| Resilience   | Resilience4j (서킷브레이커) · RedisRateLimiter                  |
| Storage      | Amazon S3 · LocalStack (로컬 S3 대체)                          |
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

대기열과 API 요청 횟수 제한은 `gateway-service`에서 처리합니다.
메인 화면 배너는 `performance-service`의 별도 도메인으로 관리합니다.

### 3️⃣ 아키텍처 다이어그램

TicketRush는 API Gateway를 단일 진입점으로 두고, 인증·회원·공연·예매·결제·좌석·티켓 도메인을 독립 서비스로 분리한 **MSA 구조**입니다.

운영 환경에서는 Nginx가 외부 HTTPS 요청을 받아 `gateway-service`로 전달하고, Gateway가 요청 경로에 따라 각 도메인 서비스로 라우팅합니다.
서비스 간 상태 변경은 Kafka 이벤트를 중심으로 처리하며, 좌석 선점·대기열처럼 빠른 상태 접근과 동시성 제어가 필요한 영역에는 Redis를 사용합니다.

#### 인프라 구성

<img src="docs/images/architecture.png" width="800" alt="TicketRush AWS 인프라 아키텍처 — EC2 호스트의 Nginx가 HTTPS를 받아 Docker Compose로 실행되는 Gateway·7개 도메인 서비스와 MySQL·Redis·Kafka·관측 스택으로 전달">

#### 서비스 간 흐름

```mermaid
flowchart TB
    CLIENT["Web Client"]

    subgraph EC2["AWS EC2 · Production"]
        NGINX["Nginx<br/>HTTPS :443"]
        GATEWAY["gateway-service<br/>API Gateway :8080"]

        subgraph SERVICES["Domain Services"]
            USER["user-service<br/>:8081"]
            AUTH["auth-service<br/>:8082"]
            PERFORMANCE["performance-service<br/>:8083"]
            BOOKING["booking-service<br/>:8084"]
            PAYMENT["payment-service<br/>:8085"]
            SEAT["seat-service<br/>:8086"]
            TICKET["ticket-service<br/>:8087"]
        end

        MYSQL[("MySQL 8.0")]
        REDIS[("Redis 7")]
        KAFKA[["Apache Kafka 3.7<br/>KRaft"]]

        PROMETHEUS["Prometheus"]
        GRAFANA["Grafana"]
    end

    OAUTH["Kakao · Google · Naver"]
    TOSS["Toss Payments"]
    S3["Amazon S3"]

    CLIENT -->|"HTTPS"| NGINX
    NGINX --> GATEWAY

    GATEWAY --> USER
    GATEWAY --> AUTH
    GATEWAY --> PERFORMANCE
    GATEWAY --> BOOKING
    GATEWAY --> PAYMENT
    GATEWAY --> SEAT
    GATEWAY --> TICKET

    USER --> MYSQL
    AUTH --> MYSQL
    PERFORMANCE --> MYSQL
    BOOKING --> MYSQL
    PAYMENT --> MYSQL
    SEAT --> MYSQL
    TICKET --> MYSQL

    GATEWAY -->|"대기열"| REDIS
    SEAT -->|"좌석 선점 · 분산 락"| REDIS

    PERFORMANCE -->|"PerformanceCreated"| KAFKA
    BOOKING -->|"BookingCreated · BookingExpired"| KAFKA
    PAYMENT -->|"PaymentConfirmed · PaymentCanceled"| KAFKA
    SEAT -->|"SeatHoldFailed"| KAFKA

    KAFKA --> SEAT
    KAFKA --> BOOKING
    KAFKA --> PAYMENT
    KAFKA --> TICKET

    AUTH -->|"OAuth2"| OAUTH
    PAYMENT -->|"결제 승인 · 취소"| TOSS
    PERFORMANCE -->|"이미지 · 3D 모델"| S3

    PROMETHEUS -. "metrics scrape" .-> GATEWAY
    PROMETHEUS -. "metrics scrape" .-> USER
    PROMETHEUS -. "metrics scrape" .-> AUTH
    PROMETHEUS -. "metrics scrape" .-> PERFORMANCE
    PROMETHEUS -. "metrics scrape" .-> BOOKING
    PROMETHEUS -. "metrics scrape" .-> PAYMENT
    PROMETHEUS -. "metrics scrape" .-> SEAT
    PROMETHEUS -. "metrics scrape" .-> TICKET

    GRAFANA --> PROMETHEUS
```

**주요 흐름**

* **외부 요청** — Client → Nginx → Gateway → 각 도메인 서비스
* **예매 처리** — 좌석 선점 → 예매 생성 → 결제 → 예매 확정·티켓 발급을 Kafka 이벤트로 연결
* **동시성 제어** — Redis를 이용해 좌석 선점과 대기열 상태를 관리
* **이벤트 정합성** — Kafka 기반 비동기 이벤트와 Outbox/Inbox 패턴으로 이벤트 유실·중복 처리에 대응
* **관측** — Prometheus가 애플리케이션·인프라 지표를 수집하고 Grafana에서 시각화

> 현재 운영 환경은 **단일 EC2**이며, Docker Compose로 애플리케이션 8개와 MySQL·Redis·Kafka·Prometheus·Grafana 등의 인프라를 함께 실행합니다.
> DB·Redis·Kafka의 접속 정보는 환경변수로 관리하며, 추후 RDS·ElastiCache·MSK 등 관리형 인프라로 이전할 수 있도록 구성했습니다.

---

## 📈 성능 / 부하 테스트

**24개 회차의 부하 테스트**를 진행했습니다. 인스턴스 타입이 기록된 15개 회차는 단일 EC2(`m7i-flex.large`, 2 vCPU / 7.6 GiB)에서 애플리케이션 8개와 MySQL·Redis·Kafka·관측 스택을 함께 실행하며 측정했습니다.
이전 9개 회차는 인스턴스 타입이 기록되지 않아 동일 구성으로 단정하지 않습니다.

| 무엇을 | 전 → 후 | 회차 |
|-----|-----|-----|
| 좌석맵 응답 크기 (gzip) | 174,615 B → 8,405 B | #505 |
| 좌석 집계 포화 구간 관측 처리량 (커버링 인덱스) | 254.84 → 396.75 rps | #529 |
| 좌석맵 서버 응답, 80 계단 (JSON 캐싱) | 741.04 ms → 10.11 ms | #539 |
| 예매 파이프라인 드레인율 (컨슈머 concurrency 3) | 43.0/s → 96.6/s | #598 |

표의 '계단'은 초당 요청 도착률로 구분한 부하 구간이며, '드레인율'은 쌓인 이벤트의 초당 소비 건수입니다.
좌석 집계의 396.75 rps는 부하 생성기의 가상 사용자(VU) 수 상한 내에서 관측한 값입니다. 실제 포화점은 이보다 높을 수 있습니다.

표의 각 행은 **같은 회차에서 측정한 변경 전후 값**을 비교합니다. 서로 다른 회차의 측정값은 비교하지 않았습니다.
회차별 통제 조건과 결과에 영향을 준 다른 요인은 리포트의 `대조 범위` 열에 정리했습니다.
회선 → 컨테이너 메모리 → 호스트 CPU → 유입 제어 순으로 병목을 확인하고 대응한 과정은
[performance-report.md](docs/performance-report.md)에 정리했습니다. 1만 명 동시 대기를 재현하지 못한 점과 수평 확장을 검증하지 않은 점도 함께 명시했습니다.

실측치를 바탕으로 계산한 **예상 트래픽·인프라 설계 기준**(일평균 사용자 수·피크 TPS·서버 사양)은
[capacity-planning.md](docs/capacity-planning.md)에서 확인할 수 있습니다.

---

## 🚀 로컬 실행과 API 문서

로컬에서 서비스를 실행하는 절차입니다.

### 1️⃣ 사전 준비물

| 항목 | 버전 | 비고 |
|-----|-----|-----|
| JDK | 21 | 각 모듈 `build.gradle`의 Gradle toolchain 설정 |
| Docker | - | Redis · Kafka · 관측 스택 구동용 |
| MySQL | 8.x | **호스트에 직접 설치**. `docker-compose.yml`에는 MySQL이 없습니다 |

### 2️⃣ 데이터베이스 준비

각 서비스는 `jdbc:mysql://localhost:3306/ticket_rush`로 접속합니다. 스키마를 미리 생성해 두면
서비스 기동 시 `ddl-auto=update` 설정에 따라 테이블이 자동으로 생성됩니다.

```sql
CREATE DATABASE ticket_rush CHARACTER SET utf8mb4;
```

> 운영과 동일한 스키마(generated 컬럼·인덱스 포함)로 시작하려면
> [`deploy/mysql/init/001-ticket-rush-schema.sql`](deploy/mysql/init/001-ticket-rush-schema.sql)을 적용하세요.
> 데이터 모델은 [ERD 문서](docs/erd.md)를 참고하세요.

### 3️⃣ 환경변수 설정

```bash
cp .env.local.example .env.local
```

`.env.local`을 열어 `[필수]`로 표시된 값을 채웁니다. 서비스별 필수 환경변수는
[`.env.local.example`](.env.local.example)의 주석에서 확인할 수 있습니다.

Spring Boot는 `.env.local`을 자동으로 읽지 않습니다. 실행할 때 셸 환경변수나 IntelliJ EnvFile로 주입해야 합니다.

- **`gateway-service`만 실행할 때** — `JWT_SECRET`만 있으면 됩니다 (DB를 사용하지 않습니다)
- **`auth-service`를 실행할 때** — OAuth 3종 · `MAIL_*` · `INTERNAL_API_TOKEN` · `GATEWAY_INTERNAL_TOKEN`이
  모두 필수이며 기본값이 없습니다. 하나라도 빠지면 기동에 실패합니다
- `SPRING_PROFILES_ACTIVE`는 `local` 단독으로 둡니다
- **소셜 로그인 콜백**은 프론트의 `http://localhost:5173/oauth/callback/{provider}`로 설정합니다.
  `{provider}`는 `kakao`, `google`, `naver`이며, 각 공급자 콘솔에도 같은 Redirect URI를 등록해야 합니다.
- **실제 PG 호출 없이 로컬 결제를 테스트할 때**는 `PAYMENT_PG_STUB_ENABLED=true`, `TOSS_PAYMENTS_ENABLED=false`로 설정합니다.

### 4️⃣ 인프라 기동

```bash
# 최소 구성 (Redis + Kafka + LocalStack)
docker compose up -d redis kafka localstack

# 관측 스택까지 포함 (Prometheus + Grafana)
docker compose up -d
```

| 컨테이너 | 포트 | 비고 |
|--------|-----|-----|
| Redis | `127.0.0.1:6379` | 좌석 선점 락 · 대기열. keyspace 만료 이벤트(`Ex`) 활성화 |
| Kafka | `127.0.0.1:29092` | KRaft 모드. 기본 파티션 3 |
| LocalStack | `127.0.0.1:4566` | 공연 등록 파일 업로드용 S3 대체(#636). 기동 시 버킷 생성·퍼블릭 정책 자동 적용 |
| Prometheus | `127.0.0.1:9090` | |
| Grafana | `127.0.0.1:3000` | 기본 계정 `admin` / `admin` |

> **LocalStack을 실행하지 않으면 파일 업로드가 필요한 공연 등록·파일 교체 요청이 실패합니다.** 파일 업로드를 비활성화하는 설정이 없기 때문입니다.
> 목록·상세 조회와 서비스 기동은 정상 동작하므로, 이 결과만으로 파일 업로드 환경을 확인할 수는 없습니다.

부하 테스트용 `k6`는 `loadtest` 프로파일로 분리되어 있어 위 명령으로는 실행되지 않습니다
([load-test-guide.md](docs/load-test-guide.md) 참고).

### 5️⃣ 애플리케이션 기동

필요한 서비스를 모듈별로 실행합니다. 각 서비스는 별도 터미널에서 실행하세요.

```bash
# .env.local 을 환경변수로 주입한 뒤 실행
set -a && . ./.env.local && set +a
./gradlew :gateway-service:bootRun
./gradlew :performance-service:bootRun
```

> IntelliJ에서 실행한다면 [EnvFile 플러그인](https://plugins.jetbrains.com/plugin/7861-envfile)으로
> `.env.local`을 주입하도록 실행 구성을 만드세요. `.idea/`는 Git 추적 대상에서 제외되어 있으므로 실행 구성은 직접 만들어야 합니다.

| 서비스 | 포트 | DB |
|------|-----|-----|
| `gateway-service` | 8080 | 미사용 (WebFlux + Redis) |
| `user-service` | 8081 | ✅ |
| `auth-service` | 8082 | ✅ |
| `performance-service` | 8083 | ✅ |
| `booking-service` | 8084 | ✅ |
| `payment-service` | 8085 | ✅ |
| `seat-service` | 8086 | ✅ |
| `ticket-service` | 8087 | ✅ |

API 요청은 게이트웨이의 **8080** 포트로 보냅니다. 나머지 서비스 포트는 게이트웨이가 내부 라우팅에 사용합니다.
서비스를 직접 호출하면 게이트웨이가 주입하는 인증 헤더가 없어 동작이 달라집니다.

운영 환경에서는 **Nginx의 HTTPS 443 포트만 외부 요청의 진입점으로 사용**하며, `gateway-service`의 8080과
Actuator 관리 포트 8090은 EC2의 `127.0.0.1`에만 바인딩됩니다. 나머지 7개 도메인 서비스는 포트를 외부에 공개하지 않고
Docker Compose 내부 네트워크에서만 통신합니다.


`local` 프로파일에서는 공연·배너(`performance-service`)와 좌석(`seat-service`)의 더미 데이터를 자동으로 생성합니다.
데이터가 이미 있으면 건너뜁니다.

### 6️⃣ 동작 확인

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 7️⃣ API 문서 (Swagger)

게이트웨이가 7개 서비스의 OpenAPI 문서를 **하나의 Swagger UI로 통합**합니다.
우측 상단 드롭다운에서 서비스를 전환합니다.

| 환경 | 주소 |
|-----|-----|
| 로컬 | http://localhost:8080/swagger-ui.html |
| 운영 | https://api.ticketrush.store/swagger-ui.html |

- `/swagger-ui.html`은 `/swagger-ui/index.html`로 리다이렉트(302)됩니다
- 인증 없이 접근할 수 있습니다 (`/swagger-ui/**`, `/v3/api-docs/**`가 게이트웨이 허용 목록에 등록되어 있습니다)
- 서비스별 원본 명세는 `/v3/api-docs/{user|auth|performance|booking|payment|seat|ticket}`에서 직접 받을 수 있습니다
- 게이트웨이가 각 서비스에서 문서를 가져오므로, 실행 중이 아닌 서비스의 문서는 불러올 수 없습니다. 나머지 서비스의 문서는 정상적으로 표시됩니다

<img src="docs/images/swagger-ui.jpg" width="700" alt="게이트웨이 통합 Swagger UI — performance-service 선택 화면">

---

## ⚙️ CI/CD와 운영 환경

### CI (지속적 통합)

PR에서는 다음 항목을 검사합니다. 머지 조건과 브랜치 보호 설정은 [백엔드 컨벤션](docs/backend-convention.md)의 "검증 파이프라인"을 참고하세요.

| 워크플로 | 대상 | 검사 항목 |
| --- | --- | --- |
| [CI](.github/workflows/ci.yml) | PR → `develop` | 포맷·정적 검사, 테스트·빌드, Prometheus 수집 대상과 배포 설정 대조 |
| [Schema Validate](.github/workflows/schema-validate.yml) | PR → `develop` | MySQL 스키마 스냅샷과 엔티티 대조(`ddl-auto=validate`), 릴레이 조회·인덱스 확인 |
| [PR Title Check](.github/workflows/pr-title.yml) | 모든 PR | PR 제목 형식 검사 |

> `ddl-auto=validate`는 컬럼 길이·인덱스·유니크/FK·nullable 차이를 검출하지 않으므로, 스키마 스냅샷 반영 여부를 별도로 확인해야 합니다.

### CD (지속적 배포)

`main`에 push하거나 GitHub Actions에서 수동 실행하면 [CD 워크플로](.github/workflows/cd.yml)가 시작됩니다. 같은 운영 환경의 배포는 순차적으로 실행합니다.

```mermaid
flowchart LR
    MAIN["main push<br/>or workflow_dispatch"]
    ACTIONS["GitHub Actions"]
    VALIDATE["배포 파일 검증<br/>+ Gradle Test"]
    OIDC["AWS OIDC<br/>IAM Role Assume"]
    BUILD["Docker Image Build<br/>8 Services"]
    ECR["Amazon ECR"]
    PACKAGE["배포 파일 패키징"]
    EC2["AWS EC2"]
    COMPOSE["Docker Compose<br/>pull & up"]
    VERIFY["배포 상태 검증"]
    EXTERNAL["Public HTTPS<br/>Health Check"]

    MAIN --> ACTIONS
    ACTIONS --> VALIDATE
    VALIDATE --> OIDC
    OIDC --> BUILD
    BUILD --> ECR
    BUILD --> PACKAGE
    PACKAGE -->|"SSH / SCP"| EC2
    ECR -->|"Image Pull"| EC2
    EC2 --> COMPOSE
    COMPOSE --> VERIFY
    VERIFY --> EXTERNAL
```

- 배포 설정을 검증하고 전체 모듈 테스트를 실행합니다.
- AWS OIDC로 인증한 뒤 8개 서비스 이미지를 Git 커밋 SHA로 태그해 ECR에 올리고, EC2의 Docker Compose로 배포합니다.
- 애플리케이션·모니터링·이미지 버전을 검증한 뒤 릴리스 정보를 기록하고, 외부 HTTPS 상태를 확인합니다.

> 배포 실패 시 **자동 롤백은 수행하지 않습니다.** `PREVIOUS_RELEASE`의 직전 릴리스 정보와 ECR의 기존 이미지로 복구할 수 있습니다.

### 운영 배포 환경

운영 환경은 `SPRING_PROFILES_ACTIVE=prod`로 실행합니다. 환경변수 목록은 [운영 환경변수 템플릿](deploy/.env.prod.example)을 참고하세요.

- 실제 값은 EC2의 `deploy/.env`에 저장하고 Git에 커밋하지 않습니다. `__REQUIRED__` 항목은 실제 값으로 채워야 합니다.
- `GATEWAY_INTERNAL_TOKEN`과 `INTERNAL_API_TOKEN`은 각 토큰을 사용하는 서비스 간에 값이 일치해야 합니다. 내부 API 토큰이 비어 있으면 기동에 실패합니다.
- 서비스 간 호출 URL(`*_SERVICE_URL`)은 [운영 Compose 설정](deploy/docker-compose.prod.yml)의 `environment`에서 관리합니다. `deploy/.env`에 같은 키를 추가해도 덮어쓸 수 없습니다.

변경별 배포 순서와 사전 확인 사항은 [UTC 전환 가이드](docs/utc-timestamp-rollout.md)와 [좌석맵 배포 가이드](docs/seat-map-layout-rollout.md)를 참고하세요.

---

## 👥 팀원과 담당 역할

|                            프로필                            | 이름  |                     GitHub                     | 담당 파트                                                              |
|:---------------------------------------------------------:|:---:|:----------------------------------------------:|--------------------------------------------------------------------|
|    <img src="https://github.com/50h33.png" width="80">    | 김소희 |       [@50h33](https://github.com/50h33)       | 공통 인프라 구축 · 좌석(seat) · 예매(booking) · 티켓(ticket) · 모니터링 · 부하/성능 테스트 |
|  <img src="https://github.com/calla1102.png" width="80">  | 김민주 |   [@calla1102](https://github.com/calla1102)   | 디자인(Figma) · 공연(performance) · 결제(payment) · CI                    |
| <img src="https://github.com/kimhyerim01.png" width="80"> | 김혜림 | [@kimhyerim01](https://github.com/kimhyerim01) | 인증(auth) · 회원(user) · 게이트웨이(gateway) · 배포(CD)                      |

---

## 📚 상세 문서

| 문서                                                            | 내용                          |
|---------------------------------------------------------------|-----------------------------|
| [AGENTS.md](AGENTS.md)                                        | 도구 중립 AI 진입점 / 문서 라우팅       |
| [CLAUDE.md](CLAUDE.md)                                        | 아키텍처 개요 + AI 작업 규칙          |
| [backend-convention.md](docs/backend-convention.md)           | 코딩·협업 컨벤션, 버전 정보 (SSOT)     |
| [ddd-directory-structure.md](docs/ddd-directory-structure.md) | DDD 디렉토리 구조                 |
| [erd.md](docs/erd.md)                                         | 데이터 모델 / ERD               |
| [kafka-event-guide.md](docs/kafka-event-guide.md)             | Kafka 이벤트 / Outbox / DLT 정책 |
| [mapstruct-guide.md](docs/mapstruct-guide.md)                 | MapStruct Mapper 구조         |
| [ai-workflow-guide.md](docs/ai-workflow-guide.md)             | Claude Code AI 개발 워크플로우     |
| [load-test-guide.md](docs/load-test-guide.md)                 | 부하 테스트 실행 런북              |
| [performance-report.md](docs/performance-report.md)           | 성능 측정 종합 리포트 (병목 이동·개선 전후·한계) |
| [capacity-planning.md](docs/capacity-planning.md)             | 예상 트래픽·인프라 설계 기준 (실측 역산) |
| [adr/](docs/adr/)                                             | 아키텍처 결정 기록(ADR)             |
