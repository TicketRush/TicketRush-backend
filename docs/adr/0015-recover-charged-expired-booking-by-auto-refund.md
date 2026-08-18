# 15. 확정 신호가 유실돼 과금만 남은 만료 예매는, 예매를 되살리는 대신 대조로 찾아 자동 환불한다

날짜: 2026-08-17

## 상태

승인됨

## 맥락

`PaymentConfirmUseCase`는 PG 승인에 성공하면 `PaymentConfirmedEvent`를 발행한다. payment-service는 `app.event-publisher.type: kafka`라 이 발행은 fire-and-forget이고, 실패는 카운터와 로그로만 남는다([ADR 0014](0014-recover-refund-failure-signal-by-reconciliation.md)).

이 이벤트의 구독자는 둘이다 — booking(예매 CONFIRMED 전이)과 ticket(티켓 발급). 유실되면 booking이 PENDING에 남는데, **거기서 멈추지 않는다.** `BookingExpirationScheduler`가 1분마다 돌며 `createdAt + 5분`을 넘긴 PENDING을 EXPIRED로 만든다.

최종 상태는 이렇다.

| | 상태 |
|---|---|
| payment | **COMPLETED (과금 완료)** |
| booking | EXPIRED |
| ticket | 미발급 |
| seat | 해제 → 재판매 |
| 환불 | **없음** |
| 관리자 미해결 목록 | **안 잡힘** |

`BookingExpiredEvent`의 구독자는 payment 하나뿐이고, 하는 일은 `registerExpiredBooking` — 만료 booking을 등록해 이후 confirm을 차단할 뿐 환불하지 않는다([#224](https://github.com/TicketRush/TicketRush-backend/issues/224)).

### 착수 전 조사에서 이슈의 전제 세 가지가 코드와 달랐다

**1. "사용자가 손댈 방법이 없다"는 사실이 아니다.** `PaymentCancelUseCase.execute`는 본인 결제·`COMPLETED`·티켓 USED 여부만 보고 **booking 상태를 전혀 확인하지 않는다.** 결제 내역에도 그대로 뜨므로 사용자가 직접 환불할 수 있다.

**2. 🔴 그런데 그 취소 경로가 남의 좌석을 푼다.** `PaymentCancelUseCase`는 `PaymentCanceledEvent`를 `bookingNumber = null`로 발행하고(API 경로는 예매번호를 모른다는 주석이 의도를 명시한다), seat의 `SeatReleaseSoldSeatUseCase`는 그 값이 있을 때만 좌석 소유 교차검증(ABA 방지)을 한다. null이면 검증이 통째로 꺼지고 "SOLD면 반환"만 남는다. 좌석이 이미 다른 사용자에게 팔린 뒤라면 **그 사람의 좌석이 AVAILABLE로 돌아간다.** seat 리스너의 Inbox는 재전달만 막으므로 최초 수신인 이 시나리오는 그대로 통과한다. → [#608](https://github.com/TicketRush/TicketRush-backend/issues/608)로 분리했다.

**3. 재결제가 막히는 이유가 이슈 서술과 다르다.** `expired_booking` 가드([#224](https://github.com/TicketRush/TicketRush-backend/issues/224))가 아니라 그보다 앞선 `PaymentConfirmUseCase`의 COMPLETED 중복 검사에 걸린다. 실제 응답은 `PAYMENT_ALREADY_COMPLETED`다.

또한 **[ADR 0014](0014-recover-refund-failure-signal-by-reconciliation.md) "다루지 않은 것"의 "booking이 PENDING으로 고착된다"는 서술은 부정확하다** — 5분 뒤 EXPIRED로 전이된다. 고착이 아니라 소멸이며, 그래서 복구가 더 어렵다.

### #574의 방식을 복제할 수 없다

[ADR 0014](0014-recover-refund-failure-signal-by-reconciliation.md)는 "전달을 보장하는 대신 남아 있는 상태로 재발행한다"를 세웠고, 안전성의 근거를 **이미 배포된 수신 측 계약**에 위임한 것이 핵심이었다. 여기서 같은 일을 하면 **아무 일도 일어나지 않는다.**

`PaymentConfirmedEvent`를 재발행해도 `Booking#confirm`이 EXPIRED에 대해 `BOOKING_EXPIRED`로 거절하고, 그 예외는 영구 실패로 분류돼 그대로 ack된다. 위임할 계약이 여기서는 **반대 방향으로 작동한다.** 좌석이 이미 팔린 뒤라 예매를 되살리는 것 자체가 불가능하기도 하다.

대상 집합의 성질도 다르다. #574에는 `refund` 테이블의 FAILED 이력이라는 미해결 목록이 이미 있었고, 해결되면 `markCompleted`로 스스로 빠졌다. `PaymentConfirmedEvent`에는 그런 축이 없다 — COMPLETED payment 전부가 대상이 될 수는 없다.

### 검토한 대안

**판정 기준**

- **(a) payment에 "확정 신호 전달됨" 축을 새로 둔다.** 대상 집합이 명확해지지만 스키마 변경이고, prod가 `validate` 모드라 수동 DDL이 따라온다. 무엇보다 이미 발생한 과거 사고 건은 그 컬럼이 없어 영영 잡히지 않는다.
- **(b) COMPLETED payment 전건을 booking에 동기 조회해 대조한다.** 판정은 정확하지만 결제 건수에 비례해 왕복이 늘고, 정상 건이 압도적 다수라 대부분이 헛돌이다.
- **(c) 채택 — `expired_booking × payment(COMPLETED) × refund 부재`로 로컬 후보군을 좁힌 뒤, 후보만 booking에 재확인한다.** 세 테이블이 모두 payment 안에 있어 왕복 없이 후보를 만들고, 되돌릴 수 없는 부작용 앞에서만 원본을 다시 본다.

**복구 동작**

- **(가) 예매 복구.** 좌석이 이미 재판매된 뒤라 성립하지 않는다. 되살릴 자리가 없다.
- **(나) 채택 — 자동 환불.** [#492](https://github.com/TicketRush/TicketRush-backend/issues/492)가 만든 `PaymentRefundByBookingUseCase`를 그대로 재사용한다.

## 결정

**`expired_booking`을 커서로 훑어 "과금됐는데 예매가 만료됐고 환불 이력이 없는" 건을 찾고, booking에 재확인한 뒤 자동 환불한다. 스케줄러는 기본 비활성으로 배포한다.**

### 판정 질의는 2단계로 나눈다

```
1) SELECT eb FROM ExpiredBooking eb WHERE eb.id > :cursor ORDER BY eb.id ASC LIMIT :batch
2) SELECT p FROM Payment p WHERE p.bookingId IN (:ids) AND p.status = COMPLETED
3) 후보별 순차 판정
```

조인 한 방 + `LIMIT`으로 짜면 안 된다. "과금됐는데 만료된" 건은 전체 만료 중 극히 드물어서 옵티마이저가 상한을 채우려 스캔을 이어가고, 매 주기 사실상 풀스캔이 된다 — [ADR 0014](0014-recover-refund-failure-signal-by-reconciliation.md)가 `idx_refund_status`에서 겪은 것과 같은 형태다. 2단계로 나누면 1)이 PK 범위 스캔, 2)가 `idx_payment_booking_id`([#412](https://github.com/TicketRush/TicketRush-backend/issues/412))를 탄다.

세 번째 질의는 판정 1의 환불 이력 조회인데, **`refund`에는 `booking_id` 인덱스가 없다**(키는 PK · `UNIQUE(payment_id)` · `idx_refund_status` 셋뿐). 그래서 이것도 후보별 호출이 아니라 `booking_id IN (...)`으로 묶는다. 건별로 부르면 후보 수만큼 풀스캔이고, `refund`는 성공한 환불까지 누적돼 계속 커지는 테이블이며, 하필 후보가 가장 많은 순간이 처음 켜는 첫 랩이다. 묶으면 풀스캔이 **랩당 1회**로 고정돼 비용이 후보 수에 비례하지 않는다.

그 결과 **새 인덱스가 필요 없다. 스키마는 무변경이다.** 인덱스를 추가하는 선택지도 있었지만, prod가 `validate` 모드인데 **Hibernate `validate`는 인덱스를 검증하지 않아** 수동 DDL을 빠뜨려도 기동이 성공한다 — [ADR 0014](0014-recover-refund-failure-signal-by-reconciliation.md)가 `idx_refund_status`에서 정확히 그 함정을 남겼다. 질의 형태를 바꿔 피할 수 있다면 그편이 낫다.

### 판정 순서 자체가 계약이다

| # | 판정 | 통과하지 못하면 | 왜 이 자리인가 |
|---|---|---|---|
| 1 | 환불 이력 부재 | `already_refunded` / `refund_failed_history` | **PG 무한 재호출 차단의 핵심.** 상태를 가리지 않고 거르는 것이 의도적이다 — FAILED 이력도 "이미 시도했다"는 뜻이라, 상태를 가려 다시 집어들면 실패한 환불을 매 주기 PG에 되풀이해 던진다. 다만 **관측에서는 반드시 가른다**(아래 참고) |
| 2 | 만료 후 grace(30분) 경과 | `grace` | 정상 처리가 늦게 도착하는 창을 환불로 덮지 않는다 |
| 3 | booking 동기 재확인 | `booking_lookup_failed` | 로컬 근거는 이벤트로 채워진 사본이라 최신이 아닐 수 있다. 404·503은 물론 **그 밖의 예외까지** 여기로 접는다 — 새어 나가면 그 랩의 남은 후보와 발견 카운터가 통째로 죽어, 장애 구간에서 관측이 침묵한다 |
| 4 | 상태가 EXPIRED·CANCELED | `booking_alive` / `booking_status_unknown` | 허용 목록이다. **알려진 비허용 상태와 모르는 문자열을 가른다** — 후자는 booking이 상태 이름을 바꿨다는 뜻이고, 그러면 전건이 이 갈래로 떨어져 복구가 조용히 전면 정지한다([#572](https://github.com/TicketRush/TicketRush-backend/issues/572)가 `owner_unknown`을 분리한 것과 같은 판단) |
| 5 | `bookingNumber` 존재 | `booking_number_unknown` + CRITICAL | **위 정정 2의 사고를 원천 차단한다.** 값 없이 환불하면 이 경로가 곧 #608이 된다(사용자 취소 API 경로의 같은 구멍은 [ADR 16](0016-require-booking-number-for-refund-seat-release.md)에서 닫았다). 가드를 호출부가 아니라 **환불 실행 메서드 안**에 둔 것은, `bookingId`·`bookingNumber`가 `Long`·`String`이라 인자를 잘못 넘겨도 컴파일이 통과하기 때문이다(`RefundTrigger`를 열거형으로 뽑은 것과 같은 이유) |
| 6 | 자동 환불 실행 | — | `RefundTrigger.CONFIRM_SIGNAL_LOST` |

**판정 1의 두 태그를 가르는 것이 특히 중요하다.** 둘 다 "환불 이력이 있어 건너뛴다"이지만 뜻이 정반대다 — `refund_failed_history`는 **PG가 거절해 돈이 돌아가지 않은 미해결 사고**이고, 예매가 EXPIRED라 booking의 관리자 재환불 게이트도 열리지 않는다. 한 태그로 접으면 그 건이 정상 환불 시계열에 섞여, CRITICAL 로그 한 줄이 흘러간 뒤로는 영영 보이지 않는다. 아래 "관리자 창구가 로그와 메트릭뿐"이라는 한계를 감수하는 이상, 그 메트릭이 두 상태를 구분하지 못하면 감수의 전제가 무너진다.

**티켓 USED 가드는 통과하지 않는다.** 결제 취소 API 경로는 [#416](https://github.com/TicketRush/TicketRush-backend/issues/416)이 세운 그 가드를 거치지만 이 자동 경로는 거치지 않는다. 입장 검증이 예매 CONFIRMED를 요구하므로 EXPIRED·CANCELED 예매의 티켓은 USED가 될 수 없고, [#492](https://github.com/TicketRush/TicketRush-backend/issues/492)도 같은 이유로 가드를 넣지 않았다. 사람 개입 없이 되돌릴 수 없는 취소를 실행하는 경로가 기존 안전 게이트를 우회한다는 사실 자체는 기록해 둔다.

CANCELED를 허용 목록에 넣은 것은 사용자가 만료 전에 취소했는데 확정 신호만 유실된 경우도 결과가 같기 때문이다 — 과금이 남고 예매가 없다.

`ALREADY_SETTLED`는 여기서 정합성 붕괴가 아니다. 후보를 뽑은 뒤 환불하기까지 사이에 다른 경로가 정리했을 수 있다. 신호를 받아 처리하는 `SeatConfirmFailedEventListener`에서는 같은 값이 "과금됐다"는 전제의 붕괴라 CRITICAL이지만, **대조로 후보를 찾는 이 경로에서는 경합의 정상적 귀결이다** — 심각도 해석이 갈린다.

### 배치 상한의 중단 가드를 복제하지 않는다

#574는 "한 건도 못 내보낸 채 3회 연속 실패하면 이번 주기를 접는다"를 뒀다. 거기서 연속 실패는 브로커 전면 장애라 남은 건도 같은 이유로 실패할 것이 확실했기 때문이다.

**여기서는 그 가드가 해롭다.** 연속 실패의 정체가 특정 건의 PG·booking 왕복 실패라, 커서를 두고 멈추면 선두의 실패한 몇 건이 뒤쪽 사고 건 전체를 무기한 굶긴다. 그건 #574가 `MAX_CONSECUTIVE_FAILURES`에 "성공 0건" 조건을 함께 건 이유였던 봉쇄와 같은 형태다. **실패해도 커서는 전진한다.**

### ShedLock을 쓰지 않는다 — 근거가 #574와 다르다

[ADR 0014](0014-recover-refund-failure-signal-by-reconciliation.md)의 근거는 "읽기와 발행뿐이라 전이할 상태가 없다"였다. **이 작업에는 PG 환불이라는 되돌릴 수 없는 부작용이 있으므로 그 근거를 그대로 쓸 수 없다.**

그럼에도 락을 두지 않는 근거는 중복 실행이 이중 환불로 번지지 않게 막는 방어선 셋이 이미 있다는 것이다.

| 방어선 | 위치 |
|---|---|
| PG 멱등키가 `paymentId`로 고정 (`REFUND-%07d`) | `PaymentRefundByBookingUseCase` |
| `refund.payment_id` unique | `Refund` 엔티티 ([#296](https://github.com/TicketRush/TicketRush-backend/issues/296)) |
| `markCanceled`가 COMPLETED가 아니면 예외 | `Payment` 엔티티 |

판정 1의 `existsByBookingId` 선검사는 이 셋에 얹는 **PG 호출 억제일 뿐 정합성 근거가 아니다.** 조회와 취소 사이의 경합은 막지 못한다. 순서를 뒤집거나 선검사만 믿으면 안 된다.

ShedLock을 들이려면 `RedisLockProvider`가 필요하고, 그러려면 payment가 명시적으로 끈 Redis 오토컨피그([#425](https://github.com/TicketRush/TicketRush-backend/issues/425))를 되살려 이 서비스를 [ADR 0008](0008-accept-redis-spof-with-fail-closed.md)의 장애 범위에 새로 넣어야 한다.

### 기본 비활성으로 배포한다

`ReplayRefundFailureSignalScheduler`(#574)가 `matchIfMissing = true`인 것과 다르다. 차이는 부작용의 성질이다 — 그쪽은 메시지를 한 번 더 보내는 것이고, 이쪽은 **돈을 돌려보내는 것**이다. 켜는 순간 첫 랩이 과거에 쌓인 사고 건을 전량 환불하므로 잔량을 실측하고 켠다([#441](https://github.com/TicketRush/TicketRush-backend/issues/441)).

**랩당 PG 호출 상한(`max-refunds-per-lap`, 기본 50)을 배치 상한과 별도로 둔다.** 배치 상한은 만료 테이블을 훑는 크기이고 이쪽은 실제 결제 취소 호출 수다. 나누는 이유는 Toss에 429가 없고 대신 연속 요청 제한(403 `FORBIDDEN_CONSECUTIVE_REQUEST`)이 오는데, `TossCancelRetryableCode`의 javadoc이 **"가장 나오기 쉬운 구간이 대량 보상 환불 버스트"**라고 적어 둔 그 상황이 바로 첫 랩이기 때문이다. 거기 걸리면 [#573](https://github.com/TicketRush/TicketRush-backend/issues/573)의 인라인 재시도까지 같은 이유로 소진돼 `PAYMENT_REFUND_FAILED`로 확정되고, FAILED 이력이 남은 그 건은 판정 1에 걸려 **자동 복구에서 영구히 빠진다.** 되돌릴 수 없는 부작용을 다루는 경로에서 가장 나쁜 결말이라, 완화를 운영 규율("사람이 batch-size를 낮춰 켠다")에 맡기지 않고 코드에 둔다. 상한에 도달하면 커서를 그 자리에 두고 조기 종료하므로 다음 주기가 곧바로 이어받는다.

세 값(`batch-size`·`grace-minutes`·`max-refunds-per-lap`) 모두 **0 이하면 기동에 실패시킨다.** 0은 "제한 없음"이 아니라 고장이다 — `batch-size=0`은 `PageRequest.of(0, 0)`이 매 주기 예외를 던져 영구 무동작이 되고, `grace-minutes=0` 이하는 판정 2를 통째로 무력화한다. 어느 쪽도 로그를 보지 않으면 드러나지 않아 배포 시점에 터뜨린다([#573](https://github.com/TicketRush/TicketRush-backend/issues/573)의 "대기값 0은 대기 생략이 아니라 킬 스위치여야 한다"와 같은 축).

```sql
SELECT eb.booking_id, p.payment_id, p.amount, eb.expired_at
FROM expired_booking eb
JOIN payment p ON p.booking_id = eb.booking_id AND p.status = 'COMPLETED'
LEFT JOIN refund r ON r.booking_id = eb.booking_id
WHERE r.refund_id IS NULL;
```

### 관측

`charged_expired.detected` / `.recovered` / `.skipped`(reason 태그 7종) 세 카운터를 둔다. 환불 결과 자체는 기존 `PAYMENT_REFUND`·`PAYMENT_REFUND_FAILED`에 `trigger` 태그로 실린다.

**Gauge는 두지 않는다.** 잔량을 정확히 세려면 만료 테이블을 매번 전건 훑어야 하는데 그 풀스캔이 바로 이 설계가 피한 것이다. `detected − recovered`를 Grafana에서 겹쳐 읽는다.

## 결과

### 좋아지는 것

- **과금만 남은 사고 건이 자동으로 정리된다.** 유실 원인과 무관하다 — Kafka 유실이든 booking 다운이든 상태로 남으면 잡힌다.
- **스키마가 무변경이다.** 새 인덱스도 없어, #574가 밟은 "`validate`는 인덱스를 검증하지 않아 수동 DDL 누락이 조용히 지나간다"는 함정을 아예 만들지 않는다.
- **결제 확정 경로를 한 줄도 건드리지 않는다.** cross-domain 변경은 booking `BookingInternalResponse`에 `bookingNumber` 한 필드 추가뿐이고 가법적이다.
- 롤백이 설정 한 줄이다(`app.charged-expired-recovery.enabled: false`, 재기동 필요). 되돌릴 DDL이 없다.
- 판정 5가 [#608](https://github.com/TicketRush/TicketRush-backend/issues/608)의 좌석 오반환을 이 경로에서는 원천 차단한다. 배포 순서가 역전돼 booking이 아직 필드를 내려주지 않아도 같은 가드가 막는다.

### 나빠지는 것 / 남는 한계

- **되돌릴 수 없는 부작용을 스케줄러가 자동으로 실행한다.** 판정이 틀리면 정상 결제가 환불된다. 그래서 판정을 fail-closed로 짜고 기본 비활성으로 배포하지만, 위험 자체가 사라지지는 않는다.
- **관리자 창구가 로그와 메트릭뿐이다.** `Booking#recordRefundFailure`가 EXPIRED·CANCELED에서 아무것도 하지 않아 **`refund_failed_at`이 구조적으로 채워질 수 없고**, 그래서 이 경로는 `RefundFailedEvent`를 발행하지 않는다 — 발행해도 게이트는 열리지 않고 열렸다는 착각만 남는다. payment 전용 관리자 조회 API는 후속 이슈다.
- **첫 랩의 폭발 반경.** 과거 사고 건을 전량 환불한다. CS가 PG 콘솔에서 수동 환불한 건은 payment가 COMPLETED로 남아 있어 중복 취소를 시도하게 된다. 이때 이중 환급을 막는 것은 **우리 멱등키가 아니다** — 콘솔 취소는 그 키를 쓴 적이 없다. 막는 것은 Toss가 이미 취소된 건을 거절한다는 사실이고, 그 결과 우리 쪽에는 `PAYMENT_REFUND_FAILED` → FAILED 이력이 남는다(멱등키 논거는 위 "동시 실행" 문단에서만 유효하다).
- **환불이 성공해도 booking은 EXPIRED에 그대로 남는다.** `Booking#markRefunded`는 CONFIRMED·REFUNDING·REFUNDED에서만 참이라 EXPIRED·CANCELED 예매는 REFUNDED로 전이하지 못하고, `BookingMarkRefundedUseCase`가 그때마다 "환불 예매 종결 스킵" WARN을 남긴다. 귀결이 둘이다 — 사용자·CS가 보는 예매는 계속 "기한 만료"인데 결제는 환불 완료라 두 도메인의 표시가 어긋나고, **복구가 성공할 때마다 booking에 경고가 찍혀** 첫 랩에서는 백로그 건수만큼 쏟아진다(성공 신호가 경고로 보이는 역전). 예매 상태 머신에 "만료 후 환불됨"을 새로 만드는 것은 booking 도메인 변경이라 이번 범위 밖으로 두었고, 그 대가를 여기에 적어 둔다.
- **환불 이력 조회가 루프 밖으로 나온 대가가 있다.** 후보별 호출을 `IN` 한 번으로 묶어 풀스캔을 랩당 1회로 줄인 대신, 그 질의가 지속적으로 실패하면(인덱스가 없으니 `refund`가 커졌을 때의 statement timeout이 현실적인 실패 모드다) **커서가 전혀 전진하지 않고 같은 페이지를 영구히 재시도한다.** 방향은 안전한 쪽이지만(잘못된 환불이 아니라 정지) 카운터는 전부 0이고 스케줄러 스택트레이스만 남으므로, 그 침묵을 사고 없음으로 읽으면 안 된다.
- **커서의 안전성이 "단일 호출자"라는 사실에만 기대고 있다.** `@Scheduled(fixedDelay)`가 같은 메서드의 실행을 겹치지 않게 해 주는 덕에 `volatile long cursor`의 read-modify-write가 인터리브되지 않는다. 관리자 수동 트리거 같은 두 번째 호출자를 붙이면 두 실행이 커서를 서로 덮어써 구간을 건너뛴다(다음 한 바퀴를 기다려야 한다). 다중 인스턴스에서는 위 방어선 셋이 이중 환불을 막지만, 그 대가로 FAILED 이력이 남는다.
- **`expired_booking`에 리텐션이 없다.** 해결돼도 행이 사라지지 않아 한 바퀴 길이가 누적 만료 건수에 비례해 늘어난다. 실제 복구 지연 상한은 주기가 아니라 `ceil(만료 건수 / batch-size) × interval`이다. 리텐션은 후속으로 남긴다.
- **`detected`가 매 랩 반복 카운트될 수 있다.** booking 조회 실패·예매번호 부재로 복구되지 못한 건은 환불 이력이 남지 않아 다음 랩에 다시 잡힌다. 누적값을 사고 건수로 읽으면 안 되고 `recovered`와의 차이로 읽어야 한다.
- **grace 30분은 근거가 실측이 아니다.** 만료 5분 + 여유로 잡은 값이라 운영 데이터로 재조정해야 한다.
- **알림 규칙은 여전히 없다.** #574가 남긴 한계가 그대로다. 카운터만으로는 대시보드를 열어야 참이다.
- **payment의 두 번째 `@Scheduled`다.** 스레드 풀이 2라 두 스케줄러가 같은 풀을 나눠 쓴다. 이 작업이 한 주기를 길게 물면 보상 신호 재발행이 밀린다.

### 다루지 않은 것

- [#608](https://github.com/TicketRush/TicketRush-backend/issues/608) 결제 취소 API의 좌석 오반환. 이 ADR의 경로는 판정 5로 막지만, **사용자가 직접 누르는 취소 경로는 그대로 열려 있다.** 이 결정이 사고 상태를 자동으로 정리하면 노출 빈도는 줄지만 사라지지는 않는다.
- payment 관리자 조회 API. 위 "관리자 창구" 한계를 닫는 후속이다.
- `expired_booking` 리텐션.
- payment 전면 outbox 전환. [ADR 0014](0014-recover-refund-failure-signal-by-reconciliation.md)의 (a)와 같은 이유로 트랜잭션 재설계가 선행돼야 한다.
