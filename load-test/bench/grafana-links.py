#!/usr/bin/env python3
"""Grafana Explore 캡처 링크를 만든다.

    python load-test/bench/grafana-links.py <시작UTC> <종료UTC> <arm접미사>

Grafana 에 이미지 렌더러 플러그인이 없어 PNG 를 서버가 못 만든다 — 링크를 열어 손으로 캡처한다.
쿼리·시간범위를 URL 에 박아 두면 손으로 맞출 일이 없고, 두 arm 을 같은 창으로 찍게 된다.

URL 인코딩을 손으로 하면 틀린다(#549 의 링크 파일은 한 줄이 400자가 넘는다).
"""
import json
import sys
from datetime import datetime, timezone
from urllib.parse import quote

# (파일명 슬러그, 제목, [(refId, expr, 주의)])
# 스케일이 다른 지표를 한 패널에 넣으면 작은 쪽이 0 에 붙는다 — 큰 쪽을 쿼리에서 나눠 얹는다(#549).
PANELS = [
    ("booking-rps-vs-vus", "예매 서버 RPS vs VU", [
        ("A", 'sum(rate(http_server_requests_seconds_count{instance="booking-service:8090"}[1m]))', ""),
        ("B", "k6_vus / 500", "B 는 실제 VU ÷ 500 (2.0 = 1,000 VU)"),
    ]),
    ("gateway-rps", "게이트웨이 총 RPS — 대기열의 자기 비용", [
        ("A", 'sum(rate(http_server_requests_seconds_count{instance="gateway-service:8090"}[1m]))', ""),
    ]),
    ("host-cpu", "호스트 CPU", [
        ("A", '100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100)', "무부하 기저는 약 7%"),
    ]),
    ("queue-admission", "대기열 승급 — result 별", [
        ("A", 'sum by (result) (rate(ticketrush_queue_admission_total{job="gateway"}[1m]))', ""),
    ]),
    ("container-mem", "컨테이너 메모리 % — seat/booking/gateway", [
        ("A", '100 * ticketrush_container_memory_usage_bytes{container=~"seat-service|booking-service|gateway-service"}'
              ' / ticketrush_container_memory_limit_bytes{container=~"seat-service|booking-service|gateway-service"}',
         "라벨은 name= 이 아니라 container= 다"),
    ]),
    # 앱 컨테이너만 찍으면 인프라 쪽 압박을 놓친다 — #554 OFF arm 에서 MySQL 이 100% 에 닿았는데
    # 앱 3개만 필터한 위 패널에는 그 사실이 남지 않았다.
    ("container-mem-infra", "컨테이너 메모리 % — MySQL/Kafka/Redis", [
        ("A", '100 * ticketrush_container_memory_usage_bytes{container=~"ticketrush-mysql|ticketrush-kafka|ticketrush-redis"}'
              ' / ticketrush_container_memory_limit_bytes{container=~"ticketrush-mysql|ticketrush-kafka|ticketrush-redis"}',
         "MySQL 이 100% 에 닿아도 OOM kill 이 0 이면 정상 운영값이다 — kill 카운터와 함께 읽는다"),
    ]),
    ("hikari-tomcat", "HikariCP active/pending · Tomcat busy", [
        ("A", 'hikaricp_connections_active{job="ticketrush-services"}', ""),
        ("B", 'hikaricp_connections_pending{job="ticketrush-services"}', ""),
        ("C", 'tomcat_threads_busy_threads{job="ticketrush-services"}', "seat 는 max 50, 나머지 200"),
    ]),
    ("connections", "동시 커넥션 (Tcp_CurrEstab)", [
        ("A", "node_netstat_Tcp_CurrEstab", ""),
    ]),
    ("outbox-backlog", "outbox backlog — 무너진 뒤 회복", [
        ("A", 'ticketrush_outbox_backlog{job="ticketrush-services"}', ""),
    ]),
    ("redis-mem", "Redis 메모리 (무효 기준 48MB)", [
        ("A", "redis_memory_used_bytes", ""),
    ]),
]


def epoch_ms(s: str) -> int:
    return int(datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ")
               .replace(tzinfo=timezone.utc).timestamp() * 1000)


def link(queries, start_ms: int, end_ms: int) -> str:
    ds = {"type": "prometheus", "uid": "prometheus"}
    pane = {
        "a": {
            "datasource": "prometheus",
            "queries": [{"refId": r, "expr": e, "datasource": ds} for r, e, _ in queries],
            "range": {"from": str(start_ms), "to": str(end_ms)},
        }
    }
    panes = quote(json.dumps(pane, separators=(",", ":")), safe="")
    return f"http://localhost:3000/explore?orgId=1&schemaVersion=1&panes={panes}"


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    start, end, arm = sys.argv[1], sys.argv[2], sys.argv[3]
    s, e = epoch_ms(start), epoch_ms(end)

    print(f"## {arm.upper()} arm — {start} ~ {end}\n")
    for slug, title, queries in PANELS:
        print(f"### {title}")
        print(f"저장 파일명: `graph-{slug}-{arm}.png`\n")
        for _, _, note in queries:
            if note:
                print(f"> {note}")
        print(f"\n{link(queries, s, e)}\n")


if __name__ == "__main__":
    main()
