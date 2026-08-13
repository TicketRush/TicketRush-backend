"""#348 측정 — 회차 시계열 증적 덤프. **로컬에서** 실행한다(EC2 아님).

리포트에 남기는 timeseries-*.json 을 회차마다 손으로 뽑으면 빠뜨리는 지표가 생긴다. 측정 창만
주면 정해진 목록을 한 번에 떠서 파일로 남긴다. bench/ 의 다른 샘플러(trx/outbox)가 부하 '중'
폴링이라면 이쪽은 부하가 '끝난 뒤' 한 번 도는 도구다.

사용법:  python load-test/bench/dump-timeseries.py <START_UTC> <END_UTC> <OUTDIR> [회차이름]
  예:    python load-test/bench/dump-timeseries.py \
           2026-07-27T14:38:00Z 2026-07-27T15:10:00Z load-tests/k6/results/260727-348-openrun-e2e ramp

회차이름을 주면 `timeseries-<지표>-<회차이름>.json` 으로 저장한다. 한 리포트에 회차가 둘 이상이면
반드시 줄 것 — 안 주면 뒤 회차가 앞 회차 파일을 덮어쓴다(#496 이 before/after 를 접미사로
구분한 것과 같은 규약).

전제:
  - Prometheus 에 SSH 터널이 떠 있어야 한다(localhost:9090). 관측 포트는 인터넷에 열려 있지
    않다(ADR 0007). 터널이 없으면 전 쿼리가 연결 실패로 끝난다.
  - step 은 15s 로 고정한다 — 스크랩 간격과 같아야 원 표본을 그대로 본다.

⚠ **부하가 도는 중에는 돌리지 않는다.** 쿼리 30여 개가 측정 대상 EC2 의 CPU 를 쓴다. 부하 중
   대시보드를 열지 않는 것과 같은 이유다(ADR 0004).
⚠ 시계열이 비어 있으면 "쿼리는 성공했으나 데이터가 없다"는 뜻이고, 마지막에 목록으로 보고한다.
   메트릭 이름 오타와 구분되지 않으므로, 새 지표를 추가할 때는 실제 이름을 먼저 확인할 것
   (`curl -s localhost:9090/api/v1/label/__name__/values`).
"""
import json
import pathlib
import sys
import urllib.parse
import urllib.request

if len(sys.argv) not in (4, 5):
    sys.exit(f"사용법: python {sys.argv[0]} <START_UTC> <END_UTC> <OUTDIR> [회차이름]\n"
             f"  예:   python {sys.argv[0]} 2026-07-27T14:38:00Z 2026-07-27T15:10:00Z "
             f"load-tests/k6/results/260727-348-openrun-e2e ramp")

START, END, OUTDIR = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
# 회차가 둘 이상인 리포트에서 파일이 서로 덮이지 않게 한다.
SUFFIX = f"-{sys.argv[4]}" if len(sys.argv) == 5 else ""
STEP = "15s"

# (파일 슬러그, PromQL) — 파일명은 #402/#496 관행(timeseries-<slug>.json)을 따른다
QUERIES = [
    # 호스트 자원
    ("node-cpu", '100 * (1 - avg(rate(node_cpu_seconds_total{job="node", mode="idle"}[1m])))'),
    ("node-iowait", '100 * avg(rate(node_cpu_seconds_total{job="node", mode="iowait"}[1m]))'),
    ("node-mem-used-bytes", 'node_memory_MemTotal_bytes{job="node"} - node_memory_MemAvailable_bytes{job="node"}'),
    # 회선 축. device 를 ens5 로 고정한다 — #508 이전에는 이 지표가 컨테이너 veth 를 재서
    # 실제의 1/6500 이 나왔다. 그래서 #348 회차는 회선 포화를 k6 수치로 역추정해야 했다.
    ("node-net-tx", 'rate(node_network_transmit_bytes_total{job="node", device="ens5"}[1m])'),
    ("node-net-rx", 'rate(node_network_receive_bytes_total{job="node", device="ens5"}[1m])'),
    # 컨테이너별 메모리(#515). container-mem-sampler 가 cgroup v2 를 직접 읽어 node-exporter
    # textfile 로 넘긴다 — cAdvisor 는 이 호스트에서 컨테이너를 열거하지 못해 되돌렸다.
    # ⚠ sampler-containers 가 0 이면 위 세 시계열이 비는 것은 '메모리를 안 썼다' 가 아니라
    #   '수집이 멎었다' 는 뜻이다. 리포트에 수치를 옮기기 전에 이 값을 먼저 본다.
    ("container-mem-usage", 'ticketrush_container_memory_usage_bytes'),
    ("container-mem-limit-pct", '100 * ticketrush_container_memory_usage_bytes / ticketrush_container_memory_limit_bytes'),
    ("container-oom-kills", 'increase(ticketrush_container_oom_kills_total[5m])'),
    ("container-sampler-containers", 'ticketrush_container_sampler_containers'),
    # 유입 축 (k6)
    ("k6-rps", 'sum(rate(k6_http_reqs_total[1m]))'),
    ("k6-rps-by-name", 'sum by (name) (rate(k6_http_reqs_total[1m]))'),
    ("k6-req-failed", 'k6_http_req_failed_rate'),
    ("k6-req-duration-p95", 'k6_http_req_duration_p95'),
    # 지연을 단계별로 쪼갠다 — waiting(TTFB, 서버가 첫 바이트를 줄 때까지) 대비
    # receiving(본문 수신)이 크면 병목은 처리가 아니라 전송이다(= 응답 크기·압축 부재).
    ("k6-req-waiting-p95", 'k6_http_req_waiting_p95'),
    ("k6-req-receiving-p95", 'k6_http_req_receiving_p95'),
    ("k6-req-blocked-p95", 'k6_http_req_blocked_p95'),
    ("k6-req-connecting-p95", 'k6_http_req_connecting_p95'),
    ("k6-req-tls-p95", 'k6_http_req_tls_handshaking_p95'),
    ("k6-data-received", 'rate(k6_data_received_total[1m])'),
    # 좌석 배정 건전성 — 실측으로 확인된 이름(Rate 는 _rate 접미사)
    ("k6-seat-conflict-rate", 'k6_e2e_seat_conflict_rate'),
    ("k6-seat-exhausted-rate", 'k6_e2e_seat_exhausted_rate'),
    ("k6-booking-created-rate", 'k6_e2e_booking_created_rate'),
    ("k6-seat-layouts-p95", 'k6_e2e_seat_layouts_duration_p95'),
    ("k6-booking-p95", 'k6_e2e_booking_duration_p95'),
    ("k6-browse-journey-p95", 'k6_e2e_browse_journey_duration_p95'),
    ("k6-vus", 'k6_vus'),
    ("k6-dropped-iterations", 'k6_dropped_iterations_total'),
    # 처리 축 (서버)
    ("server-rps-by-instance", 'sum by (instance) (rate(http_server_requests_seconds_count{job="ticketrush-services"}[1m]))'),
    ("seat-server-avg-ms", '1000 * (rate(http_server_requests_seconds_sum{instance="seat-service:8090"}[1m]) / rate(http_server_requests_seconds_count{instance="seat-service:8090"}[1m]))'),
    ("booking-server-avg-ms", '1000 * (rate(http_server_requests_seconds_sum{instance="booking-service:8090"}[1m]) / rate(http_server_requests_seconds_count{instance="booking-service:8090"}[1m]))'),
    ("server-p95-by-instance", 'histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket{job="ticketrush-services"}[5m])))'),
    # 게이트웨이
    ("gateway-rps", 'sum(rate(http_server_requests_seconds_count{job="gateway"}[1m]))'),
    ("gateway-5xx-rps", 'sum(rate(http_server_requests_seconds_count{job="gateway", status=~"5.."}[1m]))'),
    # 대기열 (#472 / ADR 0009). 게이트웨이의 http_server_requests 는 uri 라벨이 /** · UNKNOWN 으로
    # 뭉개져(#402 실측 카디널리티 4) 폴링 경로를 따로 볼 수 없다 — 아래 커스텀 지표가 유일한 수단이다.
    ("queue-waiting", 'ticketrush_queue_waiting{job="gateway"}'),
    # 상태 확인 RPS = 이 카운터의 합. 폴링 1회가 정확히 1증가라 별도 미터를 두지 않았다.
    ("queue-admission-rps", 'sum by (result) (rate(ticketrush_queue_admission_total{job="gateway"}[1m]))'),
    ("queue-admit-ratio", 'sum(rate(ticketrush_queue_admission_total{job="gateway", result="admitted"}[1m])) / sum(rate(ticketrush_queue_admission_total{job="gateway"}[1m]))'),
    ("queue-poll-interval", 'ticketrush_queue_poll_interval_seconds{job="gateway"}'),
    # result="unavailable" 이 0 이 아니면 fail-closed(ADR 0008)가 발동한 것이다 = 회차 무효.
    ("queue-entry-token", 'sum by (result) (rate(ticketrush_queue_entry_token_total{job="gateway"}[1m]))'),
    # 완료 조건의 핵심 축 — 유입이 1만이든 10만이든 이 값이 admit-rate 부근에서 평평해야 한다.
    ("booking-server-rps", 'sum(rate(http_server_requests_seconds_count{instance="booking-service:8090"}[1m]))'),
    # Redis 는 maxmemory 64mb + noeviction 이라 대기열이 예산을 넘기면 좌석 락 SET 까지 거절된다.
    # 지금까지 QUERIES 에 Redis 축이 없었는데, 대기열부터는 이게 회차 무효 판정 기준이다(48 MB).
    ("redis-mem-used", 'redis_memory_used_bytes'),
    ("k6-queue-status-p95", 'k6_queue_status_duration_p95'),
    ("k6-queue-wait-to-admit-p95", 'k6_queue_wait_to_admit_seconds_p95'),
    ("k6-queue-admitted-rate", 'k6_queue_admitted_rate'),
    ("k6-queue-status-unavailable-rate", 'k6_queue_status_unavailable_rate'),
    # 진입 실패. 실효 코호트 = 유입 - 이 값이고, 회차의 모든 비율이 그 코호트를 분모로 쓴다.
    # #549 는 이 축이 없어 724명(7.24%)을 http_req_failed 로 역산했다(#554 에서 신설).
    ("k6-queue-enqueue-failed", 'k6_queue_enqueue_failed_total'),
    # 병목 후보
    ("hikari-pending", 'hikaricp_connections_pending{job="ticketrush-services"}'),
    ("hikari-active", 'hikaricp_connections_active{job="ticketrush-services"}'),
    ("tomcat-busy", 'tomcat_threads_busy_threads{job="ticketrush-services"}'),
    ("outbox-backlog", 'ticketrush_outbox_backlog{job="ticketrush-services"}'),
    ("outbox-in-flight", 'ticketrush_outbox_in_flight{job="ticketrush-services"}'),
    ("kafka-consumer-lag", 'max by (instance, topic) (kafka_consumer_fetch_manager_records_lag{job="ticketrush-services"})'),
    ("gc-pause-rate", 'sum by (instance) (rate(jvm_gc_pause_seconds_sum{job="ticketrush-services"}[5m]))'),
    ("jvm-heap-used", 'sum by (instance) (jvm_memory_used_bytes{job="ticketrush-services", area="heap"})'),
    # #509 가설 검증용. OOM 시점 RSS 626 MiB 중 힙으로 설명되지 않는 몫이 242 MiB 였고,
    # 그 유력한 출처로 스레드 스택을 지목해 tomcat max-threads 를 200 → 50 으로 낮췄다.
    # 아래 셋이 그 가설의 증거다 — 스레드 수가 줄었는데도 RSS 가 안 내려가면 가설이 틀린 것이고,
    # 그때는 NMT 로 비힙 내역을 직접 떠야 한다.
    #   ⚠ nonheap 은 metaspace·코드캐시만 잡는다. 스레드 스택과 다이렉트 버퍼는 여기 없다 —
    #     그 몫은 container-mem-working-set 에서 힙·nonheap 을 뺀 잔차로만 추정된다.
    ("jvm-nonheap-used", 'sum by (instance) (jvm_memory_used_bytes{job="ticketrush-services", area="nonheap"})'),
    ("jvm-threads-live", 'jvm_threads_live_threads{job="ticketrush-services"}'),
    ("tomcat-threads-current", 'tomcat_threads_current_threads{job="ticketrush-services"}'),
    # 좌석 도메인
    ("seat-hold-total", 'sum by (result) (ticketrush_seat_hold_total)'),
    ("seat-lock-contention", 'ticketrush_seat_lock_contention_total'),
    # ── #403 좌석 상태 집계 ────────────────────────────────────────────────
    # uri 라벨은 템플릿 그대로다("/api/v1/seat/{performanceId}/seat-counts"). 정규식으로 잡는 것은
    # seat-layouts 와 나란히 두고 차분을 읽기 위해서다 — 두 경로의 DB 접근 행수는 같고 차이는
    # 응답 크기·직렬화뿐이라 그 차분이 곧 그 비용이다.
    ("seat-counts-server-rps", 'sum(rate(http_server_requests_seconds_count{instance="seat-service:8090", uri=~".*seat-counts"}[1m]))'),
    ("seat-counts-server-avg-ms", '1000 * sum(rate(http_server_requests_seconds_sum{instance="seat-service:8090", uri=~".*seat-counts"}[1m])) / sum(rate(http_server_requests_seconds_count{instance="seat-service:8090", uri=~".*seat-counts"}[1m]))'),
    ("seat-counts-server-p95", 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{instance="seat-service:8090", uri=~".*seat-counts"}[1m])))'),
    # #534 고정항 분해용. 위 server-avg-ms 에서 아래 둘을 빼면 프레임워크 몫(MVC 필터·직렬화)이 남는다.
    #   acquire = 풀에서 커넥션을 받아오기까지. 포화 전에는 거의 0 이고, 무릎에서 치솟는 값이다.
    #   usage   = getConnection ~ close 사이. 쿼리 실행 + 결과 매핑이 여기 들어간다.
    # ⚠ usage 는 seat-service 전체 경로가 섞인다(seatmap·hold 등). 부하 회차 중에는 seat-counts 가
    #   압도적이라 근사로 쓰지만, 다른 경로가 함께 도는 회차에서는 그대로 믿지 않는다.
    ("seat-counts-hikari-acquire-avg-ms", '1000 * sum(rate(hikaricp_connections_acquire_seconds_sum{instance="seat-service:8090"}[1m])) / sum(rate(hikaricp_connections_acquire_seconds_count{instance="seat-service:8090"}[1m]))'),
    ("seat-counts-hikari-usage-avg-ms", '1000 * sum(rate(hikaricp_connections_usage_seconds_sum{instance="seat-service:8090"}[1m])) / sum(rate(hikaricp_connections_usage_seconds_count{instance="seat-service:8090"}[1m]))'),
    ("k6-seat-counts-p95", 'k6_seat_counts_duration_p95'),
    ("k6-seat-counts-p99", 'k6_seat_counts_duration_p99'),
    ("k6-seat-counts-scale-mismatch", 'k6_seat_counts_scale_mismatch_rate'),
    ("k6-seat-layouts-compare-p95", 'k6_seat_layouts_duration_p95'),
    # ── #403 SSE 팬아웃 ───────────────────────────────────────────────────
    # 큐가 1000 에 붙는 시각과 pool_size 가 4 → 16 으로 늘어나는 시각을 함께 본다.
    # ThreadPoolTaskExecutor 는 큐가 다 찬 뒤에야 스레드를 늘리므로 이 순서가 뒤집히면 오독이다.
    ("sse-executor-queued", 'executor_queued_tasks{name="seatStatusSseExecutor"}'),
    ("sse-executor-active", 'executor_active_threads{name="seatStatusSseExecutor"}'),
    ("sse-executor-pool-size", 'executor_pool_size_threads{name="seatStatusSseExecutor"}'),
    ("sse-executor-completed-rate", 'rate(executor_completed_tasks_total{name="seatStatusSseExecutor"}[1m])'),
    # 발행 경로별 도착률(#520). 위 큐 깊이와 같은 창에 떠야 겹쳐 읽을 수 있다 — #403 은 큐가 평균 50 ·
    # 최대 307 까지 튀는 것을 봤지만 그 불균일의 출처를 가르지 못했다(§10). source 5종.
    ("sse-published-by-source", 'sum by (source) (rate(ticketrush_seat_sse_event_published_total[1m]))'),
    ("k6-sse-propagation-p95", 'k6_sse_propagation_ms_p95'),
    ("k6-sse-propagation-p99", 'k6_sse_propagation_ms_p99'),
    ("k6-sse-probe-booking-p95", 'k6_sse_probe_booking_duration_p95'),
    ("k6-sse-events-received-rate", 'rate(k6_sse_events_received_total[1m])'),
    ("k6-sse-connected-rate", 'rate(k6_sse_connected_total[1m])'),
    ("k6-sse-connection-closed-rate", 'rate(k6_sse_connection_closed_total[1m])'),
    ("k6-sse-mutate-created-rate", 'k6_sse_mutate_created_rate'),
    # ── #469 좌석맵 JSON 캐싱 ──────────────────────────────────────────────
    # 히트율의 분모에 failure 를 넣지 않는다 — 캐시 리포지토리가 장애를 miss 와 분리해 세는
    # 이유가 그것이다(SeatMapCacheRepository:43). 셋을 따로 떠서 리포트에서 조립한다.
    # ⚠ before 회차에서는 셋 다 비는 것이 정상이다 — 캐시가 아직 배포되지 않았다는 증적이다.
    ("seatmap-cache-rate", 'sum by (result) (rate(ticketrush_seat_seatmap_cache_total[1m]))'),
    ("seatmap-cache-total", 'sum by (result) (ticketrush_seat_seatmap_cache_total)'),
    # seat-counts 와 대칭으로 seat-layouts 서버 축을 뜬다. 캐시 히트는 DB·직렬화를 건너뛰므로
    # 개선은 k6 클라이언트 축보다 이 서버 축에서 먼저 보인다(회선 지연이 섞이지 않는다).
    ("seat-layouts-server-rps", 'sum(rate(http_server_requests_seconds_count{instance="seat-service:8090", uri=~".*seat-layouts"}[1m]))'),
    ("seat-layouts-server-avg-ms", '1000 * sum(rate(http_server_requests_seconds_sum{instance="seat-service:8090", uri=~".*seat-layouts"}[1m])) / sum(rate(http_server_requests_seconds_count{instance="seat-service:8090", uri=~".*seat-layouts"}[1m]))'),
    ("seat-layouts-server-p95", 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{instance="seat-service:8090", uri=~".*seat-layouts"}[1m])))'),
    # Redis 예산(#469 완료조건). maxmemory 64mb + noeviction 이라 상한에 닿으면 캐시 SET 이
    # 거절되는 데서 그치지 않고 좌석 락 SET 까지 막힌다(fail-closed 503, ADR 0008).
    # used 만 보면 그 위험이 안 보이므로 max 와 캐시 failure 카운터를 같은 창에 둔다.
    ("redis-memory-used", 'redis_memory_used_bytes'),
    ("redis-memory-max", 'redis_memory_max_bytes'),
    ("redis-memory-used-pct", '100 * redis_memory_used_bytes / redis_memory_max_bytes'),
    # ── #532 SSE 큐 역압 ──────────────────────────────────────────────────
    # rejected 는 '0 이어야 정상'이라 no data 와 0 을 구분해야 한다 — 카운터가 생성자에서
    # 등록돼 있어 시계열 자체는 존재하고 값이 0 으로 평평한 것이 통과 증적이다.
    ("sse-rejected-total", 'ticketrush_seat_sse_event_rejected_total'),
    ("sse-caller-runs-total", 'ticketrush_seat_sse_event_caller_runs_total'),
    ("sse-caller-runs-rate", 'rate(ticketrush_seat_sse_event_caller_runs_total[1m])'),
]


def fetch(expr):
    url = "http://localhost:9090/api/v1/query_range?" + urllib.parse.urlencode(
        {"query": expr, "start": START, "end": END, "step": STEP}
    )
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


OUTDIR.mkdir(parents=True, exist_ok=True)
empty, ok = [], 0
for slug, expr in QUERIES:
    try:
        data = fetch(expr)
        series = data["data"]["result"]
    except Exception as e:  # 쿼리 실패도 증적으로 남긴다
        print(f"  ERROR   {slug}: {e}")
        continue
    if not series:
        empty.append(slug)
        continue
    path = OUTDIR / f"timeseries-{slug}{SUFFIX}.json"
    path.write_text(json.dumps({"query": expr, "start": START, "end": END,
                                "step": STEP, "data": series},
                               ensure_ascii=False, indent=1), encoding="utf-8")
    pts = sum(len(s["values"]) for s in series)
    print(f"  OK      {slug:26s} series={len(series):2d} points={pts}")
    ok += 1

print(f"\n저장 {ok}건 -> {OUTDIR}")
if empty:
    print("시계열 없음(쿼리는 성공, 데이터 부재):", ", ".join(empty))
