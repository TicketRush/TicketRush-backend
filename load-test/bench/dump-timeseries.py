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
    # 컨테이너별 메모리는 여기 없다 — 수단이 아직 없다(#515). #509 에서 cAdvisor 로 넣으려 했으나
    # Docker 29 + cgroup v2 + systemd 조합에서 컨테이너를 열거하지 못해 되돌렸다.
    # 그때까지 컨테이너 메모리는 부하 중 `docker stats`, OOM 판정은 `dmesg | grep CONSTRAINT_MEMCG`
    # 로 본다(런북 §13.6). 회차 리포트에는 그 한계를 명시할 것.
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
