# load-test

seat/booking/ticket 핫패스 성능·정합성을 정량 측정하기 위한 k6 부하 테스트 인프라.

```
load-test/
├── config/   # env.js(환경변수), options.js(공통 stages·thresholds)
├── lib/      # auth.js(DevToken 발급)
├── scenarios/# booking-create.js(예매 생성, 인증), seat-layouts.js(좌석 조회, 비인증)
└── seed/     # seed_load.sql(대량 시딩), cleanup_load.sql(정리)
```

빠른 실행:

```bash
# 1) 모니터링 스택 (remote-write receiver 포함)
docker compose up -d prometheus grafana

# 2) 대량 시딩 (규모는 seed_load.sql 상단 @vars 에서 조정)
mysql -h 127.0.0.1 -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" ticket_rush < seed/seed_load.sql

# 3) k6 실행 → Prometheus remote-write → Grafana
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg" \
k6 run -o experimental-prometheus-rw scenarios/seat-layouts.js
```

상세(사전조건·Grafana 관측·트러블슈팅)는 [`docs/load-test-guide.md`](../docs/load-test-guide.md) 참고.
