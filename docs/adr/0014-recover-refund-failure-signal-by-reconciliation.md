# 14. 환불 실패 보상 신호는 발행을 보장하는 대신, 남아 있는 상태로 재발행해 복구한다

날짜: 2026-08-13

## 상태

승인됨

## 맥락

payment-service는 `app.event-publisher.type: kafka`라 이벤트를 Kafka로 직접 발행한다. 발행은 fire-and-forget이고, 실패는 `log.error` 한 줄로 끝난다. 반면 booking·seat는 outbox 릴레이를 쓴다.

이 차이의 대가가 [#492](https://github.com/TicketRush/TicketRush-backend/issues/492)에서 커졌다. 좌석 확정 실패 보상 환불을 PG가 거절하면 payment는 `RefundFailedEvent`를 발행하고 끝나는데, 그 발행이 유실되면 booking에 `refundFailedAt`이 찍히지 않는다. 그러면:

- 미해결 목록(`CONFIRMED` + `refund_failed_at IS NOT NULL`)에 잡히지 않고
- 관리자 재환불 API가 `BOOKING_REFUND_RETRY_NOT_ALLOWED`로 거부한다

즉 **과금이 남은 사고 건에서 수동 복구 수단까지 함께 닫힌다**. 그 건은 사용자 취소와 달리 "다시 누를 사용자"가 없다.

### 이슈의 전제 하나가 코드와 달랐다

착수 전 조사에서 **PG 거절 시 `refund` 테이블에 FAILED 이력이 이미 독립 커밋으로 남는다**는 것을 확인했다(`FailedRefundRecorder`, [#334](https://github.com/TicketRush/TicketRush-backend/issues/334)). 과금 건이 흔적 없이 사라지지는 않는다.

따라서 실제 공백은 "기록의 부재"가 아니라 **기록과 복구 게이트 사이의 단절**이다. payment에는 그 기록이 있고 booking에는 없는데, 게이트는 booking 쪽만 본다.

이 구분이 중요한 이유는, 전달 보장을 아무리 올려도 게이트 단절이 남기 때문이다. 발행이 성공해도 booking이 다운돼 재시도가 소진되면 결과가 같다.

### 검토한 대안

- **(a) payment 전면 outbox 전환.** `OutboxEventPublisher`는 활성 트랜잭션을 강제하는데, payment의 발행 지점 여섯 곳은 전부 트랜잭션 밖이고 그것이 의도된 불변식이다 — "PG 왕복 동안 DB 커넥션을 잡지 않는다". 특히 `FailedRefundRecorder`는 *"호출자는 트랜잭션 밖이어야 FAILED 이력이 독립 커밋된다"* 고 못 박는다. 전면 전환은 **이 이슈가 지키려는 것 자체를 부순다**. 그 밖에 스케줄러 2종·ShedLock·Redis 오토컨피그 부활([#425](https://github.com/TicketRush/TicketRush-backend/issues/425) 역행)이 따라온다.
- **(b) 발행 결과를 확인하고 ack.** 실제로 막는 창이 좁다 — 브로커가 완전히 죽으면 오프셋 커밋도 실패해 재전달로 이미 self-heal된다. 남는 건 "오프셋은 커밋되는데 그 발행만 실패"하는 경우인데, 그 대가로 컨슈머 스레드를 최악 `delivery.timeout.ms`(120초)만큼 잡는다. 실패 시 리스너를 통째로 재실행하므로 PG를 다시 때리고, 최종 안착지인 DLT는 [ADR 0012](0012-absorb-transient-pg-refund-rejections-at-caller.md)를 낳은 [#573](https://github.com/TicketRush/TicketRush-backend/issues/573)에서 이미 "복구 창구가 아니다"로 결론났다.
- **(c) 발행 실패 관측만.** 카운터는 "몇 건 실패했다"만 말하고 어느 건인지 남기지 않아 게이트가 그대로 닫혀 있다.

## 결정

**`RefundFailedEvent`의 전달을 보장하는 대신, payment에 남아 있는 FAILED 환불 이력을 근거로 그 신호를 주기적으로 재발행한다.**

`status='FAILED'`인 `refund` row를 5분마다 배치 단위로 훑어(커서로 이어 읽으므로 한 바퀴는 `ceil(미해결 건수 / batch-size)` 주기가 걸린다) `RefundFailedEvent`를 **원본 실패 시각(`requested_at`)으로** 재발행한다. booking은 변경하지 않는다 — 이미 배포된 리스너가 받아 `refundFailedAt`을 채우고, 그러면 미해결 목록과 관리자 재환불 API가 열린다.

안전성의 근거를 새로 만들지 않고 **이미 배포된 계약**에 위임한 것이 이 결정의 핵심이다.

| 필요한 보장 | 어디에 이미 있는가 |
|---|---|
| 종결된 건을 되돌리지 않는다 | `Booking#recordRefundFailure` — REFUNDED/CANCELED면 전이 없이 `false` |
| 진행 중인 재환불을 중단시키지 않는다 | 같은 메서드 — REFUNDING이면 `failedAt`이 기록된 값보다 **나중일 때만** 복원. 재발행은 원본 시각을 실으므로 이 조건에 걸리지 않는다 |
| 반복 재발행이 낭비되지 않는다 | 수신 측 Inbox `(consumer_group, event_id)` — eventId를 refund별로 고정하면 두 번째부터 UseCase 실행 자체를 건너뛴다 |
| 대상이 무한히 쌓이지 않는다 | `Refund#markCompleted` — 재시도가 성공하면 FAILED가 COMPLETED로 전이돼 대상 집합에서 스스로 빠진다 |

한 주기가 읽는 건수에는 상한을 두되 **커서로 이어 읽는다.** 상한만 두고 매번 가장 오래된 것부터 읽으면, 미해결이 상한을 넘는 순간 뒤쪽 건은 영원히 선택되지 않는다 — FAILED 이력은 재환불이 성공해야 빠지는데 그 재환불을 여는 것이 바로 이 재발행이라 순환이 끊긴다. 상한의 존재 이유가 "대량으로 쌓였을 때"인데 정확히 그때 무력해지는 구조라 커서가 필요하다.

관측은 두 축으로 나눈다. `ticketrush.event.publish.failed`(발행이 실제로 유실되는가)와 `ticketrush.payment.refund.unresolved`(지금 미해결 건이 몇 건인가)다. 전자는 나중에 outbox 전환이 필요한지를 **데이터로** 판단하기 위한 것이다 — 지금은 그 빈도를 아무도 모른다.

### ShedLock을 쓰지 않는다

booking·seat의 outbox 릴레이는 ShedLock으로 다중 실행을 막지만 이 스케줄러는 붙이지 않는다. **읽기와 발행뿐이라 전이할 상태가 없고**, 중복 실행되더라도 같은 refund는 같은 eventId로 발행돼 수신 측 Inbox unique가 흡수하기 때문이다.

락을 위해 ShedLock을 들이면 `RedisLockProvider`가 필요하고, 그러려면 payment가 명시적으로 끈 Redis 오토컨피그를 되살려 이 서비스를 [ADR 0008](0008-accept-redis-spof-with-fail-closed.md)의 장애 범위에 새로 넣어야 한다. 얻는 것에 비해 대가가 크다.

## 결과

### 좋아지는 것

- 유실 원인과 무관하게 복구된다. Kafka 유실이든 booking 다운이든, FAILED 이력이 남아 있는 한 게이트가 열린다(단, 한 refund에 대해 1회분 — 아래 한계 참고).
- **booking-service를 건드리지 않는다.** 이벤트 스키마도 그대로라 배포 순서 의존성이 없다.
- 롤백이 설정 한 줄이다(`app.refund-signal-replay.enabled: false`).
- payment의 발행 경로·트랜잭션 경계·PG 호출이 전부 무변경이라 기존 환불·취소 경로의 회귀 표면이 없다.

### 나빠지는 것 / 남는 한계

- **`bookingNumber`를 null로 발행한다.** `refund` 테이블에 없는 값이고, 현재 이 토픽의 유일한 구독자인 booking이 `bookingId`·`failedAt`만 쓰기 때문에 성립한다. 그 전제가 깨지면 이 경로가 먼저 깨진다. `reason`도 원 발행이 싣던 트리거별 문구 대신 일반 메시지로 바뀌어 트리거 구분이 소실되는데, 같은 이유로 지금은 무해하다.
- **한 refund에 대해 복구되는 유실은 1회분뿐이다.** eventId를 `refund_id`로 고정했고 재실패는 새 row를 만들지 않으므로(`FailedRefundRecorder`가 unique 위반을 멱등 처리), "1차 실패 복구 → 관리자 재환불 → 2차 실패 → 그 이벤트 유실" 시나리오에서는 재발행이 (a) 같은 eventId라 Inbox에 걸리고 (b) 도달해도 원본 시각이 기록된 값보다 이르러 무동작이다. 그 건은 REFUNDING에 남으므로 [#397](https://github.com/TicketRush/TicketRush-backend/issues/397)의 고착 복구 경로(`isStuckInRefunding`)가 받는다 — 완전히 닫히지는 않지만 이 경로로는 안 열린다.
- **첫 주기의 폭발 반경.** 대상에 as-of 컷오프가 없어, 배포 직후 첫 주기가 **기존 FAILED 이력 전량**을 재발행한다. 과거에 PG 콘솔에서 수동 환불했거나 CS가 다른 방식으로 종결한 건도 booking 미해결 목록에 새로 올라온다. 컷오프를 두면 과거 건이 영영 안 열리므로 두지 않았고, 대신 배포 전 잔량 실측을 체크리스트 항목으로 돌렸다.
- **수동으로 종결된 건을 해소 표시할 수단이 없다.** 그 row는 계속 대상으로 남아 게이지를 올린 채 주기마다 메시지를 낸다. 알림 임계를 잡으려면 먼저 그런 row를 정리해야 한다.
- **미해결 건이 남아 있는 동안 같은 Kafka 메시지가 매 주기 나간다.** 수신 측이 Inbox로 버리므로 DB 쓰기는 없지만 메시지 자체는 흐른다. 없애려면 `refund`에 전달 상태 컬럼이 필요한데, 그건 스키마 변경과 맞바꾸는 선택이라 하지 않았다.
- **알림은 없다.** 카운터·게이지만 올리고 Slack은 붙이지 않았다. 발행 실패 콜백은 프로듀서 IO 스레드에서 실행되는데 `SlackNotifier`에 스로틀이 없어, 브로커 장애로 실패가 쏟아질 때 그 스레드가 웹훅에 묶여 **장애를 키운다**. 임계·억제·반복 방지가 있는 Grafana 알림 규칙이 올바른 자리이며, 그건 후속으로 남겼다. 그때까지 "관측 가능"은 대시보드를 열어야 참이다.
- **인덱스 하나가 늘었다.** `idx_refund_status`. FAILED가 희소해 `LIMIT`이 조기 종료를 못 하므로 인덱스가 없으면 매 주기 사실상 풀스캔이 된다. prod는 `validate` 모드지만 **Hibernate `validate`는 인덱스를 검증하지 않는다**(`.github/workflows/schema-validate.yml`·`deploy/mysql/README.md`가 같은 한계를 명시). 즉 수동 DDL을 빠뜨려도 기동은 성공하고, 증상은 5분마다 도는 풀스캔뿐이라 아무도 모른다 — 기동 실패로 걸러지지 않으므로 체크리스트([#441](https://github.com/TicketRush/TicketRush-backend/issues/441))가 유일한 그물이다.
- payment에 `@Scheduled`가 처음 생겼다. 배선 실패가 조용히 지나갈 수 있어 배포 후 확인 항목을 체크리스트에 넣었다.
- **레포에 "이벤트별로 전달 보장 등급을 다르게 둔다"는 선례가 생겼다.** 지금까지는 서비스 단위(`app.event-publisher.type`)로만 갈렸다. 이 결정은 발행 모드를 바꾸지 않고 특정 이벤트에만 복구 경로를 덧댄 것이라 설정만 보고는 드러나지 않는다 — 그래서 이 문서가 필요하다.

### 다루지 않은 것

- `PaymentConfirmedEvent` 유실. 이쪽은 `PaymentCanceledEvent`의 self-heal에 해당하는 경로가 아예 없어 booking이 PENDING으로 고착되는데, 복구 설계가 booking 확장을 부르므로 별도 이슈로 남긴다.
  > **정정(2026-08-17, [ADR 0015](0015-recover-charged-expired-booking-by-auto-refund.md)).** "PENDING으로 고착된다"는 부정확하다. `BookingExpirationScheduler`가 5분 뒤 EXPIRED로 전이시키므로 **고착이 아니라 소멸**이며, 그래서 이 문서가 세운 "남아 있는 상태로 재발행해 복구한다"를 그대로 적용할 수 없다 — 재발행해도 `Booking#confirm`이 EXPIRED를 거절한다. ADR 0015는 예매를 되살리는 대신 **대조로 찾아 자동 환불하는** 반대 방향을 택했다.
- payment 전면 outbox 전환. 위 (a)의 트랜잭션 재설계가 선행돼야 한다.
