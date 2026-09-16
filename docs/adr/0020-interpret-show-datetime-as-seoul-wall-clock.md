# 20. 공연 시작 시각(show_date·show_time)은 Asia/Seoul 벽시계로 해석하고, 그 비교에 쓰는 현재 시각은 UTC Clock을 존 변환해 만든다

날짜: 2026-09-15

## 상태

승인됨

## 맥락

공연일이 지난 공연이 사용자 목록에 그대로 노출되고 상태도 `ON_SALE`로 남는 문제([#651](https://github.com/TicketRush/TicketRush-backend/issues/651))를 고치려면, 저장된 공연 시작 시각(`show_date` + `show_time`)을 **현재 시각과 비교**해야 한다. 이 레포에서 그 값을 현재 시각과 비교하는 코드는 이것이 처음이다.

문제는 그 값에 시간대가 없다는 것이다. `show_date`·`show_time`은 어드민이 `yyyy-MM-dd`·`HH:mm:ss`로 입력하는 오프셋 없는 벽시계 값이고, 컬럼도 `DATE`·`TIME`이라 저장 시점에 시간대가 붙지 않는다. 한편 레포의 시각 규약은 [#646](https://github.com/TicketRush/TicketRush-backend/issues/646)에서 UTC로 정리됐다 — common의 `ClockConfig`는 `Clock.systemUTC()`이고, JPA auditing·booking·payment·ticket·seat의 발생·만료 시각은 UTC이며, 운영 컨테이너는 `Dockerfile`에서 `TZ=UTC`·`-Duser.timezone=UTC`로 고정됐다. 그 정리는 performance-service를 비목표로 두었고, `docs/utc-timestamp-rollout.md`는 "`booking_open_at`이 KST 벽시계라면 UTC 런타임의 비교와 충돌하는지"를 별도 이슈로 남겼다.

그래서 이 이슈가 사실상 그 질문에 처음 답하게 됐다. 잘못 답하면 대가가 크다. `show_date`·`show_time`이 KST 벽시계인데 UTC 현재 시각과 비교하면 공연이 시작하고도 9시간 동안 목록에 남고 CLOSED 전환도 9시간 늦는다. 반대 방향이면 아직 시작 전인 당일 공연이 최대 9시간 일찍 닫힌다. 그리고 전이표에 `CLOSED → ON_SALE`이 없어 **잘못 닫힌 공연은 코드 롤백으로 돌아오지 않는다.**

값의 의미를 확정할 근거는 코드·문서 어디에도 없었다. 다만 정황은 한쪽을 가리켰다.

- 요청 DTO(`PerformanceCreateRequest`)가 오프셋 없는 `LocalDate`·`LocalTime`을 받고, Swagger에 시간대 언급이 없다. 어드민이 "9월 15일 19시 공연"이라고 입력하는 값이다.
- 한국 서비스이고, 운영 MySQL 컨테이너는 `TZ: Asia/Seoul`이다.
- 시드(`DataInitRunner`)와 문서 예시의 시각이 전부 15:00~20:00대 — 한국 공연 시각이다.

## 결정

### 1. `show_date`·`show_time`은 Asia/Seoul 벽시계로 해석한다

이 값은 시스템이 생성하는 시각(auditing·만료·발생)이 아니라 사람이 입력하는 공연 시각이다. UTC 규약은 전자를 위한 것이고, 후자에 같은 규약을 강제하면 어드민이 UTC로 환산해 입력해야 한다. 해석을 KST로 고정하고, 그 해석을 `PerformanceShowTimePolicy`의 상수 `SHOW_ZONE` 한 곳에 둔다.

### 2. 비교용 현재 시각은 UTC Clock을 존 변환해 만든다

`LocalDateTime.now()`는 JVM 기본 시간대를 따라 운영(UTC)과 로컬(KST)에서 다른 값을 돌려준다. 대신 common의 `Clock`(UTC)을 주입받아 `Clock.withZone(Asia/Seoul)`로 읽는다. 어느 JVM에서 돌아도 같은 판정이 나오고, 테스트는 이 정책 컴포넌트만 대체해 시각을 고정한다.

### 3. 시작 시각 정각은 "지남"이다

기준 문장은 하나다 — "공연 시작 시각(showDate + showTime, Asia/Seoul)이 지났다 = 시작 시각 정각을 포함해 현재 시각과 같거나 이전이다." 목록·스케줄러·Swagger가 이 문장을 그대로 반복한다.

### 4. 목록 제외 조건과 CLOSED 벌크 전환은 같은 컷오프 값을 받아 여집합을 코드로 강제한다

정책이 한 번의 `now`에서 날짜·시각을 뽑아 `ShowTimeCutoff(date, time)`으로 묶고, 목록(`showDate > d OR (showDate = d AND showTime > t)`)과 벌크 전환(`showDate < d OR (showDate = d AND showTime <= t)`)이 그 값을 그대로 받는다. 두 컬럼을 함수로 합쳐 비교하지 않는다 — 함수는 H2(MySQL 모드)와 MySQL이 갈릴 수 있지만 비교 연산자는 양쪽에서 같은 결과를 냈다. 여집합은 테스트로 고정한다.

### 5. 벌크 전환의 `updated_at`은 auditing과 같은 UTC Clock 값이다

비교 축(KST 벽시계)과 기록 축(UTC)을 구분한다. 하나의 `now`로 둘 다 채우면 어느 한쪽이 9시간 어긋난다.

## 결과

- 사용자 목록은 시작 시각이 지난 공연을 즉시 걸러내고, 스케줄러가 1분 주기로 `ON_SALE`을 `CLOSED`로 맞춘다. 상세 조회는 예매 내역·티켓에서 들어오는 경로를 막지 않기 위해 그대로 둔다.
- 목록 캐시 키에 시각을 넣지 않아 TTL(30초)만큼 늦게 빠질 수 있다. 키에 시각을 넣으면 캐시가 무의미해지므로 감수한다.
- (해소됨 · #653) ~~`booking_open_at`은 같은 형식인데 아직 `LocalDateTime.now()`(JVM 존)와 비교한다. 한 서비스 안에 두 해석이 공존하는 상태가 남는다.~~ — `bookingOpenAt`도 같은 정책으로 해석한다. `PerformanceShowTimePolicy.bookingOpenCutoff()`가 Asia/Seoul 벽시계 `LocalDateTime`(초 절삭)을 내고, 오픈 벌크 전환(`bulkTransitionStatusByBookingOpenAtDue`)은 그 값과 비교하며 `updatedAt`은 common `ClockConfig`의 UTC Clock 빈 값으로 따로 받는다 — CLOSED 전환과 같은 꼴이다. 해제 API의 `updatedAt`도 같은 Clock으로 맞춰 performance 벌크 JPQL의 기록 축이 전부 auditing과 같아졌다(auditing은 그 Clock을 UTC로 재해석하므로, 이 동일성은 Clock 빈이 UTC라는 전제 위에 있다). 사람이 입력하는 공연 시각의 해석은 이제 한 서비스 안에서 하나다.
- **`CLOSED`가 어드민의 의도적 행위에서 시스템이 자동으로 만드는 상태로 바뀌었는데, 전이표에 `CLOSED → ON_SALE`이 없다.** 어드민이 당일 공연을 연기하려고 수정 화면을 열어 둔 사이 스케줄러가 닫으면, 수정이 커밋돼도 상태는 건드리지 않으므로 "시작 시각은 미래인데 `CLOSED`"인 공연이 생기고 되돌릴 전이가 없다. 이미 지난 공연을 연기해 재판매하는 운영 케이스도 같은 벽에 막힌다. 이번 범위에서는 관리자 API 설명에 제약을 명시하는 데 그치며, `CLOSED → ON_SALE`(또는 `UPCOMING`) 전이 허용 여부는 후속 이슈로 결정한다.
- **배포 전에 운영 DB의 `show_time` 표본이 KST 벽시계인지 사람이 확인해야 한다.** 이 결정의 근거는 정황이지 데이터 확인이 아니다. 표본이 05:00~11:00대에 몰려 있으면 UTC로 입력된 데이터일 수 있어 배포를 멈춘다. 첫 주기에 닫힐 `ON_SALE` id 스냅샷을 배포 전에 확보해 오판 시 수동 복구 입력으로 쓴다.
- 전이표에 `UPCOMING → CLOSED`가 없어 `UPCOMING`인 채 시작 시각이 지난 공연은 상태가 영구 `UPCOMING`으로 남는다. 목록에서는 빠지므로 노출은 막히며, 처리 방침은 후속 이슈다.
- 서비스 지역이 늘어 공연 시각의 시간대가 하나가 아니게 되면 이 결정은 폐기되고, 공연마다 시간대를 저장하는 결정으로 대체돼야 한다.
