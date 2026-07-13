# 5. 환불 실패를 예매 상태가 아니라 타임스탬프로 기록한다

날짜: 2026-07-10

## 상태

승인됨

## 맥락

### 환불은 두 경로로 들어온다

```
경로 A · 결제 취소 API (#22)
  POST /api/v1/payment/{paymentId}/cancel
    → PaymentCancelUseCase → PG 취소 → PaymentCanceledEvent(bookingNumber=null)

경로 B · 예매 취소 Saga (#91)
  DELETE /api/v1/booking/{bookingNumber}
    → BookingCancelMyBookingUseCase: CONFIRMED → REFUNDING
    → RefundRequestedEvent
      → payment RefundRequestedEventListener → PaymentRefundByBookingUseCase → PG 취소
         ├ 성공 ────────────→ PaymentCanceledEvent(bookingNumber=값)
         └ PG 결정적 거절 ──→ RefundFailedEvent
```

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
- (마이그레이션) 마이그레이션 도구가 없고 prod는 `ddl-auto: validate`이므로(ADR [3](0003-shared-database-with-service-boundaries.md)), `infra/mysql/init/01-schema.sql` 갱신과 함께 기존 DB에는 수동 `ALTER`가 필요하다. 작성 시점 기준 아직 프로덕션 배포 전이라 `REFUND_FAILED` 행이 존재하지 않으므로 데이터 변환이 필요 없다. **먼저 그 전제를 확인한다.**

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
- (해소됨 · #397) ~~REFUNDING에서 멈춘 예매는 이 조회로 보이지 않는다~~ — payment 통신 실패로 DLT에 빠져 종결 이벤트가 끝내 오지 않으면 예매는 `REFUNDING`으로 남는다(돈도 못 받고, 좌석은 SOLD로 묶이고, 입장까지 차단). 이 사각지대에 두 수단을 추가했다. **식별**: `GET /api/v1/booking/admin/bookings/refunding-stuck`가 `REFUNDING`에서 임계 시간(기본 30분, `app.booking.refunding-stuck-threshold-minutes`) 이상 멈춘 예매를 오래된 순으로 노출한다. `REFUNDING` 진입 후 엔티티가 갱신되지 않으므로 `updatedAt`이 곧 진입 시각이다. 임계는 Kafka 재시도 소진(약 31초)보다 훨씬 길어 컨슈머 랙·재배포 지연을 오탐하지 않는다. **복구**: 환불 재시도 API가 고착 건(임계 초과 REFUNDING)이면 상태 전이 없이 `RefundRequestedEvent`만 재발행한다. booking이 자체 판단으로 `CONFIRMED` 복원하는 방식은 채택하지 않았다 — 통신 실패는 환불이 실제 성공했을 수도 있는 상태라, 재발행이 payment의 self-heal(COMPLETED면 PG 재실행, CANCELED면 `PaymentCanceledEvent` 재발행)과 맞물려 `REFUNDED` 또는 `CONFIRMED+refundFailedAt`로 안전하게 수렴하는 유일한 경로다. 임계 미달의 신선한 `REFUNDING`은 정상 진행 중이므로 여전히 거절한다. 재발행은 엔티티를 건드리지 않아 관리자 중복 호출을 낙관적 락이 막지 못하지만, payment 멱등성이 이중 환불을 막으므로 감사 로그로만 추적한다. 리컨실 스케줄러·메트릭 자동화는 도입하지 않았다 — DLT 적재 시 Slack 알림이 이미 1차 신호이고, 사람 판단 없는 자동 재발행은 무한 PG 재시도 위험이 있다.
- (후속 과제 · #399) **입장 완료(ticket=USED) 예매의 환불 시 좌석 재판매를 막을지 결정해야 한다.** 위 트레이드오프 참고. 기존 결함이나 이 변경으로 도달 경로가 늘었다.
- (후속 과제) payment-service의 결제 취소 API가 booking 상태를 고려하지 않는 문제와 관리자용 재환불 수단은 별도 이슈로 분리돼 있다.
