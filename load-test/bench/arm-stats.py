#!/usr/bin/env python3
"""회차 arm 하나의 판정 수치를 덤프에서 계산한다 (#554 ON/OFF 대조).

    python load-test/bench/arm-stats.py <덤프디렉터리> <arm접미사> <시작UTC>
                                        [--window 초 초] [--ramp-end 초]

계단 회차(#555)는 arm 이 2개가 아니라 계단 N개이고 계단마다 t0 가 다르다(계단 하나 = 회차 하나 —
QUEUE_ADMIT_RATE 가 서버값이라 반영에 게이트웨이 재생성이 필요하다). 가로 대조표:

    python load-test/bench/arm-stats.py <덤프디렉터리> --table a20=<UTC> a40=<UTC> ...
                                        [--ramp-end 초] [--span 초]

손으로 세면 틀린다 — #554 는 arm 마다 판정 창이 다르고(ON t+300~500s, OFF t+30~300s),
DEFERRAL_RATIO 는 '램프 종료 이후 예매 비율' 이라 시각 기준이 하나만 어긋나도 결론이 뒤집힌다.
15초 샘플러의 중앙값을 '최대' 로 쓰는 사고(§16.6, 묶음 C 에서 두 번)도 여기서 막는다.

읽기 전용이다. 덤프 파일을 고치지 않는다.
"""
import json
import re
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
    # #554 가 임계로 선언해 놓고 재지 못한 축이다(report §5.4 — "임계를 정해 놓고 재지 못하면
    # 기준이 아니다"). 값이 비율(0-1)이라 임계도 0.01 이다.
    ("k6-req-failed-ratio", "k6 실패율 >= 1%", 0.01, 2, "ge"),
    # #555 여정 보강으로 SSE 가 부하 축에 들어온다. 팬아웃은 (구독자 x 이벤트율)이고 둘 다
    # admit rate 에 비례하므로 admit 의 제곱으로 큰다 — 무릎이 admit 이 아니라 SSE 일 때
    # 그 사실이 이름으로 나와야 한다. 800 = queueCapacity 1000 의 0.8 배(tomcat-busy 와 같은 선).
    ("sse-executor-queued", "SSE 큐 >= 800 (capacity 1000)", 800.0, 2, "ge"),
    ("sse-rejected-total", "SSE 이벤트 거절 발생", 0.0, 1, "gt"),
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


def knee(outdir: Path, arm: str, t0: int, ramp_end: int = 300):
    """임계를 가장 먼저 교차한 지표. 없으면 None.

    ramp_end = 램프(유입) 종료 시각(초). 그 이전 표본은 보지 않는다.

    #554 에서 `booking p95 >= 150ms` 가 t+42~87s 에 걸렸는데 포화가 아니라 JIT 워밍업이었다
    (report §4.5 — 같은 구간에서 VU 는 917 -> 10,000 으로 10배 늘었는데 p95 는 219 -> 132ms 로
    단조 감소했다. 101표본 중 4개만 넘었다). 임계값을 올리면 진짜 포화도 놓치므로 구간을 자른다.

    상한은 두지 않는다 — 소화 구간 끝까지가 관측 대상이다.
    """
    hits = []
    for slug, name, thr, need, op in KNEE_WATCH:
        for label, pts in load(outdir, slug, arm):
            pts = [p for p in pts if p[0] >= t0 + ramp_end]
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
        # #555 계단 회차. queue_drain_seconds 의 max 가 이 회차의 주 지표(전원 소화 시간)다 —
        # Trend 는 시계열로 p95 만 나가므로 max 는 이 요약이 유일한 출처다.
        "queue_drain_seconds", "queue_post_admit_seconds", "queue_seatmap_duration",
        "queue_seatmap_ok", "queue_sse_connect_duration", "queue_sse_subscribe_failed",
        "queue_sse_connection_error", "queue_cohort_exhausted",
        "http_reqs", "http_req_failed", "http_req_duration", "iterations", "vus_max",
        "dropped_iterations", "data_sent",
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
    for miss in ("queue_enqueue_failed", "queue_polls_exhausted", "queue_cohort_exhausted",
                 "queue_sse_subscribe_failed", "queue_sse_connection_error",
                 "dropped_iterations"):
        if miss not in seen:
            print(f"  {miss:30s} 요약에 없음 = 0건 (k6 는 값이 0인 Counter 를 출력하지 않는다)")


def summary_field(outdir: Path, arm: str, metric: str, field: str):
    """k6 요약에서 <metric> 줄의 <field>=<수> 를 뽑는다. 없으면 None.

    Trend 의 max 는 시계열로 나가지 않는다(remote-write 는 p95 계열만 낸다). #555 의 주 지표인
    '전원 소화 시간' = queue_drain_seconds 의 max 라, 이 요약이 그 값의 유일한 출처다.
    """
    path = outdir / f"k6-summary-{arm}.txt"
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8", errors="replace").replace("\r", "").splitlines():
        s = line.strip()
        if ":" not in s or s.split(".")[0].strip() != metric:
            continue
        m = re.search(rf"\b{field}=([0-9.]+)", s)
        return float(m.group(1)) if m else None
    return None


def series_stat(outdir: Path, slug: str, arm: str, t0: int, lo: int, hi: int, how: str = "max"):
    """한 슬러그의 모든 시계열을 합쳐 창 안의 통계 하나를 낸다. 표본이 없으면 None."""
    vals = []
    for _, pts in load(outdir, slug, arm):
        vals += [v for _, v in window(pts, t0, lo, hi)]
    if not vals:
        return None
    return max(vals) if how == "max" else sum(vals) / len(vals)


def table(outdir: Path, arms, ramp_end: int, span: int):
    """계단(arm)을 가로로 놓는 대조표. #555 는 계단마다 별도 회차라 t0 가 계단마다 다르다.

    ⚠ 예매 RPS 만 보고 계단을 비교하면 안 된다 — 폴링 RPS 는 waiting >= 1,200 인 동안 R 에
    고정되므로(pollSeconds = ceil(waiting/R), min 3 / max 60) admit 을 올리면 대기가 빨리
    빠져 폴링 총량이 오히려 줄어든다. 계단마다 CPU 예산의 분모가 달라진다는 뜻이라,
    폴링·게이트웨이 RPS 를 같은 표에 둔다.
    """
    print(f"=== 계단 대조표  판정창 t+{ramp_end}s ~ t+{span}s ===\n")
    head = (f"{'arm':>8}  {'예매RPS avg/max':>16}  {'소화s(max)':>10}  {'폴링RPS':>8}  "
            f"{'GW RPS':>7}  {'CPU avg/max':>13}  {'dropped':>7}  {'vus_max':>7}  KNEE")
    print(head)
    print("-" * len(head))
    for arm, start in arms:
        t0 = int(datetime.strptime(start, "%Y-%m-%dT%H:%M:%SZ")
                 .replace(tzinfo=timezone.utc).timestamp())
        b_avg = series_stat(outdir, "booking-server-rps", arm, t0, ramp_end, span, "avg")
        b_max = series_stat(outdir, "booking-server-rps", arm, t0, ramp_end, span)
        drain = summary_field(outdir, arm, "queue_drain_seconds", "max")
        poll = series_stat(outdir, "queue-admission-rps", arm, t0, ramp_end, span)
        gw = series_stat(outdir, "gateway-rps", arm, t0, ramp_end, span)
        c_avg = series_stat(outdir, "node-cpu", arm, t0, ramp_end, span, "avg")
        c_max = series_stat(outdir, "node-cpu", arm, t0, ramp_end, span)
        dropped = summary_field(outdir, arm, "dropped_iterations", "count") or 0.0
        vus = summary_field(outdir, arm, "vus_max", "max")
        k = knee(outdir, arm, t0, ramp_end)
        knee_txt = f"{k[1]} t+{k[0] - t0}s" if k else "N/A"

        def f(v, w, p=2):
            return f"{v:{w}.{p}f}" if v is not None else " " * (w - 1) + "-"

        print(f"{arm:>8}  {f(b_avg, 7)}/{f(b_max, 7)}  {f(drain, 10, 1)}  {f(poll, 8, 1)}  "
              f"{f(gw, 7, 1)}  {f(c_avg, 6)}/{f(c_max, 5)}  {dropped:7.0f}  {f(vus, 7, 0)}  {knee_txt}")

    print("\n⚠ dropped > 0 이면 vus_max 를 회차 전에 적어 둔 VU_MODEL_PEAK 와 대조한다 —")
    print("  모델에 붙어 있으면 생성기 VU 부족(회차 무효)이고, 여유가 있는데 dropped 면 대상 포화다.")
    print("  queue_post_admit_seconds 가 함께 부풀었는지가 그 판정의 보조축이다.")


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(2)

    if "--table" in sys.argv:
        outdir = Path(sys.argv[1])
        ramp_end, span = 300, 3600
        if "--ramp-end" in sys.argv:
            ramp_end = int(sys.argv[sys.argv.index("--ramp-end") + 1])
        if "--span" in sys.argv:
            span = int(sys.argv[sys.argv.index("--span") + 1])
        arms = []
        for tok in sys.argv[sys.argv.index("--table") + 1:]:
            if tok.startswith("--"):
                break
            if "=" not in tok:
                print(f"계단 인자는 <arm>=<시작UTC> 형식이어야 한다: '{tok}'")
                sys.exit(2)
            arm, start = tok.split("=", 1)
            arms.append((arm, start))
        if not arms:
            print(__doc__)
            sys.exit(2)
        table(outdir, arms, ramp_end, span)
        return
    outdir, arm, start = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    t0 = int(datetime.strptime(start, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp())

    lo, hi = (300, 500) if arm == "on" else (30, 300)
    if "--window" in sys.argv:
        i = sys.argv.index("--window")
        lo, hi = int(sys.argv[i + 1]), int(sys.argv[i + 2])

    # 램프(유입) 종료 시각. KNEE 판정과 DEFERRAL_RATIO 가 함께 쓴다.
    # ⚠ 기본값 300 은 #554 형상(램프 5분) 이다. 유입 형상이 다른 회차는 반드시 넘겨라 —
    #   안 넘기면 램프 구간이 판정에 섞여 JIT 워밍업을 포화로 읽는다(#554 §4.5).
    ramp_end = 300
    if "--ramp-end" in sys.argv:
        ramp_end = int(sys.argv[sys.argv.index("--ramp-end") + 1])

    print(f"=== arm={arm}  t0={start}  판정창 t+{lo}s~t+{hi}s  램프종료 t+{ramp_end}s ===\n")

    print("[주 판정축]")
    booking = load(outdir, "booking-server-rps", arm)
    if booking:
        pts = booking[0][1]
        print(describe(window(pts, t0, lo, hi), "예매 서버 RPS (판정창)"))
        print(describe(pts, "예매 서버 RPS (회차 전체)"))
        d = deferral_ratio(pts, t0, ramp_end)
        if d:
            print(f"  {f'DEFERRAL_RATIO (t+{ramp_end}s 이후 비율)':38s} {d[0]:6.2f}%   "
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

    print("\n[무너진 뒤 회복 — outbox backlog]")
    # 이슈 #554 의 관측 축이고 #549 에 선례가 없다. 회차 종료 후 backlog 가 언제 0 으로
    # 돌아오는지가 '회복' 이다. 덤프 창이 소진 시점까지 덮여 있어야 값이 나온다.
    for slug in ("outbox-backlog",):
        for label, pts in load(outdir, slug, arm):
            if not pts or max(v for _, v in pts) == 0:
                continue
            peak_t, peak = max(pts, key=lambda x: x[1])
            drained = next((t for t, v in pts if t > peak_t and v == 0), None)
            line = (f"  {label:26s} peak {peak:6.0f} (t+{peak_t - t0}s)")
            if drained:
                line += f"  → 0 도달 t+{drained - t0}s  (peak 이후 {drained - peak_t}s)"
            else:
                line += f"  → 창 끝({pts[-1][0] - t0}s)까지 0 미도달, 마지막 {pts[-1][1]:.0f}"
            print(line)

    k6_summary(outdir, arm)

    print(f"\n[꺾이는 지점 — 램프 종료(t+{ramp_end}s) 이후만 본다]")
    k = knee(outdir, arm, t0, ramp_end)
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
