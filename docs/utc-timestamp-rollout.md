# UTC 시각 응답 전환 배포·롤백 가이드 (#646)

발생·만료 시각 응답에 UTC 오프셋(`Z`)을 명시하고 시계 기준을 UTC로 고정한 변경의 계약·배포·롤백 절차다.
이 문서는 절차 정의만 담으며, 운영 배포·캐시 조작·데이터 수정은 별도 승인 후 수행한다.

> **주의 — `main` 머지 = 자동 배포.** `.github/workflows/cd.yml`은 `main` push에서 전 서비스를 한 번에 교체한다.
> 아래 2장 확인과 3장 캐시 정리는 파이프라인에 없으므로, **머지 전에** 2장을 끝내고 머지 직후 3장 캐시 정리를 담당자가 수동으로 실행한다.
> 확인이 끝나지 않았으면 머지를 보류한다.

## 1. 바뀌는 계약

응답 형식: `yyyy-MM-dd'T'HH:mm:ss'Z'` (UTC, 초 단위 절삭). null 필드는 기존대로 생략한다.

| 서비스 | 응답 DTO | 필드 |
|---|---|---|
| booking | `BookingSummaryResponse`, `BookingMySummaryResponse` | `confirmed_at`, `refund_failed_at`, `updated_at`, `expires_at` |
| booking | `BookingDetailResponse` | `confirmed_at`, `expires_at` |
| booking | `BookingAdminSummaryResponse` | `booked_at` |
| payment | `PaymentSummaryResponse`, `PaymentDetailResponse`, `PaymentConfirmResponse` | `paid_at` |
| payment | `PaymentCancelResponse` / `RefundResponse` | `canceled_at` / `confirmed_at` |
| ticket | `TicketQrResponse` / `EntryCheckInResponse` | `issued_at`, `expires_at` / `used_at` |
| user | `UserMeResponse` | `created_at` |
| seat | `SeatMapItemResponse`, `SeatStatusChangedResponse`(SSE 포함), `SeatAdminMonitoringResponse.seats[]` | `hold_expired_at` |
| seat | `SeatAdminSeatDetailResponse` | `hold_started_at`, `hold_expired_at` |

예: 기존 `"expires_at": "2026-05-22 10:35:00"` → `"expires_at": "2026-05-22T10:35:00Z"`.
운영 앱 컨테이너는 이미 UTC로 동작해 왔으므로(`docs/load-test-guide.md` "배포본 앱은 UTC") 운영 응답은 숫자가 그대로이고 `Z`만 붙는다.
로컬 등 KST JVM에서 만든 데이터만 숫자가 9시간 달라진다.

적용 방식: 지정 필드에만 `@JsonSerialize(using = UtcLocalDateTimeSerializer.class)`를 붙인다
(`common/src/main/java/com/ticketrush/global/json/UtcLocalDateTimeSerializer.java`).

### Asia/Seoul 오프셋을 붙이는 필드 (#671) — 위 UTC 그룹과 규칙이 다르다

응답 형식: `yyyy-MM-dd'T'HH:mm:ss+09:00` (Asia/Seoul, 초 단위 절삭). null 필드는 기존대로 생략한다.

| 서비스 | 응답 DTO | 필드 |
|---|---|---|
| performance | `PerformanceDetailResponse` | `booking_open_at` |

예: 기존 `"booking_open_at": "2027-08-01 20:00:00"` → `"booking_open_at": "2027-08-01T20:00:00+09:00"`.
**숫자는 어드민 입력과 같고 오프셋만 붙는다** — 위 UTC 그룹처럼 UTC로 환산하지 않는다.

이 필드는 저장값이 어드민이 오프셋 없이 입력한 **Asia/Seoul 벽시계**라(ADR 0020) `UtcLocalDateTimeSerializer`를
쓸 수 없다. 그 직렬화기는 값이 이미 UTC라고 전제하고 `Z`를 리터럴로 붙이기만 해서, 형식은 정상으로 보이면서
9시간 어긋난 순간을 가리킨다. 대신
`performance-service/src/main/java/com/ticketrush/boundedcontext/performance/app/support/SeoulWallClockSerializer.java`가
`PerformanceShowTimePolicy.SHOW_ZONE`으로 오프셋을 계산한다.

`Z`가 아니라 `+09:00`인 이유는 **이 필드만 읽기와 쓰기가 같은 화면을 왕복**하기 때문이다. 어드민 수정 화면이
별도 관리자 상세 API 없이 이 응답을 재사용하고(#650) 요청 DTO는 오프셋 없는 KST를 받으므로, `Z`로 내면 폼이
그 값을 되돌려 저장하는 순간 오픈 시각이 조용히 9시간 앞당겨진다. 저장·비교 축과 요청 형식은 그대로다.

**알려진 한계:** 같은 응답의 `show_date`·`show_time`은 ADR 0020이 같은 축으로 묶은 값인데도 아직 존 표시가
없다. 프론트는 한 응답 안에서 두 규칙을 쓰게 된다. 함께 옮기지 않은 이유는 이번 증상이 `booking_open_at`
하나에서만 보고됐고, 두 필드는 `DATE`·`TIME` 컬럼이라 형식 결정이 별개이기 때문이다 — 별도 이슈 후보다.

### 바뀌지 않는 계약 (불변 조건)

- 전역 Jackson 포맷(`JacksonConfig`: `yyyy-MM-dd HH:mm:ss`, snake_case, NON_NULL)
- 요청 DTO의 날짜·시각 입력 형식
- Kafka 이벤트 페이로드·Outbox 페이로드 형식, 서비스 간 내부 DTO
- 공연 `show_date`, `show_time`의 형식과 값

### 시계 기준

- `ClockConfig`: `Clock.systemUTC()`. JPA auditing(`JpaConfig`)은 주입된 Clock을 UTC로 해석하고, Clock이 없으면 `systemUTC`를 쓴다.
- booking·payment·ticket·seat의 발생·만료 시각 생성과 비교는 `LocalDateTime.now(ZoneOffset.UTC)` 또는 UTC Clock을 쓴다.
- Toss 승인·조회·취소의 오프셋 시각은 `withOffsetSameInstant(ZoneOffset.UTC)`로 변환한다(초 미만 정밀도는 응답 직렬화 때만 절삭).
- Outbox 외피 시각은 audited `createdAt`을 UTC로 해석한다. Inbox/DLT/Outbox 보존 기간 임계값과 Outbox `publishedAt`도 UTC다.
- `Dockerfile`: `TZ=UTC`, `-Duser.timezone=UTC`. 이 설정은 DB 데이터가 UTC라는 증거가 아니다.
- 런타임 시간대 영향만 받는 비목표: performance-service의 `Performance.deletedAt`, 대시보드·통계의 `LocalDate.now()`.
  - performance 벌크 JPQL(오픈 전환 #653·CLOSED 전환 #651·오픈 시각 해제 #653)은 `updatedAt`에 UTC Clock 값을 쓰고, 공연 시각·예매 오픈 시각 비교는 Asia/Seoul 벽시계로 한다(ADR 0020). 비교 축과 기록 축이 다르므로 하나의 `now`로 둘 다 채우지 않는다.
  - booking 일별 매출 집계(`cast(confirmedAt as LocalDate)`)의 날짜 경계는 UTC 자정이다.

### 좌석맵 캐시

- 키: `seat:seat-map:<performanceId>` → `seat:seat-map:v2:<performanceId>`. 새 버전은 구 키를 읽지 않는다.
- TTL 30초, cache-aside, fail-open은 그대로다.

## 2. 배포 전 확인 (차단 조건)

아래 항목을 권한 있는 **읽기 전용** 근거로 확인한다. 하나라도 미확인·혼합·비UTC면 배포와 재처리를 **중단**하고 별도 승인된 마이그레이션 계획을 먼저 세운다.

1. **DB 시각 저장 규약**: 기존 `created_at`, `updated_at`, `hold_expired_at`, `paid_at` 등을 기록한 **앱 JVM의 시간대**를 확인한다(운영 앱 컨테이너 `TZ`/`user.timezone`, 과거 배포 이력).
   시각 컬럼은 모두 `DATETIME`이라 앱이 바인딩한 값이 그대로 저장되며, MySQL 컨테이너의 `TZ: Asia/Seoul`·`@@time_zone`은 이 값에 영향을 주지 않는다. MySQL이 KST라는 사실만으로 차단하지 않는다.
2. **대기 중 Outbox 행**: `PENDING`/`FAILED` 행 페이로드 시각의 출처 시간대.
3. **Kafka 미처리 메시지**: 컨슈머 lag에 남은 이벤트의 시각 출처 시간대.
4. **DLT 재처리 데이터**: 재처리 대상 페이로드·외피의 시각 출처 시간대.
5. **소비자 준비**: 프런트엔드·관리자 화면이 `Z` 포함 ISO-8601과 기존 무시간대 형식(`yyyy-MM-dd HH:mm:ss`, UTC로 해석)을 **둘 다** 파싱할 수 있는지. 롤백 시 무시간대 형식이 돌아오기 때문이다.
6. **`booking_open_at` 업무 의미**: KST 벽시계로 확정했다(ADR 0020·#653). 응답 축은 #671에서 오프셋 표기(`+09:00`)로 옮겼고 저장값·비교 축과 요청 형식은 그대로다. 오픈 전환은 UTC 런타임에서도 Asia/Seoul 벽시계와 비교하므로 충돌은 해소됐다. #653 배포 전에는 기존 `booking_open_at` 값이 KST 의도인지 확인한다 — UTC 의도로 넣은 값은 배포 첫 주기에 KST 기준 이미 도래한 것이 한꺼번에 열리고, 그 뒤로도 건마다 9시간 일찍 열린다. `ON_SALE → UPCOMING` 전이가 없어 코드 롤백으로 되돌릴 수 없으므로 첫 주기 대상 id를 배포 전에 스냅샷한다.

금지: 추측에 따른 +9/-9시간 보정, 시각 재표기, 메시지 폐기·유실로 대기열 비우기. UTC임이 확인된 기존 페이로드는 원문 바이트와 이벤트 ID를 유지한 채 재처리한다.

## 3. 배포 절차 (단일 버전 전환)

구·신 버전의 동시 서비스는 지원하지 않는다. 구버전은 v2 캐시를 무효화하지 못하기 때문이다.

1. 구버전의 트래픽을 차단하고, SSE 장기 연결·스케줄러·Kafka 컨슈머 등 쓰기 주체를 멈춘 뒤 진행 중 작업이 끝나기를 기다린다.
2. 좌석맵 캐시 네임스페이스만 한정해 정리한다: `seat:seat-map:*` (v2 포함). 운영 Redis는 전 서비스가 DB 0을 공유하므로 `KEYS` 대신 `SCAN` + `UNLINK`로 지운다. `FLUSHALL`·`seat:lock:*` 정리는 금지.
   운영 Redis는 포트를 publish하지 않고 `--requirepass`를 쓰므로, 인증값 `REDISCLI_AUTH`가 있는 `ticketrush-redis` 컨테이너 안에서 실행한다(`-a`는 붙이지 않는다 — `docs/load-test-guide.md`의 같은 관용구 참고).

   ```bash
   docker exec ticketrush-redis sh -c \
     'redis-cli --scan --pattern "seat:seat-map:*" | xargs -r redis-cli unlink'
   # 0이어야 한다
   docker exec ticketrush-redis sh -c 'redis-cli --scan --pattern "seat:seat-map:*" | wc -l'
   ```
3. UTC 런타임의 신버전만 기동·활성화한다.
4. 마지막 구버전 쓰기가 끝난 뒤 한 번 더 2번을 수행해 구 포맷 재적재를 막는다.
5. 스모크 테스트: 대상 응답 필드의 `Z` 형식, 예매 `expires_at` = 생성 + 5분, QR `expires_at` = JWT `exp`, 좌석맵 캐시 미스·히트와 SSE `hold_expired_at`의 일치.

## 4. 롤백 절차

1. 신버전 트래픽·SSE·쓰기 주체를 멈추고 진행 중 작업을 끝낸다.
2. 호환성을 확인한다. 기준: (a) 구버전 런타임도 UTC(`TZ=UTC` 또는 `-Duser.timezone=UTC`)로 뜨는지, (b) 소비자가 무시간대 응답을 UTC로 해석하는지(2장 5번). 둘 중 하나라도 아니면 9시간 오차가 다시 생기므로 바이너리를 되돌리지 말고 롤백을 **중단**한다.
3. 좌석맵 캐시 네임스페이스만 한정해 정리한다(`seat:seat-map:*`, 3장 2번과 같은 `SCAN` + `UNLINK`).
4. 구버전을 재개한다.
