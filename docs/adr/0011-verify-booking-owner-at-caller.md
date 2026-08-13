# 11. 내부 API 응답의 소유자 대조는 호출자가 하고, 불일치는 존재를 숨기는 코드로 접는다

날짜: 2026-08-04

## 상태

승인됨

[ADR 0002](0002-external-internal-api-url-separation.md)가 정한 내부 API 경계를 전제로 한다. 그 ADR이 "누가 내부 API를 호출할 수 있는가"(경로·라우트·토큰)를 정했다면, 이 ADR은 "호출해서 받은 데이터가 요청자의 것인지 누가 확인하는가"를 정한다.

## 맥락

내부(서비스 간) API는 **식별자만으로 조회된다.** booking-service의 `BookingGetInternalUseCase.execute(Long bookingId)`는 `findById` 단독이고 소유자 파라미터 자체가 없다. 접근 통제는 `hasRole("INTERNAL")` + `X-Internal-Token`뿐이라, **내부 토큰을 가진 서비스는 임의의 `bookingId`를 조회할 수 있다.** 이는 결함이 아니라 의도된 설계다 — 내부 API는 신뢰 경계 안에서 여러 호출자에게 서비스하며, 어떤 호출자에게는 소유자 개념 자체가 없다(예: 이벤트 리스너의 보상 처리).

문제는 그 결과 **소유자 검증의 책임이 어디에도 명시되지 않았다**는 것이다. payment-service의 결제 확정(`PaymentConfirmUseCase`)은 인증 주체의 `userId`를 인자로 받으면서도 그 값을 검증에 쓰지 않고 `Payment.userId`로 저장하기만 했다. 그래서 인증된 사용자가 남의 `bookingId`로 결제를 확정하면 **그 결제가 요청자 앞으로 귀속되고**(#572), 결제 취소·환불이 `Payment.userId` 기준으로 권한을 판단하므로 취소 권한까지 함께 넘어갔다.

같은 선택이 이미 두 곳에서 각자 내려져 있었고, 문서는 없었다.

- ticket-service `BookingRestClient`는 booking 404를 `TICKET_NOT_FOUND`로 통일한다 — "다른 사용자 예매의 존재 여부 노출을 막는다"고 javadoc에 적혀 있다.
- payment-service는 `findByIdAndUserId(...).orElseThrow(PAYMENT_NOT_FOUND)`로 "남의 것 = 없는 것" 관례를 쓴다(`PaymentCancelUseCase`, `PaymentGetDetailUseCase`).

즉 팀은 이미 같은 판단을 두 번 했지만, 세 번째 상황(#572)에서 그 판단을 다시 처음부터 해야 했다. 예매 ID가 순번에 가까운 이 시스템에서는 응답이 존재 여부를 구분해 주기만 해도 훑어서 열거할 수 있다는 점도 함께 고려됐다.

## 결정

**내부 API 응답의 소유자 대조는 호출자가 한다.** 내부 API에 소유자 파라미터를 추가하지 않는다 — 소유자 개념이 없는 호출자(이벤트 리스너 등)까지 그 계약을 지게 되고, 신뢰 경계 안의 API가 호출자별 권한 모델을 알아야 하는 역전이 생긴다.

호출자는 사용자 요청 경로에서 아래를 지킨다.

1. **소유자 대조를 다른 정책 판정보다 먼저 한다.** 상태·기한 같은 판정이 먼저 오면 남의 리소스에 그 판정 결과가 응답으로 나가 존재와 상태가 함께 샌다.
2. **불일치는 "없음"과 같은 응답으로 접는다.** 결제 확정은 `BOOKING_NOT_FOUND`(404)를 쓴다. 전용 403을 만들면 "존재하지만 네 것이 아님"을 그대로 알려 주게 된다.
3. **소유자를 판정할 수 없으면 "없음"이 아니라 "판정 불가"로 끊는다.** 응답에 소유자 필드가 null이면(계약 붕괴·배포 불일치) `PAYMENT_BOOKING_COMMUNICATION_FAILED`(503) + `log.error`로 처리한다. 이 경우를 404로 접으면 전건 실패가 "예매를 찾을 수 없습니다"로 나가 배포 사고가 데이터 문제로 오진단된다.
4. **응답을 동일화한 대가는 로그와 메트릭으로 보전한다.** 차단 시 `bookingId`·요청자·소유자를 로그에 남기고, 차단 메트릭의 `reason` 태그를 사용자 오류(`owner_mismatch`)와 계약 결함(`owner_unknown`)으로 가른다. 합치면 배포 사고가 사용자 오류 시계열에 묻힌다.

## 결과

- 남의 예매로 결제가 귀속되는 경로가 PG 호출 **전에** 닫힌다. 과금이 일어나지 않으므로 보상(#492)도 필요 없다.
- **응답만으로는 원인을 구분할 수 없다.** "남의 예매", "존재하지 않는 예매", "오타 `bookingId`"가 모두 404로 수렴한다. CS 진단은 전적으로 로그와 `reason` 태그에 의존하며, 이것이 열거 방어의 대가다.
- **잔여 오라클이 남는다.** 결제 확정의 앞선 두 가드(로컬 DB만 보는 COMPLETED 중복 차단, 만료 fast-path)는 `bookingId`만으로 판정하므로, 남의 예매라도 이미 결제됐으면 `PAYMENT_ALREADY_COMPLETED`가, 만료 기록이 있으면 `BOOKING_EXPIRED`가 나간다. 소유자 대조를 그 앞으로 올리면 닫히지만, 그러면 중복·만료 요청에도 booking 왕복이 붙고 booking 장애 시 로컬로 끝나던 경로까지 503이 된다. **새는 것이 소유자 귀속 정보가 아니라 상태 2비트**라 이 비용을 치르지 않기로 했다. 이후 이 판단을 뒤집으려면 왕복 비용과 fail-closed 결합을 함께 재검토해야 한다.
- **내부 API 응답 계약이 곧 보안 경계가 된다.** booking이 `user_id` 필드를 이름 변경·삭제하면 payment의 매핑이 `@JsonIgnoreProperties(ignoreUnknown = true)` 때문에 조용히 null이 되고, 결제 확정이 **전건 503**이 된다. 이를 잡는 계약 테스트는 없으며, 관측 축은 `owner_unknown` 메트릭과 그 분기의 `log.error` 두 가지다 — **알람은 메트릭에 걸되**, 로그 쪽은 전건 실패를 배포 직후 눈에 띄게 하는 역할이라 레벨을 낮추지 않는다.
- **이 결정은 앞으로의 경로만 닫는다. 이미 잘못 귀속된 `payment` row는 스스로 풀리지 않는다.** 결함이 있던 기간에 만들어진 row가 남아 있으면, 진짜 소유자는 결제 확정의 COMPLETED 중복 가드에 걸려 영구히 `PAYMENT_ALREADY_COMPLETED`(409)로 막히고, 잘못 귀속받은 쪽은 `findByIdAndUserId` 기준의 취소·환불 권한을 계속 보유한다. 어느 쪽도 애플리케이션 경로로는 복구되지 않으므로, 배포와 별개로 `payment`와 `booking`의 `user_id`를 대조해 불일치 건수를 실측해야 한다. 0건이 아니면 복구는 별도 이슈가 된다.
- **대리 결제·양도처럼 "결제자 ≠ 예매 소유자"인 기능이 생기면 이 결정이 그 기능을 막는다.** 현재 그런 경로는 없다. 그때는 이 ADR을 개정해 "소유자"를 "결제 권한 보유자"로 넓히는 편이, 가드를 예외 처리로 우회하는 것보다 낫다.

### 결제 진입점 점검 결과 (#572 시점)

`bookingId`를 외부 입력으로 받는 경로만 이 대조가 필요하다. 나머지는 조회 자체가 `userId` 복합 조건이라 구조적으로 안전하다.

| 진입점 | 외부 `bookingId` 수용 | 소유자 검증 |
|---|---|---|
| `POST /api/v1/payment/confirm` | O | **이 ADR로 추가됨** |
| confirm 멱등 fallback | O(내부 전달) | 위 가드 통과가 도달의 선행 조건 — `saveAndFlush`가 유일한 `DataIntegrityViolationException` 발생 지점이다 |
| `POST /api/v1/payment/{paymentId}/cancel` | X | `findByIdAndUserId` |
| `GET /api/v1/payment` | X | `findByUserIdAndStatus` |
| `GET /api/v1/payment/{paymentId}` | X | `findByIdAndUserId` |
| `POST /api/v1/payment/webhook` | X (`bookingId` 필드 없음) | PG 콜백이라 인증 주체 없음, 진위는 `paymentKey` 재조회로 검증 |
| 이벤트 리스너 3종 | 내부 신뢰 경계 | 사용자 입력이 아니므로 대상 아님 |
