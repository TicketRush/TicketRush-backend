# 5. 환불 실패를 예매 상태가 아니라 타임스탬프로 기록한다

날짜: 2026-07-10

## 상태

승인됨

## 맥락

### 환불은 두 경로로 들어온다

```
경로 A · 결제 취소 API (#22)
  POST /api/v1/payment/{paymentId}/cancel
    → PaymentCancelUseCase → booking 조회(예매번호) → PG 취소 → PaymentCanceledEvent(bookingNumber=값)

경로 B · 예매 취소 Saga (#91)
  DELETE /api/v1/booking/{bookingNumber}
    → BookingCancelMyBookingUseCase: CONFIRMED → REFUNDING
    → RefundRequestedEvent
      → payment RefundRequestedEventListener → PaymentRefundByBookingUseCase → PG 취소
         ├ 성공 ────────────→ PaymentCanceledEvent(bookingNumber=값)
         └ PG 결정적 거절 ──→ RefundFailedEvent
```

경로 A가 booking을 조회하는 것은 예매번호를 얻기 위해서다. 그 값 없이 발행하면 seat의 좌석 소유 교차검증이 통째로 꺼져 다른 예매의 SOLD 좌석을 반환하므로, 얻지 못하면 PG 취소 앞에서 취소를 끊는다([ADR 16](0016-require-booking-number-for-refund-seat-release.md)).

`PaymentCanceledEvent`는 booking·seat·ticket이 각각 다른 consumer group으로 구독해 팬아웃된다. booking은 `REFUNDED`로 종결하고, seat는 `SOLD → AVAILABLE`로 반환하고, ticket은 입장권을 취소한다.

`RefundFailedEvent`는 booking만 구독한다. **seat가 이를 구독하지 않는 것은 의도된 설계다** — 환불이 실패했으므로 좌석은 `SOLD`를 유지해야 한다. 돈도 돌려받지 못하고 좌석까지 잃는 역방향 공백을 막는 refund-first 원칙이다.

`RefundFailedEvent`는 PG가 **결정적으로 거절**했을 때만 발행된다(`FailedRefundRecorder.isDeterministicRejection`). 통신 실패처럼 성공 여부가 불명한 경우는 재시도→DLT로 넘겨 섣부른 실패 확정을 피한다.

### `REFUND_FAILED`가 흡수 상태였다

booking은 `RefundFailedEvent`를 받아 예매를 `REFUNDING → REFUND_FAILED`로 전이시켰다. 그런데 `Booking` 엔티티 어디에도 `REFUND_FAILED`를 소스로 받는 전이가 없었다. 한번 들어가면 나올 수 없는 흡수 상태(absorbing state)였고, 세 가지 결함으로 드러났다.

1. **정합성 붕괴** — 결제 취소 API(경로 A)는 booking 상태를 보지 않고 `payment.status == COMPLETED`만 검사한다. `REFUND_FAILED`에서도 payment는 `COMPLETED`이므로 이 API로 우회 환불이 성공하고, `PaymentCanceledEvent`가 발행되어 돈 환불·좌석 반환은 정상 수행된다. 그런데 booking이 호출하는 `markRefunded()`가 `REFUND_FAILED`를 소스로 받지 않아 booking만 실패 상태로 남았다. 실제로는 환불이 끝났는데 상태는 실패였다.
2. **복구 수단 부재** — `RefundFailedEvent` Javadoc과 `BookingMarkRefundFailedUseCase`는 "관리자 수동 처리"를 전제했으나, 실제 수단은 `[CRITICAL]` 로그뿐이었다. 재환불 API도, 조회 API도, 리컨실 스케줄러도 없었다.
3. **입장 차단** — `EntryVerifyUseCase`는 `bookingStatus == CONFIRMED`만 통과시킨다. 환불받지 못한 사용자가 `SOLD` 좌석도 쓰지 못했다. refund-first가 막으려던 "돈도 잃고 좌석도 잃는" 상황 그 자체였다.

### 원인은 상태 이름이 거짓말을 한 것이다

`REFUND_FAILED`는 **예매의 상태가 아니라 환불 시도의 결과**다. 예매 상태머신에 프로세스 결과가 섞여 들어갔다.

환불이 실패했다는 건 취소가 성사되지 않았다는 뜻이다. 그 순간의 실제 자원 상태를 보면 돈은 지불된 채(`payment = COMPLETED`), 좌석은 `SOLD`, 티켓은 `ISSUED`다 — **`CONFIRMED`와 완전히 동일하다.** 예매는 취소되지 않았으므로 여전히 유효한 예매다.

`REFUND_FAILED`에 벗어나는 전이를 덧붙이고 입장을 예외 허용하는 방식은 증상만 덮는다. 그렇게 하면 이후 이 상태를 다루는 모든 분기가 "실패 상태인데 입장은 되고 환불도 재시도된다"는 예외를 기억해야 한다.

한편 **환불 실패 이력의 SSOT는 이미 payment-service에 있다.** `FailedRefundRecorder`가 `Refund.failed(...)`로 FAILED 이력을 독립 트랜잭션으로 남긴다. booking의 `REFUND_FAILED` 상태는 그 이력을 예매 상태머신에 중복 인코딩한 것이었다. 게다가 `RefundFailedEvent.reason`은 payment 쪽 고정 상수 하나뿐이라 booking이 사유를 따로 저장할 실익도 없었다.

## 결정

**`BookingStatus.REFUND_FAILED`를 제거한다.** PG 환불이 최종 실패하면 예매를 `REFUNDING → CONFIRMED`로 복원하고, 실패 사실은 `Booking.refundFailedAt` 타임스탬프로만 남긴다. 실패 사유는 payment-service의 `refund` 테이블(FAILED 이력)이 SSOT다.

`Booking.markRefundFailed()`는 `recordRefundFailure(LocalDateTime failedAt)`으로 이름과 동작을 바꾼다. 이름이 거짓말한 것이 이 문제의 근원이었으므로, 실제로 하는 일(실패를 기록하고 예매를 복원한다)을 이름에 담는다.

이 결정에서 파생되는 것들:

- **입장은 허용된다.** 예매가 `CONFIRMED`이므로 `EntryVerifyUseCase`는 그대로 통과시킨다. 환불받지 못한 사용자가 좌석을 쓸 수 있다. `REFUNDING`(환불 진행 중)은 계속 차단된다.
- **재환불은 기존 취소 경로가 그대로 처리한다.** `requestRefund()`가 이미 `CONFIRMED`를 받으므로, 사용자가 예매를 다시 취소하면 환불이 재시도된다. 새 전이도 새 에러 코드도 필요 없다.
- **우회 환불은 자동으로 수렴한다.** `markRefunded()`가 이미 `CONFIRMED`를 소스로 받으므로, 결제 취소 API로 환불된 뒤 도착한 `PaymentCanceledEvent`가 booking을 `REFUNDED`로 종결시킨다. 코드 변경이 필요 없다.
- **사용자 재시도를 막지 않는다.** 막으려면 `refundFailedAt != null`일 때 취소를 거부해야 하는데, 그건 흡수 상태를 이름만 바꿔 되살리는 것이다. PG가 결정적으로 거절하는 건은 다시 실패하지만, 그건 정상적인 취소 요청이 실패하는 것과 같으며 상태는 다시 `CONFIRMED`로 수렴한다(고착 없음).
- **관리자 API를 둘 추가한다.** `GET /api/v1/booking/admin/bookings/refund-failed`로 미해결 실패 건(`CONFIRMED` + `refundFailedAt IS NOT NULL`)을 식별하고, `POST /api/v1/booking/admin/{bookingNumber}/refund-retry`로 CS가 사용자를 대신해 환불을 재시도한다. 후자는 `RefundRequestedEvent`를 재발행할 뿐이며, payment의 `PaymentRefundByBookingUseCase`가 이미 갖고 있는 self-heal 로직(결제가 `COMPLETED`면 PG 환불 재실행, 이미 `CANCELED`면 `PaymentCanceledEvent` 재발행)을 그대로 재사용한다. `eventId`는 outbox에서 발행마다 새 UUID이므로 Inbox 중복 방지에 걸리지 않는다.
- **관리자 재환불은 실패 이력이 있는 `CONFIRMED` 예매로만 제한한다.** 소유권 검증이 없는 도구이므로, 이 가드가 없으면 잘못된 `bookingNumber` 하나로 정상 예매가 강제 취소된다. 소유자가 스스로 취소하는 것과 CS가 "실패를 재시도"하는 것은 계약이 다르다. 위반 시 `BOOKING_REFUND_RETRY_NOT_ALLOWED`(`BOOKING_409_005`). 이 도구는 PG 환불을 유발하므로 행위 주체(`adminId`)와 대상을 감사 로그로 남긴다.
- **`refundFailedAt`은 재환불 시 지우지 않는다.** 관리자 조회가 `bookingStatus`로 함께 필터링하므로 `REFUNDING`·`REFUNDED`로 넘어간 건은 목록에서 빠진다. 마지막 실패 시각은 이력으로 남는 편이 낫다.

payment-service는 손대지 않는다. `RefundFailedEvent`는 Javadoc만 정정하고 레코드 필드와 토픽은 그대로 둔다.

## 결과

- **흡수 상태가 사라진다.** 어떤 상태에서 출발해도 `CONFIRMED` 또는 `REFUNDED`로 수렴하는 경로가 존재한다. 환불이 반복 실패해도 예매는 매번 `CONFIRMED`로 돌아온다.
- **세 결함이 한 번에 해소된다.** 정합성 붕괴는 `markRefunded()` 수정 없이, 입장 차단은 `EntryVerifyUseCase` 수정 없이 해결된다. `ticket-service`는 코드 변경이 전혀 없다 — 이것이 이 설계가 옳다는 신호다.
- **상태머신이 예매의 생애만 표현한다.** 이후 `bookingStatus`를 다루는 코드는 "환불 시도가 실패한 예매"라는 예외를 기억할 필요가 없다.
- **사용자가 왜 예매가 유지됐는지 알 수 있다.** `BookingSummaryResponse`에 `refundFailedAt`이 실려, 취소를 눌렀는데 예매가 `CONFIRMED`로 남은 이유를 클라이언트가 표시할 수 있다.
- (트레이드오프) **결정적 거절 건을 사용자가 반복 시도할 수 있다.** PG 호출이 낭비될 수 있으나, 흡수 상태를 되살리지 않는 편이 낫다고 판단했다. #397에서 쿨다운 도입 여부를 검토했고 **실측 전 미도입으로 결정했다** — 고착이 없어 사용자 피해가 없고 비용은 PG 호출뿐이므로, PG 호출량이 실측상 문제가 되면 그때 `refundFailedAt` 기반 쿨다운(시간제한이어야 하며 영구 차단이면 흡수 상태의 부활이다)을 재검토한다.
- (트레이드오프) **관리자가 재환불을 처리하기 전에 사용자가 입장할 수 있다.** 입장한 뒤 재환불이 성공하면 `TicketCancelUseCase`는 `USED` 티켓을 전이시키지 않지만, `SeatReleaseSoldSeatUseCase`는 티켓 사용 여부를 보지 않고 좌석을 `SOLD → AVAILABLE`로 반환한다 — 실제로 착석한 좌석이 재판매 가능해진다. 이 갭은 이 결정이 만든 것이 아니라(입장한 예매를 사용자가 취소해도 동일하다) 재환불 경로가 늘어 도달성이 넓어졌을 뿐이다. 입장 후 환불을 허용할지는 운영 정책이며, 좌석 반환 차단은 후속 과제로 분리한다. 다만 그 반대(환불도 못 받고 입장도 못 하는 상태)보다는 낫다.
- (마이그레이션) 마이그레이션 도구가 없고 prod는 `ddl-auto: validate`이므로(ADR [3](0003-shared-database-with-service-boundaries.md)), 스키마 스냅샷(작성 시점 경로 `infra/mysql/init/01-schema.sql`, 현재는 `deploy/mysql/init/001-ticket-rush-schema.sql` — #430) 갱신과 함께 기존 DB에는 수동 `ALTER`가 필요하다. 작성 시점 기준 아직 프로덕션 배포 전이라 `REFUND_FAILED` 행이 존재하지 않으므로 데이터 변환이 필요 없다. **먼저 그 전제를 확인한다.**

  ```sql
  -- 0) 전제 확인: 반드시 0이어야 한다.
  SELECT COUNT(*) FROM booking WHERE booking_status = 'REFUND_FAILED';

  ALTER TABLE booking ADD COLUMN refund_failed_at datetime(6) DEFAULT NULL;
  ALTER TABLE booking MODIFY booking_status
    enum('CANCELED','CONFIRMED','EXPIRED','PENDING','REFUNDED','REFUNDING') NOT NULL;

  -- #397 낙관적 락. 배포 전에 실행하지 않으면 ddl-auto: validate가 기동을 실패시킨다.
  ALTER TABLE booking ADD COLUMN version bigint NOT NULL DEFAULT 0;

  -- #397 REFUNDING 고착 조회용 인덱스(장애 대응 중 반복 조회 경로). validate 대상은 아니라 배포 후에 넣어도 되지만,
  -- 없으면 booking 풀스캔 + filesort가 된다.
  ALTER TABLE booking ADD INDEX idx_booking_status_updated_at (booking_status, updated_at);
  ```

  **0단계가 0이 아니면 enum을 먼저 축소해선 안 된다.** MySQL이 제거된 enum 값을 빈 문자열로 만든다. 그리고 남은 행을 일괄 `CONFIRMED`로 밀어서도 안 된다 — 위 §맥락 결함 1이 말하듯 그중 일부는 결제 취소 API로 **우회 환불이 실제로 성사된 뒤 booking만 고착된 건**이라(payment=CANCELED, 좌석은 이미 반환·재판매됐을 수 있음), `CONFIRMED`로 되살리면 입장이 허용되고 좌석이 이중 보유된다. 그 경우에만 아래를 enum 축소 **앞에** 끼워 넣는다.

  ```sql
  -- (1) 실제로 환불이 성사된 건은 REFUNDED로 수렴시킨다(우회 환불로 payment가 이미 CANCELED).
  UPDATE booking b
    JOIN payment p ON p.booking_id = b.booking_id AND p.status = 'CANCELED'
  SET b.booking_status = 'REFUNDED', b.refund_failed_at = b.updated_at
  WHERE b.booking_status = 'REFUND_FAILED';

  -- (2) 남은 건(환불되지 않은 건)만 CONFIRMED로 복원한다.
  UPDATE booking SET booking_status = 'CONFIRMED', refund_failed_at = updated_at
    WHERE booking_status = 'REFUND_FAILED';
  ```
  (1)의 `payment` 조인은 단일 공유 DB(ADR 3)에서 **일회성 운영 마이그레이션에 한해** 허용한다. 애플리케이션 코드의 크로스 도메인 조인 금지 규율과는 별개다.

  로컬은 `ddl-auto: update`가 enum 값 축소를 반영하지 않으므로 컨테이너 볼륨을 재생성하거나 위 `ALTER`를 직접 실행한다.
- (알려진 한계 · 이벤트 재생) `recordRefundFailure`는 `failedAt`이 기록된 `refundFailedAt`보다 나중일 때만 복원한다. 이 가드가 없으면, Inbox retention(기본 30일)이 만료된 뒤 DLT/토픽을 재생해 **옛 `RefundFailedEvent`가 새 재환불 시도(REFUNDING) 도중 도착**할 때 진행 중인 시도를 조용히 중단시킨다. 가드는 동일 시각 이벤트를 무시하므로 이 창을 닫는다. 다만 시도 식별자(예: `refundId`)로 매칭하는 편이 더 견고하며, 이벤트 계약을 바꿔야 하므로 후속 과제로 남긴다.
- (해소됨 · #397) ~~`Booking`에는 `@Version`이 없다~~ — `Booking`에 낙관적 락(`@Version`)을 도입했다. `requestRefund()`가 check-then-act이므로 사용자 취소와 관리자 재환불이 동시에 같은 `CONFIRMED` 예매를 읽으면 둘 다 검증을 통과했었는데, 이제 늦은 쪽 커밋이 버전 충돌로 실패한다. 이벤트 발행이 afterCommit이므로 실패한 커밋의 `RefundRequestedEvent`는 발행되지 않는다 — 이중 발행이 booking 계층에서 차단된다(그 아래 payment의 unique 제약 #296과 멱등 키는 그대로 2차 방어로 남는다). 충돌은 HTTP에서 `COMMON_409`로 응답하고, Kafka 리스너에서는 transient로 분류돼 재시도로 수렴한다. 낙관적 락은 `Booking`에만 둔다 — 베이스 엔티티로 확대하면 14개 엔티티 전체의 스키마·리스너 거동이 한꺼번에 바뀌므로 필요가 실증될 때 확대한다. 주의: 벌크 UPDATE(`expirePendingBookingById`)는 version을 증가시키지도 검사하지도 않으므로 confirm-vs-expire 경합(만료 벌크 UPDATE가 EXPIRED로 민 행을 진행 중이던 confirm 커밋이 덮는 lost update)은 이 락의 보호 밖이다 — #397 이전부터 있던 한계이며, 필요해지면 JPQL `UPDATE VERSIONED` 적용을 검토한다.

  **(#427 갱신) `Seat`으로 확대했다.** 위 "필요가 실증될 때 확대한다"의 그 필요다 — 베이스 엔티티가 아니라 엔티티 단위로 하나 더 늘리는 방식은 그대로다. `SeatHoldUseCase`도 check-then-act(`findById` → `isAvailable()` → `hold()`)인데, 이걸 막는 게 Redisson 락뿐이었다. Redis는 `appendonly yes`이지만 fsync가 `everysec`이라 크래시 시 최대 1초치 쓰기가 유실되고, 그 사이 걸린 락 키가 사라진 채 재시작되면 같은 좌석에 두 개의 HOLD가 성립한다(좌석 중복 판매). 정합성의 최종 방어선이 인메모리 스토어에 있는 구조 자체가 문제였다. 이제 **Redis 락은 경쟁을 앞단에서 걸러내는 성능 최적화**이고, **DB 낙관적 락이 정합성의 최종 방어선**이다. 비용 제약으로 Redis HA(Sentinel/Cluster)를 도입하지 않기로 한 결정(#426)의 전제 조건이기도 하다. 충돌은 `runIfFirst` 커밋 시점에 터져 Kafka 재시도로 수렴하고, 재시도 때는 좌석이 이미 HOLD라 보상 이벤트(`SeatHoldFailedEvent`)가 정상 발행된다. `Seat`의 벌크 UPDATE(`confirmSoldById`, `releaseExpiredHoldById`)도 booking과 마찬가지로 version을 건드리지 않지만, 두 벌크가 HOLD 행에만 발화하고 엔티티 더티 체킹 경로는 AVAILABLE/SOLD에서 시작해 상태 집합이 겹치지 않는다 — 자세한 불변식은 `Seat` 엔티티 주석 참고.
- (해소됨 · #397) ~~REFUNDING에서 멈춘 예매는 이 조회로 보이지 않는다~~ — payment 통신 실패로 DLT에 빠져 종결 이벤트가 끝내 오지 않으면 예매는 `REFUNDING`으로 남는다(돈도 못 받고, 좌석은 SOLD로 묶이고, 입장까지 차단). 이 사각지대에 두 수단을 추가했다. **식별**: `GET /api/v1/booking/admin/bookings/refunding-stuck`가 `REFUNDING`에서 임계 시간(기본 30분, `app.booking.refunding-stuck-threshold-minutes`) 이상 멈춘 예매를 오래된 순으로 노출한다. `REFUNDING` 진입 후 엔티티가 갱신되지 않으므로 `updatedAt`이 곧 진입 시각이다. 임계는 Kafka 재시도 소진(약 31초)보다 훨씬 길어 컨슈머 랙·재배포 지연을 오탐하지 않는다. **복구**: 환불 재시도 API가 고착 건(임계 초과 REFUNDING)이면 상태 전이 없이 `RefundRequestedEvent`만 재발행한다. booking이 자체 판단으로 `CONFIRMED` 복원하는 방식은 채택하지 않았다 — 통신 실패는 환불이 실제 성공했을 수도 있는 상태라, 재발행이 payment의 self-heal(COMPLETED면 PG 재실행, CANCELED면 `PaymentCanceledEvent` 재발행)과 맞물려 `REFUNDED` 또는 `CONFIRMED+refundFailedAt`로 안전하게 수렴하는 유일한 경로다. 임계 미달의 신선한 `REFUNDING`은 정상 진행 중이므로 여전히 거절한다. 재발행은 엔티티를 건드리지 않아 관리자 중복 호출을 낙관적 락이 막지 못하지만, payment 멱등성이 이중 환불을 막으므로 감사 로그로만 추적한다. 리컨실 스케줄러·메트릭 자동화는 도입하지 않았다 — DLT 적재 시 Slack 알림이 이미 1차 신호이고, 사람 판단 없는 자동 재발행은 무한 PG 재시도 위험이 있다.
- (해소됨 · #399) ~~입장 완료(ticket=USED) 예매의 환불 시 좌석 재판매를 막을지 결정해야 한다~~ — **운영 정책을 "입장을 완료한 예매는 환불하지 않는다"로 확정했다.** 사용자 자가 취소(`DELETE /api/v1/booking/{bookingNumber}`)와 관리자 재환불(`POST /api/v1/booking/admin/{bookingNumber}/refund-retry`)이 모두 `requestRefund()` 앞에서 ticket-service를 동기 조회해(`BookingValidateTicketNotUsedUseCase`) `USED`면 `BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED`(`BOOKING_409_006`)로 거절한다. 좌석 반환을 막는 대신 **좌석이 반환될 상황을 환불 요청 시점에 없앴으므로** `SeatReleaseSoldSeatUseCase`는 손대지 않았다 — seat가 ticket 상태를 알아야 할 이유가 사라져 ADR [3](0003-shared-database-with-service-boundaries.md)의 캡슐화 규율과도 충돌하지 않는다. 다만 이것이 **모든 경로를 닫았다는 뜻은 아니다** — 아래 후속 과제 #416과 잔여 창을 함께 볼 것.

  네 가지 후보 중 이 방식을 고른 이유와 대가는 이렇다.

  - **왜 차단인가.** 반대안(환불하되 좌석은 유지)은 seat가 티켓 사용 여부를 알아야 해 이벤트 계약 재설계나 seat→ticket 동기 호출이 필요하고, 그렇게 해도 "돈은 돌려받고 공연은 이미 봤다"는 결과가 남는다. 입장한 예매를 환불하지 않는다는 결정은 그 결과 자체를 부정한다.
  - **검사 대상은 환불이 성사될 수 있는 예매뿐이다.** 환불 개시는 `requestRefund()`가 성공할 때만 일어나고, 그것은 **취소 유스케이스의 트랜잭션 시점**에 `CONFIRMED`일 때뿐이다. 가드는 그 트랜잭션 밖에서 먼저 읽으므로 두 읽기 사이에 상태가 바뀌는 창이 있다 — 실제로 `recordRefundFailure`(#391)가 `REFUNDING`을 `CONFIRMED`로 되돌린다. 가드가 `REFUNDING`을 그냥 통과시키면 그 창에서 **티켓 검사를 한 번도 받지 않은 USED 예매가 환불을 개시**한다. 그래서 가드 시점에 `CONFIRMED`이거나 `REFUNDING`이면 검사한다 — `CONFIRMED`로 복원될 수 있는 상태가 `REFUNDING`뿐이므로 이 둘만 보면 창이 닫힌다. `CANCELED`·`EXPIRED`·`REFUNDED`·`PENDING`은 `CONFIRMED`로 돌아오는 경로가 없어 환불이 성사될 수 없으므로 검사하지 않는다(불필요한 왕복과 오해성 409를 없앤다).
  - **REFUNDING 고착 재발행(#397)만은 막지 않는다.** 그것은 새 환불의 개시가 아니라 *이미 진행 중인 환불의 복구*다. 여기를 막으면 `REFUNDING`을 빠져나올 유일한 수단이 사라져, 이 ADR이 제거한 흡수 상태가 그대로 되살아난다(돈은 이미 나갔을 수 있고, 좌석은 SOLD로 묶이고, 어떤 API로도 되돌릴 수 없다). 대신 그 예매의 입장권이 이미 `USED`라면 재발행 성공 시 좌석이 반환되므로 `[CRITICAL]` 로그로 운영자에게 알린다. 이때 티켓 조회가 **어떤 예외로 실패해도** 복구는 그대로 진행한다 — 가시화를 못 했다고 복구까지 막으면 다시 흡수 상태이기 때문이다. 관측을 고착 판정(`isStuckInRefunding`) 뒤에 둔 것도 같은 이유다. `REFUNDING`을 스쳐가는 모든 경로에 `[CRITICAL]`을 붙이면 사용자가 취소를 반복하는 것만으로 알림을 무한히 유발할 수 있다.
  - (남는 창) 고착 복구 경로에는 위 복원 창이 그대로 남는다. 가드가 고착 `REFUNDING`을 통과시킨 뒤 `CONFIRMED`로 복원되면 재환불이 티켓 검사 없이 진행된다. 고착 복구를 살리려면 불가피하며, 그 예매가 `USED`였다면 `[CRITICAL]`이 이미 남는다.
  - **흡수 상태를 되살리지 않는다.** 차단당한 예매는 `CONFIRMED`에 머물고 상태 전이가 일어나지 않는다. 사용자는 409를 받을 뿐 어떤 상태에도 갇히지 않는다.
  - **왜 동기 조회인가.** 입장은 되돌릴 수 없는 행위라 권위 있는 최신 상태로 판정해야 한다. 이벤트로 복제한 사본은 stale할 수 있어(입장 직후 취소) 차단의 보증을 약화시킨다. `EntryVerifyUseCase`가 입장 시 `bookingStatus`를 동기 조회하는 것(#364)과 같은 논리다.
  - **소유권을 먼저 검증한다.** 가드가 소유자 조건 없이 예매를 조회하면, 비소유자가 취소를 시도해 응답 코드(409 vs 404)로 타인 예매의 존재와 입장 여부를 알아낼 수 있다. 사용자 경로는 `findByBookingNumberAndUserId`로 조회하고, 소유권 검증이 없는 관리자 경로만 별도 진입점(`executeForAdmin`)을 쓴다.
  - (트레이드오프) **순환 동기 의존이 생긴다.** `ticket → booking`(입장 검증)에 더해 `booking → ticket`(취소 검증)이 추가됐다. 두 경로 모두 저빈도 읽기이고 시간대도 겹치지 않지만(취소는 공연 전, 입장은 공연 직전), 양쪽 스레드 풀이 동시에 포화되면 분산 데드락 여지가 있다. 타임아웃으로 풀린다. 검증은 `@Transactional` **밖**에서 수행해, ticket-service 지연이 booking의 DB 커넥션 풀을 물어 예매 경로 전체를 마비시키는 것은 막았다(잔여 위험은 DB 커넥션이 아니라 톰캣 스레드다).
  - (트레이드오프) **check-then-act 창이 남는다.** 검증 통과와 `requestRefund()` 커밋 사이(수 ms)에 입장이 끼어들면 `REFUNDING` + `USED` 조합이 만들어지고, 환불이 성사되면 좌석이 반환된다. 완전한 상호배제는 분산 락이 필요해 도입하지 않았다. 이 조합은 위 `[CRITICAL]` 로그로 드러난다. 역방향(REFUNDING 진입 후 입장)은 `EntryVerifyUseCase`가 이미 막는다.
  - (트레이드오프) **ticket-service 장애 시 취소가 실패한다.** 조회 실패는 `BOOKING_TICKET_COMMUNICATION_FAILED`(503)로 전파해 취소를 거부한다 — **알 수 없으면 막는다.** 좌석은 SOLD로 남고 사용자는 재시도하면 되므로 안전한 방향의 실패다. 단 고착 복구 경로에서는 이 원칙을 적용하지 않는다(위 참고).
  - **환불을 허용하는 분기는 최소한으로 좁힌다.** 이 가드에서 "통과"로 가는 길이 곧 정책이 뚫리는 길이므로, 판정할 수 없는 응답은 전부 막는다. `false`(미입장)를 반환하는 경우는 **`TICKET_404_001`로 응답한 404**(티켓 발급은 결제 완료 이벤트 이후라, CONFIRMED이면서 티켓 행이 아직 없는 상태가 실재한다 — 그 예매는 입장이 불가능하므로 취소를 막아선 안 된다)와 **`UNUSED`/`CANCELED` 상태** 둘뿐이다. 그 밖의 404(경로 오설정·라우팅 오류)와 알 수 없는 상태 문자열은 막는다 — 전자를 통과시키면 배포 사고 하나로 정책이 소리 없이 무력화되고, 후자를 통과시키면 ticket-service가 `TicketStatus`에 값을 추가하는 순간 booking이 컴파일 에러 없이 뚫린다. `EntryVerifyUseCase`가 `CONFIRMED`만 화이트리스트로 통과시키는 것과 같은 규율이다.
  - (운영 주의) `InternalApiTokenFilter`는 기대 토큰이 비어 있으면 모든 내부 호출을 403으로 거절한다. ticket-service에 `custom.security.internal-token`이 선언돼 있지 않으면 **취소·재환불이 전부 503으로 실패**한다. 컨트롤러 슬라이스 테스트는 토큰을 주입하므로 이 공백을 잡지 못해, 설정 파일을 직접 읽는 `InternalApiTokenConfigTest`를 뒀다. 같은 이유로 booking의 `service.ticket.url`도 prod에서 기본값 없이 선언해 fail-fast 시킨다 — `localhost` 기본값을 남기면 컨테이너가 자기 자신을 호출해 취소가 전부 503이 되는데, **기동은 성공해 조용히 깨진다.**
  - (운영 주의) 취소 경로의 입장권 판정에는 공용 HTTP 예산(3s/10s)이 아니라 전용 타임아웃(1s/2s)을 쓴다. 판정에 필요한 건 단일 행 조회 하나인데, ticket-service가 느려질 때 취소 요청이 톰캣 스레드를 13초까지 붙잡으면 예매 경로 전체가 함께 마른다.
- (확장 · #492) **`refundFailedAt`이 "취소를 요청했던 건"만 뜻하지 않게 됐다.** 이 ADR은 환불 실패를 `REFUNDING → CONFIRMED` 복원으로만 다뤘고, 그래서 `Booking.recordRefundFailure`도 `REFUNDING`이거나 이미 복원된 건(`CONFIRMED` + `refundFailedAt`)만 받았다. 그런데 좌석 확정 실패 보상(#492)은 **사용자 취소가 아니라 시스템이 자기 사고를 되돌리는 경로**라, 예매 상태를 바꾸지 않고(refund-first) 곧바로 환불을 건다. 그 환불이 PG에 거절되면 `CONFIRMED`이면서 `refundFailedAt`이 없는 채로 실패가 도착하는데, 기존 분기 어디에도 걸리지 않아 **실패가 기록되지 않았다.**

  기록이 없으면 이 ADR이 만든 복구 수단 두 개가 함께 막힌다 — 미해결 목록(`CONFIRMED` + `refund_failed_at IS NOT NULL`)에 잡히지 않고, 관리자 재환불 API가 `BOOKING_REFUND_RETRY_NOT_ALLOWED`로 거부한다. 과금이 남은 건에서 그건 가장 필요한 도구다. 그래서 `CONFIRMED` 분기를 열어 **상태 전이 없이 실패 시각만 기록**한다.

  - **기존 경로에 회귀는 없다.** `RefundFailedEvent`의 발행 지점은 `RefundRequestedEventListener` 하나뿐이었고 그 경로는 항상 `REFUNDING` 건이라, 이 분기는 지금까지 도달 불가능했다. #492가 첫 도달자다.
  - **미해결 목록의 의미가 넓어진다.** "취소 실패 건"에서 "취소 실패 건 + 보상 환불 실패 건"이 됐다. 후자는 사용자가 취소를 요청한 적이 없는데도 목록에 오르며, 과금이 남아 있으므로 우선 처리 대상이다. 사용자 노출 필드(`BookingSummaryResponse.refundFailedAt`)의 설명도 함께 고쳤다.
  - **새 분기의 stale 정책은 "갱신하지 않음"이다.** `REFUNDING` 분기와 달리 시각 비교를 하지 않고, `refundFailedAt`이 이미 있으면 그대로 둔 채 `true`를 반환한다. 최초 기록 시점이 곧 미해결 진입 시각이라 재전달로 밀리면 안 되기 때문이다.
  - (알려진 한계 · 위 "이벤트 재생" 항목의 확장) 보상 신호는 booking 상태와 무관하게 발화하므로, ① 보상 환불이 거절돼 `refundFailedAt=t1` ② 관리자가 재환불을 걸어 `REFUNDING` ③ 그 사이 중복 보상 신호가 도착해 또 거절되면 `RefundFailedEvent(t2)`가 나가고 ④ `t2 > t1`이라 stale 가드를 통과해 **진행 중인 재환불 시도를 `CONFIRMED`로 되돌린다.** 상태는 여전히 `CONFIRMED`/`REFUNDED`로 수렴해 고착은 없으나, #492가 두 번째 트리거를 추가하면서 이 레이스의 발생 빈도가 올라간다. 위 항목이 남긴 후속 과제(시도 식별자로 매칭)가 이 케이스도 함께 해소한다.

- (후속 과제 · #416) **결제 취소 API(`POST /api/v1/payment/{paymentId}/cancel`)로는 여전히 착석 좌석이 반환된다.** #399가 닫은 것은 booking을 거치는 두 경로뿐이다. 이 API는 booking을 거치지 않고 `payment.status == COMPLETED`만 보고 PG를 취소한 뒤 `PaymentCanceledEvent`를 발행하므로, 입장 완료 예매에도 그대로 동작해 `SeatReleaseSoldSeatUseCase`가 좌석을 반환한다. (#392가 지적했던 "예매 상태 미고려"는 `REFUND_FAILED` 제거로 해소돼 NOT_PLANNED로 닫혔으나, "티켓 USED 미고려"는 그 축이 아니라 남아 있다.)
