# 19. 결제 확정의 booking 동기 조회를 서킷브레이커로 격리하고, 열린 동안에도 fail-closed를 유지한다

날짜: 2026-09-12

## 상태

승인됨

## 맥락

결제 확정 경로는 PG 승인 전에 booking-service를 한 번 동기 조회해 예매 상태를 판정한다([#490](https://github.com/TicketRush/TicketRush-backend/issues/490)). 이 조회는 **fail-closed**다 — 판정할 수 없으면 결제를 막는다. 되돌릴 수 없는 부작용(과금)이 먼저 일어나는 것을 피하기 위한 선택이고, ADR 0008이 못박은 "가용성 손실이 정합성 손실보다 낫다"와 같은 축이다.

그 결정이 남긴 문제가 하나 있었다. **booking-service가 죽지 않고 느려지면 요청마다 톰캣 스레드가 read-timeout(1초)만큼 묶인다.** 완화 수단이 그 타임아웃 하나뿐이라, 조회 하나가 느려지는 것이 결제 경로 전체를 마르게 하는 경로가 열려 있었다. ticket-service가 [#496](https://github.com/TicketRush/TicketRush-backend/issues/496)에서 같은 형태를 겪고 서킷브레이커로 끊었다.

### 임계값을 선례에서 복사할 수 없었다

ticket-service의 설정은 그쪽 실측(#402, 정상 왕복 3.20ms)에 맞춰 잡은 값이다. 근거 없이 복사하면 오탐 open이 나는데, **fail-closed와 겹치면 그 오탐이 곧 결제 전면 중단**이 된다. 실제로 #496은 느린호출 임계를 300ms로 잡았을 때 배포 직후 JIT 워밍업 구간에서 **실패 0건인데 차단 1,472건**을 겪었다. 검표가 막히는 것과 매출이 멈추는 것은 대가가 다르다.

그래서 [#633](https://github.com/TicketRush/TicketRush-backend/issues/633)이 payment→booking 구간을 따로 실측했다(`load-tests/k6/results/260911-633-payment-booking-roundtrip/`). **그 회차가 확정해 준 값은 하나뿐이다** — 나머지는 잠정이거나 근거가 없다는 것까지가 결과다.

## 결정

### 1. 서킷을 `BookingRestClient` 안에 단다

호출부는 셋이다 — 확정(`PaymentConfirmUseCase`) · 취소(`PaymentCancelUseCase`) · 과금-만료 자동 환불 스케줄러(`PaymentRecoverChargedExpiredBookingUseCase`). 호출부마다 감으면 같은 코드가 셋으로 복제되고 하나라도 빠지면 서킷 통계가 조용히 반쪽이 된다. 클라이언트 안에 한 번 감아 **세 경로가 같은 서킷 인스턴스를 공유**한다. "booking이 죽었다"는 판단은 호출자별로 달라질 성질이 아니다.

### 2. 서킷이 열려도 정책은 그대로 fail-closed(503)다

open 상태에서는 booking을 치지 않고 즉시 `PAYMENT_BOOKING_COMMUNICATION_FAILED`(503)로 떨어진다. **이것은 #490의 fail-closed와 다른 결정이 아니라 같은 결정의 빠른 형태다** — 조회가 불가능한 동안 결제를 통과시키면 과금이 남고, 막으면 가용성만 잃는다. 서킷이 바꾸는 것은 거절의 이유가 아니라 속도뿐이다: "1초 기다린 뒤 503"이 "즉시 503"이 된다. 그 1초가 톰캣 스레드 점유이고, 이 이슈가 끊으려는 것이 그 점유다.

`BusinessException`은 fallback에서 그대로 재던진다. 특히 **`BOOKING_NOT_FOUND`(404)를 503으로 뭉개면 "없는 예매"가 "장애"로 뒤집힌다.**

### 3. 예매 없음(404)은 서킷 창에서 제외한다 — 실패도 성공도 아니다

`ignoreException`으로 `BOOKING_NOT_FOUND`를 뺀다. booking-service가 **정상 동작 중일 때** 나오는 응답이기 때문이다. 실패로 세면 존재하지 않는 예매로 결제를 반복 시도하는 것만으로 서킷이 열려 멀쩡한 결제가 전부 503이 된다.

🔴 **`recordException`이 아니라 `ignoreException`인 것이 이 결정의 핵심이다.** Resilience4j에서 "실패로 기록하지 않는다"는 곧 **성공으로 기록한다**는 뜻이다(`onSuccess`를 탄다). 그러면 셋이 따라온다 — ① 404 홍수가 `minimumNumberOfCalls`를 대신 채워 게이팅을 열어 주고, ② 성공 분모를 불려 실패율을 희석하며(404와 실패가 1:1로 섞이면 50%에 닿지 않아 booking이 실제로 아파도 열리지 않는다), ③ 느린호출 판정에도 들어간다. `ignoreException`은 permit을 돌려주고 창에서 통째로 뺀다. 우리가 원하는 것은 "이 응답은 서킷이 볼 신호가 아니다"이므로 후자다. (ticket-service는 `recordException` 축이라 이 점에서 다르다.) 이 경계는 #633이 왕복 Timer에 붙여 둔 `outcome` 태그(`success`/`not_found`/`failed`)와 **같은 선**이다. 두 축이 어긋나면 그 관측으로 서킷 동작을 설명할 수 없게 된다.

### 4. 윈도우는 시간 기반(TIME_BASED)으로 둔다 — ticket-service와 갈리는 지점

#633 회차는 윈도우 환산의 입력을 주지 못했다. 트래픽이 **합성 단일 사용자**였고, Rate Limit은 **사용자당**인데 서킷 윈도우는 **인스턴스 전역**이라 둘을 같은 축으로 환산할 수도 없다. 건수 윈도우(ticket: 20건)를 저트래픽 구간에 두면 **어제의 실패가 오늘의 개폐를 결정**한다. 시간 윈도우(60초)는 그 stale 표본 문제를 없앤다.

다만 **호출량 가정이 완전히 사라지지는 않는다.** `minimumNumberOfCalls`(10)는 윈도우 종류와 무관하게 남는 개폐 게이팅 조건이고 — 그 수를 못 채우면 서킷은 영영 열리지 않는다 — 그 값의 근거는 없다.

### 5. 킬 스위치를 둔다

`service.booking.circuit-breaker.enabled=false`면 서킷을 거치지 않고 직접 호출한다. ticket-service는 서킷이 코드에 박혀 있어 런타임에 끌 수 없는데(`docs/load-test-guide.md` §12.6), 이 경로는 fail-closed라 그 한계의 대가가 훨씬 크다. 끈 결과는 **#490 상태로 정확히 되돌아가는 것**이라 이미 아는 동작이다. 대기값 0을 킬 스위치로 둔 ADR 0012와 같은 규율이다.

### 6. 서킷을 왕복 Timer보다 바깥에 둔다

차단된 호출은 `ticketrush.payment.booking.lookup` 분포에 남지 않는다. 0ms짜리 차단 표본이 대량으로 섞이면 **#633이 만든 왕복 지연 분포가 오염되어 임계값을 다시 도출할 축을 잃는다.** 차단 건수는 resilience4j의 `not_permitted_calls_total`로 따로 본다.

🔴 **그 대가로 #633이 쓰던 대조 규칙이 하나 깨진다.** 차단된 호출은 Timer에 남지 않지만 `PaymentConfirmUseCase`의 `guard_blocked{reason="lookup_failed"}`에는 남는다. 그래서 앞으로는 **"Timer `outcome=failed`는 0인데 `guard_blocked{lookup_failed}`는 오른다"가 정상 상태로 생긴다.** #633 리포트 §4.3이 503의 발생 층을 좁힐 때 근거로 삼은 것이 바로 그 두 축의 일치였으므로, 서킷 배포 이후의 측정에서는 `not_permitted_calls_total`을 세 번째 축으로 함께 읽어야 한다.

## 결과

### 얻는 것

- booking이 느려질 때 톰캣 스레드가 요청마다 1초씩 묶이는 전파가 끊긴다. 장애 범위가 "결제 확정만 막힌다"로 좁아진다.
- 서킷 상태·차단 호출수가 Prometheus에 노출되어(`resilience4j_circuitbreaker_*`) 장애 구간을 사후에 분해할 수 있다.
- 부수 효과로 **비 `BusinessException`이 새어 원시 500이 나가던 경로가 사라졌다.** confirm 경로에는 그 catch가 없어서, 지금까지는 그런 예외가 500으로 나가고 가드 카운터도 침묵했다. 이제 fallback이 503으로 수렴시킨다.

### 감수하는 것

- 🔴 **오탐 open의 대가가 결제 전면 중단이다.** 느린호출 임계 100ms는 #633이 **재기동** 워밍업에서 얻은 값이고, **배포 직후 구간은 재현하지 못했다** — #496이 오탐을 겪은 것이 바로 그 구간이다. 첫 배포 직후 `resilience4j_circuitbreaker_state{name="booking"}`를 관찰해야 하고, 오탐이 나면 #496 선례대로 임계를 올리거나 킬 스위치로 끈다.
- 다섯 임계값 중 **실측 근거가 있는 것은 `slowCallDurationThreshold` 하나뿐이다.** 실패율 50%는 서킷이 보는 구간의 실패 표본이 0건이라 도출된 값이 아니고, open 대기 10초는 ticket에서 차용했을 뿐 근거가 없다. 각 값의 등급과 "틀렸다면 무엇으로 알 수 있는가"를 `BookingCircuitBreakerConfig` javadoc에 남겼다.
- 레포 유일한 서킷 선례(ticket, COUNT_BASED)와 윈도우 축이 갈린다. 같은 이름의 `slidingWindowSize`가 한쪽은 건수, 한쪽은 초를 뜻하므로 오독 가능성이 생긴다.
- 🔴 **스케줄러가 사용자 경로와 서킷 윈도우를 공유한다.** 과금-만료 자동 환불 스케줄러의 기본 주기(60초)가 서킷 창(60초)과 **정확히 같고**, 그 랩의 조회 횟수는 `max-refunds-per-lap`(50)과 무관하다 — 조회에 실패한 건은 환불 시도로 세지 않으므로 `batch-size`(500)까지 나갈 수 있다. booking이 아픈 구간이라면 **이 버스트 한 방이 `minimumNumberOfCalls`를 혼자 채우고 창을 자기 표본으로 도배해 서킷을 연다.** 그 대가는 사용자 결제 확정의 fail-fast 503인데, 정작 스케줄러 자신은 예외를 삼켜(SKIPPED) 아무 영향도 받지 않는다 — **비용을 내는 쪽과 트래픽을 만드는 쪽이 다르다.**

  현재 이 스케줄러는 기본 비활성(`CHARGED_EXPIRED_RECOVERY_ENABLED=false`)이라 즉시 사고는 아니지만, **그 값을 켜는 것이 이 서킷의 숨은 선행조건이 됐다.** 켤 때는 스케줄러 경로에 별도 서킷 인스턴스를 두거나 그 경로만 서킷을 우회하는 것을 먼저 결정해야 한다. 이 ADR은 그 결정을 하지 않았다.

### 이 결정이 해결하지 못하는 것

#633 회차에서 k6가 센 503 18건은 **payment의 booking 조회 경로에 도달조차 하지 않았다**(리포트 §4.3 — 세 카운터가 0이고 `guard_blocked{CONFIRMED}`와 Timer `success`가 소수점까지 일치했다). **서킷이 감쌀 구간 밖에서 난 실패라 이 결정으로는 막히지 않고, `failureRateThreshold` 계산에도 들어가지 않는다.** 어느 층에서 났는지는 미규명이며 별도로 다룬다.
