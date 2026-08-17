# 16. 환불 좌석 반환은 예매번호를 요구하고, 얻지 못하면 PG 취소 앞에서 취소를 끊는다

날짜: 2026-08-18

## 상태

승인됨

## 맥락

`PaymentCanceledEvent`의 `bookingNumber`는 seat가 좌석 소유를 교차검증(ABA 방지)하는 유일한 근거다. `SeatReleaseSoldSeatUseCase`의 가드는 그 값이 있을 때만 발화하도록 쓰여 있었다.

```java
if (bookingNumber != null && !bookingNumber.isBlank()
    && !Objects.equals(seat.getBookingNumber(), bookingNumber)) { ... 스킵 ... }
```

`null`이면 `&&` 단락평가로 조건 전체가 false가 되어 **"SOLD면 반환" 한 줄만 남는다.** 그리고 결제 취소 API 경로는 항상 `null`을 넣었다 — `PaymentCancelUseCase`가 `// API 취소 경로는 payment가 bookingNumber를 알지 못하므로 null로 발행한다(#91)`로 의도를 명시하고 있었다. **가드가 필요한 상황에서 정확히 꺼져 있었다.**

### 도달 조건은 "payment는 COMPLETED인데 그 좌석이 더 이상 이 예매의 것이 아닌" 상태 하나다

1. A가 좌석 S를 예매하고 결제 승인에 성공한다 (`payment.status = COMPLETED`)
2. 확정 신호 유실([#607](https://github.com/TicketRush/TicketRush-backend/issues/607))이나 즉시 취소로 예매가 확정되지 못하고, hold가 만료돼 S가 AVAILABLE로 풀린다. S는 A의 것으로 SOLD된 적이 없다
3. B가 S를 예매하고 결제를 완료한다 → **S는 SOLD, `seat.bookingNumber` = B의 예매번호**
4. A가 결제 내역에서 취소를 누른다. `PaymentCancelUseCase`는 본인 결제·COMPLETED·티켓 USED만 보고 booking 상태를 확인하지 않으므로 통과한다
5. `PaymentCanceledEvent(seatId = S, bookingNumber = null)` 발행 → 교차검증 스킵 → **B의 좌석이 AVAILABLE이 된다**

**B에게는 아무 신호도 가지 않는다.** 결제·발권을 마친 좌석이 빈자리로 돌아가 제3자에게 다시 팔릴 수 있다. seat 리스너의 Inbox는 재전달만 막으므로 최초 수신인 이 경로는 그대로 통과하고, 반환은 정상 로그(`환불 좌석 반환 완료`)로 찍혀 사후 탐지도 어렵다.

이 문제는 [ADR 15](0015-recover-charged-expired-booking-by-auto-refund.md)의 자동 환불 경로를 설계하다 발견됐고, 그쪽은 §판정 5에서 "값 없이 환불하면 이 경로가 곧 #608이 된다"며 예매번호 가드를 먼저 세웠다. 남은 것이 **사용자 취소 API 경로**다.

### 제약

- payment는 예매번호를 자신의 테이블에 갖고 있지 않다. `Payment` 엔티티에 해당 컬럼이 없어 로컬 조회로는 얻을 수 없다.
- 다만 배관은 이미 있다 — [#490](https://github.com/TicketRush/TicketRush-backend/issues/490)의 `BookingRestClient`와 #607이 응답에 추가한 `booking_number`가 그대로 재사용된다.
- 취소 API는 사용자 동기 요청이다. #607의 자동 복구는 "값이 없으면 이번 랩을 건너뛰고 다음에 다시" 할 수 있었지만, 여기서는 그 선택지가 없다.

## 결정

**환불 좌석 반환은 예매번호를 요구한다. payment는 그 값을 확보한 뒤에만 PG 취소로 넘어가고, seat는 값이 없는 반환 요청을 거부한다.**

### 1. 조회는 PG 취소 **앞**에 둔다

`PaymentCancelUseCase`가 booking 내부 조회로 예매번호를 확보한다. 위치는 티켓 USED 조회([#416](https://github.com/TicketRush/TicketRush-backend/issues/416)) 옆, **PG 취소 직전**이다. 조회 실패(404·503)와 "200이지만 예매번호가 빈" 경우 모두 취소 자체를 끊는다.

이 순서가 결정의 핵심이다. 실패 모드를 끝까지 따라가면 한쪽이 명백히 나쁘다.

| | 조회를 PG **앞**에 둘 때 | 조회를 PG **뒤**에 둘 때 |
|---|---|---|
| booking 장애 시 | 503, 환불 안 됨 | 환불은 성사됨 |
| 남는 상태 | 과금·예매·좌석 **전부 그대로** | **환불은 됐는데 좌석이 SOLD** |
| 복구 | 사용자가 재시도하면 끝 | `SOLD → AVAILABLE` 전이는 `releaseBooking()` 하나뿐인데 그 경로가 방금 스킵됐고, `SeatAdminForceReleaseHoldUseCase`는 SOLD를 `SEAT_SOLD_NOT_RELEASABLE`로 거부한다. **수동 DML 외에 없다** |

뒤에 두면 [#91](https://github.com/TicketRush/TicketRush-backend/issues/91)이 닫았다고 선언한 "환불됐는데 좌석이 SOLD로 남는" 역방향 공백이 되살아나고, 이번엔 복구 수단 없이 되살아난다. 재전달로도 풀리지 않는다 — seat는 스킵을 정상 return으로 ack하고, payment는 이미 CANCELED라 재취소가 멱등 반환으로 빠지며, [#574](https://github.com/TicketRush/TicketRush-backend/issues/574)의 신호 재발행은 FAILED 환불이 대상이라 COMPLETED 건을 집지 않는다.

**되돌릴 수 있는 실패를 고른다.** [ADR 11](0011-verify-booking-owner-at-caller.md) 원칙 3("판정할 수 없으면 판정 불가로 끊는다")과 같은 판단이며, #416이 티켓 조회에 대해 이미 같은 논증으로 같은 자리를 택했다("판정할 수 없는 응답은 모두 503으로 수렴시켜 좌석을 SOLD로 남기는 안전한 방향으로 전파한다").

**조회 결과는 예매번호 확보에만 쓴다.** `userId`·`bookingStatus`가 응답에 함께 오지만 쓰지 않는다. 특히 상태로 취소 가부를 판정하지 않는다 — 그렇게 하면 ADR 15가 "과금됐으나 만료된 예매는 환불해야 한다"고 결정한 것을 사용자 경로에서 뒤집게 되고, 같은 상태에 반대 정책 두 개가 생긴다.

### 2. seat는 값이 없는 반환 요청을 거부한다(킬 스위치 뒤에서)

`SeatReleaseSoldSeatUseCase`가 예매번호가 비면 반환하지 않는다. 이것이 안전한 이유는 **앱 경로에서 `SOLD ⇒ booking_number IS NOT NULL`이 불변식으로 성립**하기 때문이다 — SOLD 진입은 `SeatRepository.confirmSoldById` 하나뿐이고 그 WHERE가 `s.bookingNumber = :bookingNumber`라 NULL 행은 SQL 3값 논리상 매치되지 않으며, SET 절이 번호를 지우지 않는다. 즉 **정상 좌석에는 언제나 번호가 있으므로, 값이 빈 이벤트는 그 자체로 비정상 신호**다.

다만 거부는 `app.seat.refund-release.require-booking-number`로 감싸고 **기본값을 `false`로 둔다.** CD가 8개 서비스를 docker compose로 일괄 갱신해 payment와 seat의 교체 순서를 강제할 수 없고, 토픽에 아직 소비되지 않은 구버전 이벤트가 남아 있을 수도 있다. 그 구간에 가드가 켜져 있으면 **정상 취소의 좌석 반환까지 전부 막혀** 위 표의 "복구 불가" 상태를 대량으로 만든다.

켜는 순서가 곧 절차다 — payment 배포 확인 → `payment-canceled-topic` 컨슈머 랙 0 확인 → `SEAT_REQUIRE_BOOKING_NUMBER=true` 주입.

### 3. 관측은 스위치와 무관하게 항상 한다

스킵은 예외가 아니라 정상 return이라 리스너의 `[CRITICAL]` catch에 걸리지 않고 조용히 ack되며, `SEAT_SSE_EVENT_PUBLISHED`는 반환에 **성공**했을 때만 오른다. 기존 어느 시계열에도 나타나지 않으므로 카운터가 유일한 관측 축이다.

- seat `SEAT_REFUND_RELEASE_SKIPPED` — reason 4종(`seat_not_found` / `not_sold` / `booking_number_mismatch` / `booking_number_missing`)
- payment `PAYMENT_CANCEL_BOOKING_GUARD_BLOCKED` — reason 3종(`not_found` / `lookup_failed` / `booking_number_unknown`)

가드가 꺼져 있는 구간에도 `booking_number_missing`을 세는 것이 중요하다. **스위치를 켜도 되는지를 그 집계가 0인지로 판단**하기 때문이다. 태그를 가르는 규율은 ADR 11 원칙 4, ADR 15 §관측과 같다 — 정상 스킵(`not_sold`)과 조사 대상을 한 덩어리로 세면 사고가 멱등 스킵의 노이즈에 묻힌다.

## 결과

- **남의 좌석이 풀리는 경로가 닫힌다.** payment가 값을 채우므로 기존 교차검증이 정상 발화하고, seat 가드는 발행 측이 계약을 어겼을 때만 발화하는 이중 방어가 된다.
- **취소 API에 fail-closed 축이 하나 늘어난다.** booking 장애 시 취소가 전건 503이 된다. 새로운 성질은 아니다 — 같은 경로에 티켓 조회(#416)가 이미 있고, booking이 죽으면 결제 확정(#490)도 이미 전건 503이라 취소만 살아 있는 상황이 아니다. 서킷브레이커는 [#571](https://github.com/TicketRush/TicketRush-backend/issues/571)로 열려 있다.
- **취소 API 응답에 기존 코드가 새 경로로 등장한다.** `BOOKING_404_001`·`PAYMENT_503_003`은 신규 코드가 아니지만 취소에서는 나온 적이 없다. 프론트 공지 대상이다.
- **왕복 1회(최대 1000ms)가 붙는다.** 취소는 러시 경로가 아니라 감수한다. 이슈가 "hot path이므로 왕복이 정당한지" 물었지만, 예매 오픈에서 폭주하는 것은 좌석 선점과 결제 승인이지 취소가 아니다.
- **🔴 킬 스위치를 켠 뒤에는 좌석 고착이 발생할 수 있고, 앱 경로로 복구되지 않는다.** `booking_number_missing`이 0이 아니면 그 건의 좌석은 SOLD로 남으며 관리자 강제 해제도 거부된다. 복구는 `seat_status='AVAILABLE'`, `booking_number=NULL` 수동 DML뿐이다. **이 대가를 알고 켠다** — 대안인 오반환은 무고한 제3자의 좌석을 현장에서 이중 판매로 만들고, 그쪽은 되돌릴 방법조차 없다.
- **스위치를 언제 제거할지는 정하지 않았다.** `booking_number_missing`이 충분히 오래 0을 유지하면 가드를 상시화하고 프로퍼티를 걷어내는 것이 자연스럽지만, 그 판단에는 운영 데이터가 필요하다.
- **스키마는 바뀌지 않는다.** prod 수동 DDL 없음, 새 `ErrorStatus` 없음, 이벤트 필드 추가 없음(`bookingNumber`는 이미 있던 필드다). `bookingNumber`를 읽는 소비자가 seat 하나뿐이라 계약 변경의 파급도 없다.
- **부하 시드는 손대지 않았다.** `load-test/seed/seed_seat_counts.sql`이 `booking_number` 없이 SOLD를 INSERT해 위 불변식을 깨지만, 그 좌석들은 값 있는 이벤트에도 이미 `booking_number_mismatch`로 스킵되므로 이번 변경 전후의 동작이 같다.
