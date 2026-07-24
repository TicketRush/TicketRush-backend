# load-test

seat/booking/ticket 핫패스 성능·정합성을 정량 측정하기 위한 k6 부하 테스트 인프라.

```
load-test/
├── config/   # env.js(환경변수), options.js(공통 stages·thresholds)
├── lib/      # auth.js(로그인 → access token)
├── scenarios/# booking-create.js(예매 생성, 인증), seat-layouts.js(좌석 조회, 비인증),
│           #  seat-contention.js(단일 좌석 경합, 인증 — #344)
├── seed/     # seed_load.sql(대량 시딩 + 부하테스트 계정), cleanup_load.sql(정리)
└── chaos/    # 장애 주입(#346): broker-outage.sh, verify-loss.sql, booking-outbox.override.yml
```

k6는 docker-compose의 `loadtest` profile로 분리된 컨테이너에서 실행한다(상시 기동 대상 아님).

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
