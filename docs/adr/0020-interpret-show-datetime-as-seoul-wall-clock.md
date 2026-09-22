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

### 6. `booking_open_at` 응답에는 Asia/Seoul 오프셋을 붙인다 (#671)

저장·비교 축과 요청 형식은 위 결정 그대로이고, **응답에 존 표시만 더한다.** 응답 형식(`JacksonConfig`: `yyyy-MM-dd HH:mm:ss`)에 존 표시가 없어서, 서버 판정이 정확해도 클라이언트가 그 값을 KST로 읽을지 UTC로 읽을지 정할 근거가 응답 안에 없었다 — 두 해석의 차이가 정확히 9시간이고, 그것이 "관리자가 설정한 시각과 예매 활성화 시각이 9시간 어긋난다"는 제보의 정체였다.

**`Z`(UTC)가 아니라 `+09:00`을 고른다.** [#646](https://github.com/TicketRush/TicketRush-backend/issues/646)이 같은 API 군의 발생·만료 시각을 전부 `...Z`로 통일했으므로 표기 통일만 보면 `Z`가 맞다. 그런데 이 필드는 그 필드들과 성격이 하나 다르다 — **읽기와 쓰기가 같은 화면을 왕복한다.** 어드민 수정 화면은 별도 관리자 상세 API 없이 이 응답을 재사용하고([#650](https://github.com/TicketRush/TicketRush-backend/issues/650)), 요청 DTO(`PerformanceCreateRequest`·`PerformancePatchRequest`)는 오프셋 없는 KST를 받는다. `Z`로 내면 응답 숫자가 어드민 입력과 9시간 달라져, 폼이 그 값을 그대로 되돌려 저장하는 순간 **오픈 시각이 조용히 앞당겨진다.** `+09:00`은 숫자를 입력값과 같게 유지하면서 존 표시만 더하므로 그 왕복이 자동으로 맞고, 운영 대조에도 환산이 필요 없다.

대가는 같은 API 군에서 이 필드만 표기가 갈리는 것이다. 감수한다 — 표기 통일은 문서로 설명할 수 있지만, 쓰기 왕복의 조용한 9시간 오염은 설명으로 막을 수 없다.

환산은 `SeoulWallClockSerializer`가 와이어 경계에서만 한다. DTO 필드가 담은 값은 그대로 Asia/Seoul 벽시계이고, 존은 `PerformanceShowTimePolicy.SHOW_ZONE`을 참조해 판정과 직렬화가 같은 상수를 쓴다 — 여기서 상수를 복제하면 이 이슈가 고친 증상이 되살아난다. `common`의 `UtcLocalDateTimeSerializer`는 값이 이미 UTC라고 전제하므로 재사용할 수 없고, 상속으로도 쓰지 않는다 — 전제가 반대라 하위 타입이 성립하지 않고 두 직렬화기의 `handledType()`이 같아 서로의 자리에 끼면 이중 환산이 조용히 나간다.

### 7. `show_date`·`show_time`은 그대로 두고 합쳐진 `show_at`을 함께 내린다 (#671)

결정 6이 `booking_open_at`에 오프셋을 붙이자 같은 응답 안에서 해석 규칙이 갈렸다 — `booking_open_at`은 존 표시가 있고 `show_date`·`show_time`은 없다. 이 결정이 같은 축으로 묶은 값들인데 프론트가 필드마다 다른 규칙을 써야 하는 상태다.

두 필드에 오프셋을 직접 붙일 수는 없다. `DATE`·`TIME` 별개 컬럼이라 `LocalDate`·`LocalTime`으로 쪼개져 있고, 날짜 없는 시각에 오프셋을 붙이는 것(`"19:00:00+09:00"`)은 유효한 ISO-8601이지만 의미가 불분명하며 날짜와 합칠 로직이 여전히 필요하다.

그래서 **기존 두 필드를 그대로 두고 `show_at`을 추가한다.** `yyyy-MM-dd'T'HH:mm:ss+09:00`이며 `booking_open_at`과 같은 직렬화기·같은 존 상수를 쓴다. 필드 추가는 하위 호환이라 프론트 동시 배포가 필요 없고, 시간 계산이 필요한 소비자는 이 필드 하나만 보면 된다. 합치는 규칙은 `PerformanceShowTimePolicy.showAt`이 소유해 두 곳에서 갈라지지 않게 한다.

적용 대상은 `show_date`·`show_time`을 내리는 응답 전부다 — 상세·사용자 목록·관리자 목록. 한 응답만 고치면 이 결정이 고치려는 불일치가 다른 응답에 남는다.

기존 두 필드의 제거는 프론트가 `show_at`으로 이전한 뒤의 별개 작업이다.

## 결과

- 사용자 목록은 시작 시각이 지난 공연을 즉시 걸러내고, 스케줄러가 1분 주기로 `ON_SALE`을 `CLOSED`로 맞춘다. 상세 조회는 예매 내역·티켓에서 들어오는 경로를 막지 않기 위해 그대로 둔다.
- 목록 캐시 키에 시각을 넣지 않아 TTL(30초)만큼 늦게 빠질 수 있다. 키에 시각을 넣으면 캐시가 무의미해지므로 감수한다.
- (해소됨 · #653) ~~`booking_open_at`은 같은 형식인데 아직 `LocalDateTime.now()`(JVM 존)와 비교한다. 한 서비스 안에 두 해석이 공존하는 상태가 남는다.~~ — `bookingOpenAt`도 같은 정책으로 해석한다. `PerformanceShowTimePolicy.bookingOpenCutoff()`가 Asia/Seoul 벽시계 `LocalDateTime`(초 절삭)을 내고, 오픈 벌크 전환(`bulkTransitionStatusByBookingOpenAtDue`)은 그 값과 비교하며 `updatedAt`은 common `ClockConfig`의 UTC Clock 빈 값으로 따로 받는다 — CLOSED 전환과 같은 꼴이다. 해제 API의 `updatedAt`도 같은 Clock으로 맞춰 performance 벌크 JPQL의 기록 축이 전부 auditing과 같아졌다(auditing은 그 Clock을 UTC로 재해석하므로, 이 동일성은 Clock 빈이 UTC라는 전제 위에 있다). 사람이 입력하는 공연 시각의 해석은 이제 한 서비스 안에서 하나다.
- **`CLOSED`가 어드민의 의도적 행위에서 시스템이 자동으로 만드는 상태로 바뀌었는데, 전이표에 `CLOSED → ON_SALE`이 없다.** 어드민이 당일 공연을 연기하려고 수정 화면을 열어 둔 사이 스케줄러가 닫으면, 수정이 커밋돼도 상태는 건드리지 않으므로 "시작 시각은 미래인데 `CLOSED`"인 공연이 생기고 되돌릴 전이가 없다. 이미 지난 공연을 연기해 재판매하는 운영 케이스도 같은 벽에 막힌다. 이번 범위에서는 관리자 API 설명에 제약을 명시하는 데 그치며, `CLOSED → ON_SALE`(또는 `UPCOMING`) 전이 허용 여부는 후속 이슈로 결정한다.
- **배포 전에 운영 DB의 `show_time` 표본이 KST 벽시계인지 사람이 확인해야 한다.** 이 결정의 근거는 정황이지 데이터 확인이 아니다. 표본이 05:00~11:00대에 몰려 있으면 UTC로 입력된 데이터일 수 있어 배포를 멈춘다. 첫 주기에 닫힐 `ON_SALE` id 스냅샷을 배포 전에 확보해 오판 시 수동 복구 입력으로 쓴다.
- 전이표에 `UPCOMING → CLOSED`가 없어 `UPCOMING`인 채 시작 시각이 지난 공연은 상태가 영구 `UPCOMING`으로 남는다. 목록에서는 빠지므로 노출은 막히며, 처리 방침은 후속 이슈다.
- 서비스 지역이 늘어 공연 시각의 시간대가 하나가 아니게 되면 이 결정은 폐기되고, 공연마다 시간대를 저장하는 결정으로 대체돼야 한다.
- **(#671) `show_at`이 추가돼 공연 시각을 내리는 응답마다 필드가 셋이 됐다**(`show_date`·`show_time`·`show_at`). 셋이 어긋날 수 없도록 `show_at`은 저장하지 않고 매 응답마다 두 컬럼에서 합성한다 — 저장하면 세 번째 진실이 생긴다. 프론트가 이전을 끝내면 앞의 둘을 제거하는 것이 다음 단계다.
- **(#671) 기록 시각의 JVM 기본 존 의존을 걷어냈다.** `Performance.softDelete()`는 시각을 파라미터로 받아 auditing과 같은 UTC Clock 값을 쓰고, 관리자 대시보드·공연별 집계의 "오늘"(`PerformanceGetAdminDashboardUseCase`·`BookingRestClient`)도 주입된 Clock에서 만든다. 같은 요청이 운영(UTC)과 로컬(KST)에서 다른 기간을 돌려주던 것이 없어졌다.
  - **그 "오늘"은 UTC다.** booking의 일별 매출 버킷이 `cast(confirmedAt as LocalDate)`이고 `confirmedAt`이 UTC이므로 조회 기간도 같은 축이어야 버킷과 맞는다. 호출자만 KST로 바꾸면 요청 날짜와 버킷 날짜가 9시간 어긋난다. **"매출 업무일을 KST로 볼 것인가"는 booking의 집계 경계까지 함께 옮겨야 하는 별개 결정이며, 현재는 한국 관리자가 보는 "오늘"이 KST 09:00에 넘어간다.**
- **(#671) 게이트웨이 대기열은 `booking_open_at`과 연동하지 않았다.** `POST /api/v1/waiting-room/{performanceId}/open`은 여전히 운영자 수동 호출이고 Redis의 `openedAt`을 직접 쓴다. 이번에 서버가 오픈 전 예매를 차단하게 됐으므로 **"대기열은 열려 있는데 예매는 `PERFORMANCE_400_005`"** 조합이 새로 가능해졌다 — 운영자가 오픈 시각 전에 대기열을 열면 통과한 사용자가 예매에서 막힌다. 연동은 오픈 벌크 전환이 gateway에 알리는 새 횡단 경로와 "오픈 몇 분 전에 열 것인가"라는 제품 결정이 함께 필요해 별개 작업으로 둔다.
- **(#671) `booking_open_at` 응답 형식 변경은 파괴적 계약 변경이라 프론트와 동시 배포가 필요하다.** `main` 머지 = 자동 배포(`.github/workflows/cd.yml`)이므로 배포 시점 합의 전에는 머지하지 않는다. 롤백하면 무시간대 형식이 돌아오므로 프론트는 두 형식을 모두 파싱할 수 있어야 한다. 요청 형식은 바뀌지 않으므로 어드민 저장 경로는 손댈 필요가 없다.
- **(#671) 예매 버튼 판정은 `booking_open_at` 직접 비교가 아니라 `performance_status == ON_SALE`로 옮긴다.** 응답의 존을 고쳐도 사용자 PC 시계가 틀리면 계속 어긋난다. 이 필드는 상세·목록 응답 양쪽에 이미 있고 서버 스케줄러가 갱신한다.
- **(#671) 오픈 전 예매는 서버가 `PERFORMANCE_400_005`로 거절한다.** 프론트에만 있던 제한이라 `POST /api/v1/booking` 직접 호출로 우회됐다. booking-service가 공유 스키마의 `performance_status`를 직접 읽어 판정하므로 예매 생성 핫패스에 원격 왕복이 생기지 않는다(`BookingValidateReferencesUseCase`). 상태를 읽지 못하면 조회가 실패해 요청이 끝나고, 삭제된 공연은 `deleted_at` 조건으로 빠져 막힌다.
  - **이 직접 읽기는 ADR 0003의 규율("한 서비스는 다른 도메인의 테이블을 직접 조회·변경하지 않는다")과 충돌한다.** 새 위반은 아니다 — booking의 참조 검증은 이전부터 `user`·`performance`·`seat` 테이블을 직접 읽어 왔고 이번 변경은 같은 행에서 컬럼 하나를 더 읽는다. ADR 0003이 트레이드오프로 남긴 "코드 리뷰에서 새어들 위험"이 실현된 자리이므로, 정합성 회복(리더들을 내부 API로 옮기거나 ADR 0003을 개정)은 후속 이슈로 남긴다.
  - 같은 규칙의 사본이 두 곳이 됐다. performance-service의 `PerformanceValidateUseCase`(+ `/api/v1/internal/performance/{id}/validate`)가 원본인데 레포 전체에 호출부가 없다. 판매 가능 상태가 늘거나 상태명이 바뀌면 booking 쪽 문자열은 따라가지 않는다 — 방향은 fail-closed라 "전면 차단"으로 드러나므로 조용히 뚫리지는 않는다. `PerformanceStatus`를 common으로 올려 사본을 없앨지, 죽은 내부 엔드포인트를 정리할지는 후속 이슈다.
  - 판정에 남는 창이 두 방향이다. 상태 전환은 스케줄러가 맡으므로 오픈 시각 정각보다 늦게 열리고, 검증과 생성이 다른 트랜잭션이라 그 사이의 `ON_SALE → CLOSED/CANCELED` 전이는 닫힌 공연의 예매를 허용한다(수 ms, #668의 환불 가드와 같은 판단으로 분산 락은 두지 않는다).
  - `POST /api/v1/booking`에 `PERFORMANCE_400_005`가 추가되고, 오픈 전 공연 + 없는 좌석 조합의 응답이 `SEAT_NOT_FOUND`에서 이 코드로 바뀐다. 오픈 전 공연의 좌석 존재 여부를 알려주지 않게 된 것이라 정보 노출 축에서는 개선이지만, 클라이언트 분기에는 영향이 있다.
