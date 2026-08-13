#!/usr/bin/env python3
"""회차 arm 하나의 판정 수치를 덤프에서 계산한다 (#554 ON/OFF 대조).

    python load-test/bench/arm-stats.py <덤프디렉터리> <arm접미사> <시작UTC> [--window 초 초]

손으로 세면 틀린다 — #554 는 arm 마다 판정 창이 다르고(ON t+300~500s, OFF t+30~300s),
DEFERRAL_RATIO 는 '램프 종료 이후 예매 비율' 이라 시각 기준이 하나만 어긋나도 결론이 뒤집힌다.
15초 샘플러의 중앙값을 '최대' 로 쓰는 사고(§16.6, 묶음 C 에서 두 번)도 여기서 막는다.

읽기 전용이다. 덤프 파일을 고치지 않는다.
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

# KNEE_WATCH — metadata.txt 에 회차 전에 못 박은 임계와 지속 조건.
# (덤프슬러그, 표시명, 임계, 연속표본수, 비교방향)
KNEE_WATCH = [
    ("node-cpu", "호스트 CPU >= 90%", 90.0, 3, "ge"),
    ("gateway-5xx-rps", "게이트웨이 5xx >= 1/s", 1.0, 2, "ge"),
    ("server-p95-by-instance", "booking p95 >= 150ms", 0.150, 2, "ge"),
    ("k6-req-connecting-p95", "connecting p95 >= 100ms", 100.0, 2, "ge"),
    ("hikari-pending", "HikariCP pending >= 1", 1.0, 2, "ge"),
    ("tomcat-busy", "Tomcat busy >= 0.8 x max", 160.0, 2, "ge"),  # seat-service 는 40 (아래 예외)
    # ⚠ container-mem-limit-pct >= 90% 는 쓰지 않는다. #549 B-2(유효 회차)에서도 booking-service 가
    #   98.34% 였다 — ADR 0006 의 mem_limit 설계상 정상 운영값이라 이 임계는 상시 참이다.
    #   진짜 신호는 한계를 넘어 죽는 것이므로 OOM kill 로 바꾼다.
    ("container-oom-kills", "컨테이너 OOM kill 발생", 0.0, 1, "gt"),
    ("redis-mem-used", "Redis >= 48MB", 48 * 1024 * 1024, 1, "ge"),
    ("k6-queue-status-unavailable-rate", "status 503 발생", 0.0, 1, "gt"),
]


def load(outdir: Path, slug: str, arm: str):
    """timeseries-<slug>-<arm>.json 을 (label, [(epoch, value)]) 목록으로 편다."""
    path = outdir / f"timeseries-{slug}-{arm}.json"
    if not path.exists():
        return []
    raw = json.loads(path.read_text(encoding="utf-8"))
    out = []
    for series in raw.get("data", []):
        m = series["metric"]
        # container 가 먼저다 — 컨테이너 메모리 축은 instance 가 전부 node-exporter 하나라
        # instance 를 쓰면 16개 시계열이 같은 이름으로 찍혀 어느 컨테이너인지 못 읽는다.
        label = m.get("container") or m.get("instance") or m.get("name") or ""
        pts = [(int(t), float(v)) for t, v in series["values"]]
        out.append((label, pts))
    return out


def window(pts, t0: int, lo: int, hi: int):
    return [(t, v) for t, v in pts if t0 + lo <= t <= t0 + hi]


def describe(pts, title: str):
    if not pts:
        return f"  {title:38s} (표본 없음)"
    v = [x[1] for x in pts]
    return (
        f"  {title:38s} 표본{len(v):4d}  min {min(v):10.2f}  "
        f"avg {sum(v)/len(v):10.2f}  max {max(v):10.2f}"
    )


def deferral_ratio(pts, t0: int, ramp_end: int, step: int = 15):
    """램프 종료 이후 요청 수 / 전체 요청 수.

    RPS 시계열이라 각 점에 step 을 곱해 건수로 환산한다. 절대 정확한 적분은 아니지만
    두 arm 에 같은 방식을 쓰므로 대조에는 성립한다 — 예측이 43% vs 3.3%(13배)라
    적분 오차가 결론을 바꾸지 않는다.
    """
    if not pts:
        return None
    total = sum(v for _, v in pts) * step
    after = sum(v for t, v in pts if t >= t0 + ramp_end) * step
    if total <= 0:
        return None
    return after / total * 100.0, after, total


def knee(outdir: Path, arm: str, t0: int):
    """임계를 가장 먼저 교차한 지표. 없으면 None."""
    hits = []
    for slug, name, thr, need, op in KNEE_WATCH:
        for label, pts in load(outdir, slug, arm):
            # seat-service 만 tomcat max-threads 가 50 이라 임계가 다르다(application.yml:33).
            # 나머지는 미설정 = 기본 200. 0.8 배가 각각 40 / 160 이다.
            if slug == "tomcat-busy" and "seat-service" in label:
                thr_i, name_i = 40.0, "Tomcat busy >= 40 (seat, max 50)"
            else:
                thr_i, name_i = thr, name
            run = 0
            for t, v in pts:
                crossed = v >= thr_i if op == "ge" else v > thr_i
                run = run + 1 if crossed else 0
                if run >= need:
                    hits.append((t, name_i, label, v))
                    break
    if not hits:
        return None
    return min(hits, key=lambda x: x[0])


def k6_summary(outdir: Path, arm: str):
    """k6 요약에서 대조표에 들어가는 커스텀 지표를 뽑는다.

    서버 시계열에 없는 것들이다 — 실효 코호트(queue_enqueue_failed)·사용자당 폴링 횟수·
    대기 시간 p95 는 클라이언트만 안다. 16개 항목을 눈으로 옮기면 틀린다.
    """
    path = outdir / f"k6-summary-{arm}.txt"
    if not path.exists():
        return
    want = (
        "queue_admitted", "queue_booking_ok", "queue_booking_forbidden",
        "queue_status_unavailable", "queue_polls_per_user", "queue_wait_to_admit_seconds",
        "queue_status_duration", "queue_polls_exhausted", "queue_enqueue_failed",
        "http_reqs", "http_req_failed", "http_req_duration", "iterations", "vus_max",
        "data_sent",
    )
    print("\n[k6 요약 — 클라이언트 측정]")
    seen = set()
    for line in path.read_text(encoding="utf-8", errors="replace").replace("\r", "").splitlines():
        s = line.strip()
        # THRESHOLDS 절에도 지표 이름이 헤더로 한 번 나온다. 값 줄만 집는다(이름 뒤 점선 + 콜론).
        if ":" not in s:
            continue
        name = s.split(".")[0].strip()
        if name in want and name not in seen:
            seen.add(name)
            print(f"  {s}")
    # 0 인 Counter 는 k6 가 출력하지 않는다 — 없는 것과 0 을 구분해 적는다.
    for miss in ("queue_enqueue_failed", "queue_polls_exhausted"):
        if miss not in seen:
            print(f"  {miss:30s} 요약에 없음 = 0건 (k6 는 값이 0인 Counter 를 출력하지 않는다)")


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(2)
    outdir, arm, start = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    t0 = int(datetime.strptime(start, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp())

    lo, hi = (300, 500) if arm == "on" else (30, 300)
    if "--window" in sys.argv:
        i = sys.argv.index("--window")
        lo, hi = int(sys.argv[i + 1]), int(sys.argv[i + 2])

    print(f"=== arm={arm}  t0={start}  판정창 t+{lo}s~t+{hi}s ===\n")

    print("[주 판정축]")
    booking = load(outdir, "booking-server-rps", arm)
    if booking:
        pts = booking[0][1]
        print(describe(window(pts, t0, lo, hi), "예매 서버 RPS (판정창)"))
        print(describe(pts, "예매 서버 RPS (회차 전체)"))
        d = deferral_ratio(pts, t0, 300)
        if d:
            print(f"  {'DEFERRAL_RATIO (t+300s 이후 비율)':38s} {d[0]:6.2f}%   "
                  f"(이후 {d[1]:.0f}건 / 전체 {d[2]:.0f}건)")

    print("\n[자원 축 — 공통 비교 창 t+30s~t+300s]")
    for slug, title in [
        ("node-cpu", "호스트 CPU %"),
        ("redis-mem-used", "Redis bytes"),
        ("outbox-backlog", "outbox backlog"),
    ]:
        for label, pts in load(outdir, slug, arm):
            print(describe(window(pts, t0, 30, 300), f"{title} {label}".strip()))

    print("\n[인스턴스별 — 공통 창]")
    for slug, title in [
        ("hikari-active", "Hikari active"),
        ("hikari-pending", "Hikari pending"),
        ("tomcat-busy", "Tomcat busy"),
        ("container-mem-limit-pct", "컨테이너 mem %"),
    ]:
        for label, pts in load(outdir, slug, arm):
            w = window(pts, t0, 30, 300)
            if w and max(x[1] for x in w) > 0:
                print(describe(w, f"{title} {label}"))

    k6_summary(outdir, arm)

    print("\n[꺾이는 지점]")
    k = knee(outdir, arm, t0)
    if k:
        t, name, label, v = k
        rel = t - t0
        print(f"  최초 포화: {name} ({label}) 값 {v:.2f} — t+{rel}s "
              f"({datetime.utcfromtimestamp(t).strftime('%H:%M:%SZ')})")
        for slug, title in [("k6-vus", "VU"), ("booking-server-rps", "예매 RPS"),
                            ("gateway-rps", "게이트웨이 RPS")]:
            for _, pts in load(outdir, slug, arm):
                at = [v for tt, v in pts if abs(tt - t) <= 15]
                if at:
                    print(f"    {title}: {at[0]:.1f}")
    else:
        print("  KNEE=N/A — 임계 교차 없음. 도달한 최대 동작점:")
        for slug, title in [("k6-vus", "VU max"), ("booking-server-rps", "예매 RPS max"),
                            ("gateway-rps", "게이트웨이 RPS max"), ("node-cpu", "호스트 CPU max")]:
            for _, pts in load(outdir, slug, arm):
                if pts:
                    print(f"    {title}: {max(v for _, v in pts):.2f}")


if __name__ == "__main__":
    main()
