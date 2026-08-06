# 12. PG 환불의 일시적 거절은 호출 지점에서 흡수하고, 소진되면 결정적 실패로 확정한다

날짜: 2026-08-06

## 상태

승인됨

## 맥락

### 4xx가 한 덩어리로 뭉뚱그려져 있었다

`TossPaymentCancelClient`는 PG 취소 응답의 **모든 4xx**를 `PAYMENT_REFUND_FAILED`로 매핑했고, `FailedRefundRecorder.isDeterministicRejection`은 그 상태 하나만 보고 "PG가 확정적으로 거절함"으로 판정했다. 이 판정을 받은 건은 두 리스너 모두에서 재시도 없이 ack되고, `Refund` FAILED 이력 + `RefundFailedEvent` + `[CRITICAL]` 로그로 확정된다. **자동 복구가 그 지점에서 끝난다.**

그런데 Toss 문서를 실제로 확인해 보니 4xx 안에 재시도해야 하는 것이 섞여 있었다(2026-08-06 확인).

| HTTP | code | Toss 원문 |
|---|---|---|
| 409 | `IDEMPOTENT_REQUEST_PROCESSING` | "이전 멱등 요청이 처리중입니다." — 문서가 "다시 한번 요청해서 응답을 확인하세요"라고 직접 안내 |
| 400 | `PROVIDER_ERROR` | "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요." |
| 403 | `FORBIDDEN_CONSECUTIVE_REQUEST` | "반복적인 요청은 허용되지 않습니다. 잠시 후 다시 시도해주세요." |

**HTTP 상태로는 가를 수 없다.** 403 하나에 재시도 대상(`FORBIDDEN_CONSECUTIVE_REQUEST`), 결정적 거절(`NOT_CANCELABLE_PAYMENT`), 설정 오류(`INCORRECT_BASIC_AUTH_FORMAT`)가 공존한다. 400도 마찬가지다. 판정 근거를 PG 원본 `code`로 옮겨야 했다.

덧붙여 **HTTP 429는 Toss 에러 코드 문서 전체에 존재하지 않는다.** rate limit 자리는 403 `FORBIDDEN_CONSECUTIVE_REQUEST`가 대신한다. 그리고 그게 가장 나오기 쉬운 구간이 하필 [#492](https://github.com/TicketRush/TicketRush-backend/issues/492)가 겨냥한 대량 만료 버스트다 — 좌석 확정 실패가 동시다발로 터지면 보상 환불도 동시다발로 나간다.

### 왜 지금 중요해졌나

이 분류는 #91에서 만들어져 사용자 취소 경로가 써 왔다. 거기서는 오분류의 대가가 "사용자가 취소를 다시 누르면 됨"이었다.

**#492가 이 분류를 사고 보상의 마지막 방어선으로 쓰면서 대가가 달라졌다.** 보상 경로에는 사용자가 다시 누를 대상이 없다 — 사용자는 자기 예매가 정상 CONFIRMED로 보이고 좌석이 없다는 사실조차 모른다.

### 레포 표준(#269)을 그대로 적용하면 복구 경로가 닫힌다

일시적 실패를 만나면 예외를 재던져 Kafka 재시도 → DLT로 보내는 것이 이 레포의 표준이다(`BookingExpiredEventListener` 등). 그 표준대로 재시도 대상 4xx를 `PAYMENT_PG_COMMUNICATION_FAILED`로 옮기는 방안을 먼저 검토했고, **기각했다.**

`DeadLetterConsumer`는 DLT 메시지를 `dead_letter_record`에 저장하고 알림을 보낼 뿐이다. **재처리·재발행 수단이 레포에 없고**(엔티티 javadoc도 용도를 "수동 복구의 근거"라고 적는다), `DltRetentionService`가 **30일 뒤 그 기록을 삭제한다.**

| | 결정적 거절로 확정(현행) | 재시도 → DLT |
|---|---|---|
| 남는 기록 | `Refund` FAILED(payment DB, 영구) + `Booking.refundFailedAt` | `dead_letter_record` 1건, 30일 후 삭제 |
| 복구 창구 | 관리자 재환불 API(앱 경로) | 없음 — 수동 DB 조작 |

즉 표준을 따르면 **복구 가능성이 오히려 후퇴한다.** `Booking` 엔티티 javadoc이 이 시나리오를 이미 적어 두었다 — 보상 환불이 거절되면 "미해결 목록(`CONFIRMED` + `refund_failed_at IS NOT NULL`)에도 잡히지 않을뿐더러 관리자 재환불 API가 `BOOKING_REFUND_RETRY_NOT_ALLOWED`로 거부한다". ADR 0005가 그 창구를 열었고 #492가 보상 경로를 거기 연결했는데, 재분류가 그걸 다시 닫는 셈이었다.

## 결정

**PG 환불의 일시적 거절은 `TossPaymentCancelClient` 안에서 재시도로 흡수하고, 소진되면 기존과 동일하게 `PAYMENT_REFUND_FAILED`로 확정해 던진다.**

- **판정 근거는 PG 원본 `code`이며, 화이트리스트다.** `TossCancelRetryableCode`에 열거된 코드만 재시도 대상이고, 나머지 4xx·미정의 코드·body를 읽지 못한 응답은 전부 기존 동작(결정적 거절)을 유지한다. 블랙리스트를 쓰면 Toss가 새 거절 코드를 추가할 때마다 그 건이 이력 없이 사라진다. 화이트리스트가 틀렸을 때의 손해는 "자동 복구 못 함"이지만, 반대 방향의 손해는 "과금된 건을 잃음"이다.
- **재시도는 대기 1회 + 재호출 1회다.** Toss가 409에 대해 안내하는 "다시 한번 요청"과 같은 횟수다. 결제당 고정 멱등 키(`REFUND-%07d`)를 쓰므로 재호출이 중복 환불을 만들지 않는다 — 재시도 요청은 첫 시도와 완전히 동일하며, 이 불변식은 테스트가 멱등 키 헤더와 body를 단언해 고정한다.
- **`payment.pg.toss.cancel-retry-delay-ms`는 대기 시간이자 킬 스위치다.** `0` 이하면 대기만 생략하는 것이 아니라 재시도 자체를 하지 않고 기존 확정 경로로 낙하한다. "대기 없는 즉시 재시도"를 허용하지 않는 이유는 이 노브가 필요해지는 상황이 하필 `FORBIDDEN_CONSECUTIVE_REQUEST`(rate limit) 버스트여서, 거기서 대기만 없앤 재시도는 PG 호출량을 두 배로 늘려 사고를 키우기 때문이다.
- **`isDeterministicRejection` 술어와 두 리스너의 분기는 손대지 않는다.** 세분화된 분류가 클라이언트 안에서 끝나고 밖으로는 기존 두 상태(`PAYMENT_REFUND_FAILED` / `PAYMENT_PG_COMMUNICATION_FAILED`)만 나가기 때문이다. 결정적 거절을 의미별로 나누어 다른 `ErrorStatus`에 매핑하면 술어가 false로 떨어져 **FAILED 이력과 보상 이벤트가 동시에 사라지므로, 단일 매핑을 불변식으로 못박는다.**
- **인증 실패(401 `UNAUTHORIZED_KEY`, 403 `INCORRECT_BASIC_AUTH_FORMAT`)도 분리하지 않는다.** 재시도로 해소되지 않으니 화이트리스트에 넣을 수 없고, `PAYMENT_PG_AUTH_FAILED`로 분리해 DLT로 보내면 위 표의 오른쪽 열이 된다. 현행대로 FAILED 이력을 남기면 키를 고친 뒤 미해결 목록에서 일괄 재환불할 수 있다. 원인 구분은 로그의 `tossCode`가 맡는다.
- **양쪽 경로에 동일하게 적용한다.** API 취소 경로(#22)에서 409가 나오는 실제 상황은 이벤트 환불과 사용자 취소가 겹칠 때인데, 지금은 그때 사용자에게 "환불 실패"라고 답하고 FAILED 이력까지 남긴다. 재시도하면 선행 환불 완료 후 멱등 응답으로 정확한 결과를 돌려준다.

## 결과

**얻는 것.** 일시적 거절이 자동으로 해소된다. 해소되지 않아도 결과는 변경 전과 정확히 동일하다 — FAILED 이력, `refundFailedAt`, 관리자 재환불 API가 모두 그대로다. 즉 이 결정은 **복구 창구를 유지한 채 자동 복구만 덧붙인다.** 술어·리스너·UseCase가 무변경이라 변경 표면이 클라이언트 한 파일에 갇히고, 새 `ErrorStatus`가 없어 프론트 계약도 그대로다.

**감수하는 것.**

- **컨슈머 스레드 점유 — `max.poll.interval.ms`를 넘길 수 있다.** 재시도 대기와 2차 왕복 동안 스레드가 묶인다. `MAX_POLL_RECORDS=20`, `MAX_POLL_INTERVAL_MS=300초`인데, 배치 20건이 전부 재시도하고 2차가 read-timeout이면 20 × 11초 = 220초다. 여기에 두 항이 더 얹힐 수 있다 — ① 1차 시도가 (타임아웃 전까지) 느린 경우와 ② 기존 `ExponentialBackOffWithMaxRetries(5)`(1+2+4+8+16 = 31초)가 같은 컨슈머 스레드에서 소비되므로, 재시도 흡수 후 통신 실패로 떨어지는 건이 배치에 섞이면 그만큼 더해진다. **즉 "근접"이 아니라 초과 가능이다.** 완화 수단은 위 킬 스위치이며(`0`으로 두면 재시도가 사라져 증가분이 0이 된다), 다만 생성자 주입이라 **적용에 재기동이 필요하다**(재기동 자체가 리밸런스를 부른다). 임계가 실제로 관측되면 `MAX_POLL_RECORDS` 하향이나 `MAX_POLL_INTERVAL_MS` 상향을 별도로 검토한다 — 둘 다 common 전역 설정이라 이번 범위에 넣지 않았다.
- **API 취소 응답 지연.** 최악 +11초. nginx 60초 예산 안이지만 여유가 줄어든다.
- **`PROVIDER_ERROR` 재시도가 무효일 수 있다.** Toss 문서는 실패 응답이 멱등키에 캐시되는지를 명시하지 않는다. 캐시된다면 같은 키로 재요청해도 같은 400이 돌아온다. 실패 모드가 안전해서(대기 1회 + 호출 1회를 쓰고 기존 결과로 낙하) 포함했고, 실제 효과는 `ticketrush.payment.pg.cancel.retry`의 `outcome` 태그로 사후 판정한다.
- **`ticketrush.payment.pg.cancel` Timer의 의미가 바뀐다.** 재시도 구간을 포함하므로 값이 올라간다. 새 카운터와 겹쳐 읽어야 상승분이 설명된다.
- **레포 표준(#269)에서 벗어난다.** 다른 도메인이 이 패턴을 참조할 때는 전제를 함께 봐야 한다 — **DLT가 복구 창구로 기능하지 않는 경우에만** 이 선택이 옳다. DLT 재처리 수단이 생기면(별도 과제) 이 결정은 재검토 대상이다.

**후속.** 취소 경로의 PG 원본 코드를 `refund` 테이블에 저장하는 일(#332의 취소판)은 스키마 변경을 유발하므로 이 결정에 포함하지 않았다. 지금은 로그에만 남는다. 발행 유실로 보상 신호가 사라지는 문제는 [#574](https://github.com/TicketRush/TicketRush-backend/issues/574)가 따로 다룬다 — 이 결정은 "무엇을 보상으로 확정할 것인가"를 정할 뿐, 확정된 보상이 전달되는지는 건드리지 않는다.
