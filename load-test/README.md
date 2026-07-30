# load-test

seat/booking/ticket 핫패스 성능·정합성을 정량 측정하기 위한 k6 부하 테스트 인프라.

```
load-test/
├── config/   # env.js(환경변수), options.js(공통 stages·thresholds)
├── lib/      # auth.js(로그인 → access token), json.js(안전 JSON 파싱)
├── scenarios/# booking-create.js(예매 생성, 인증), seat-layouts.js(좌석 조회, 비인증),
│           #  seat-contention.js(단일 좌석 경합, 인증 — #344),
│           #  entry-spike.js(입장 검표 스파이크 — #402),
│           #  entry-duplicate-scan.js(동일 QR 동시 스캔 정합성 — #402),
│           #  openrun-e2e.js(오픈런 스파이크 e2e 종합 — #348),
│           #  seat-counts.js(상태별 집계 계단 — #403),
│           #  seat-sse-fanout.js(SSE 대량 구독 팬아웃 — #403, k6-sse 이미지 전용)
├── seed/     # seed_load.sql(대량 시딩 + 부하테스트 계정), cleanup_load.sql(정리),
│           #  seed_expired_holds.sql(만료 HOLD 코호트 — #345),
│           #  seed_entry.sql(검표 코호트 + ADMIN 계정, 리셋 내장 — #402),
│           #  reset_e2e.sql(e2e 회차 간 리셋 — #348),
│           #  seed_seat_counts.sql(규모·상태분포 지정 코호트 LTC-* — #403),
│           #  seed_payment_pipeline.sql(결제확정 파이프라인 코호트 LTP-*, seed/reset/verify — #504)
├── bench/    # trx-sampler.sh(MySQL 트랜잭션 지속시간·락 점유 샘플러 — #345),
│           #  outbox-sampler.sh(outbox backlog·릴레이 처리량 샘플러 — #489),
│           #  dump-timeseries.py(회차 시계열 증적 덤프 — #348, 로컬 실행)
└── chaos/    # 장애 주입(#346): broker-outage.sh, verify-loss.sql, booking-outbox.override.yml,
            #  inbox-redeliver.sh·verify-inbox.sql(#347),
            #  seat-release-singletrx.override.yml(단일 트랜잭션 비교군 — #345),
            #  inject-payment-confirmed.sh(결제확정 이벤트 직접 주입 + 드레인 대기 — #504)
```

`bench/`·`chaos/`의 스크립트와 override는 **측정 전용**이다(프로덕션 반영 아님). 스크립트는 EC2 배포본 호스트에서 실행하고, override는 적용 후 반드시 원복한다. 예외로 `bench/dump-timeseries.py`는 **로컬에서** 실행한다(Prometheus SSH 터널 경유, 부하가 끝난 뒤 1회).

k6는 docker-compose의 `loadtest` profile로 분리된 컨테이너에서 실행한다(상시 기동 대상 아님).

`Dockerfile.k6-sse`는 SSE 클라이언트(`k6/x/sse`)를 넣어 직접 빌드하는 이미지다. k6 기본 바이너리에는 SSE가 없어 `seat-sse-fanout.js`는 이 이미지에서만 돈다 — `docker compose --profile loadtest build k6-sse` 후 `k6` 대신 `k6-sse` 서비스로 실행한다.

빠른 실행:

```bash
# 1) 모니터링 스택 (remote-write receiver 포함)
docker compose up -d prometheus grafana

# 2) 대량 시딩 (규모는 seed_load.sql 상단 @vars 에서 조정)
#    오실행 가드: @i_confirm_loadtest_db=1 없으면 중단된다(운영 DB 보호).
mysql -h 127.0.0.1 -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" \
  --init-command="SET @i_confirm_loadtest_db=1" ticket_rush < seed/seed_load.sql

# 3) k6 실행 (컨테이너, remote-write는 K6_OUT로 기본 적용) → Grafana
docker compose run --rm k6 run /scripts/scenarios/seat-layouts.js
```

상세(사전조건·Grafana 관측·트러블슈팅)는 [`docs/load-test-guide.md`](../docs/load-test-guide.md) 참고.
