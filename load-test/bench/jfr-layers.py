"""JFR ExecutionSample 을 계층별로 분류해 CPU 시간 비율을 낸다(#534).

사용:
  jfr print --events jdk.ExecutionSample --json <파일>.jfr > es.json
  python load-test/bench/jfr-layers.py es.json [START_UTC] [END_UTC] [워커접두사]

  예: python load-test/bench/jfr-layers.py es.json \
        2026-07-31T18:29:49 2026-07-31T18:33:49

START/END 는 계단 구간으로 준다(앞뒤 30초를 잘라낸 창). 안 주면 녹화 전체를 본다 —
그러면 앱 기동 구간의 클래스 로딩·JIT 샘플이 섞이므로 반드시 주는 편이 낫다.

top frame(실제로 CPU 를 쓰고 있던 지점)의 패키지로 귀속하되, 그것이 HashMap·String 같은
저수준이면 호출자 프레임을 훑어 처음 만나는 실계층에 귀속한다. 피호출자 기준이라야
"어느 계층이 시간을 쓰는가" 에 답한다.

⚠ JFR 기본 stackdepth 로는 Spring 스택이 잘려(#534 회차에서 depth 5) 컨트롤러/UseCase
  프레임이 남지 않는다. 그래서 경로를 스택으로 격리하지 못하고 워커 스레드 필터로 대신한다.
  다음에 프로파일링할 때는 -XX:FlightRecorderOptions=stackdepth=256 을 함께 준다.
"""

import json
import sys
from collections import Counter

# 위에서부터 먼저 맞는 것으로 분류한다. 순서가 의미를 갖는다.
LAYERS = [
    ("DB-소켓", ("java/net/Socket", "sun/nio/ch", "sun/security/ssl", "java/net/SocketInputStream")),
    ("MySQL-드라이버", ("com/mysql",)),
    ("커넥션풀", ("com/zaxxer/hikari",)),
    ("Hibernate/JPA", ("org/hibernate", "jakarta/persistence", "org/springframework/orm",
                       "org/springframework/data/jpa", "org/springframework/transaction")),
    ("Jackson-직렬화", ("com/fasterxml/jackson",)),
    ("Spring-Security", ("org/springframework/security",)),
    ("Tomcat/서블릿", ("org/apache/catalina", "org/apache/coyote", "org/apache/tomcat",
                       "jakarta/servlet")),
    ("Spring-MVC", ("org/springframework/web", "org/springframework/boot/actuate")),
    ("Spring-AOP/프록시", ("org/springframework/aop", "org/springframework/cglib",
                            "jdk/proxy")),
    ("Spring-기타", ("org/springframework",)),
    ("앱코드", ("com/ticketrush",)),
    ("JDK-기타", ("java/", "jdk/", "sun/")),
]

SEAT_COUNTS_MARKERS = ("SeatGetStatusCountsUseCase", "getSeatStatusCounts",
                       "getPerformanceSeatStatusCounts", "getStatusCountsByPerformanceId")


def frame_class(frame):
    t = frame.get("method", {}).get("type") or {}
    return (t.get("name") or "").replace(".", "/")


def frame_method(frame):
    m = frame.get("method", {})
    return f"{frame_class(frame)}.{m.get('name', '?')}"


def classify(cls):
    for label, prefixes in LAYERS:
        if any(cls.startswith(p) for p in prefixes):
            return label
    return "기타"


def main(path, start=None, end=None, worker="http-nio-8086-exec-"):
    """start/end 는 ISO8601 문자열(예: 2026-07-31T18:23:34Z). 문자열 비교로 자른다.

    ⚠ 스택 depth 가 5 로 잘려 있어(JFR 기록 확인) 컨트롤러/UseCase 프레임이 남지 않는다.
    그래서 "이 샘플이 seat-counts 요청인가" 를 스택으로 판별할 수 없다. 대신 회차 구간의
    톰캣 워커 스레드(앱 포트 8086)만 고른다 — 그 구간 워커는 seat-counts 만 처리하므로
    등가다. 관리 포트(8090) 워커는 Prometheus 스크랩이라 반드시 제외한다.
    """
    events = json.load(open(path, encoding="utf-8"))["recording"]["events"]

    total = Counter()
    seat = Counter()
    seat_top = Counter()
    threads = Counter()
    n_seat = 0

    for e in events:
        v = e["values"]
        frames = (v.get("stackTrace") or {}).get("frames") or []
        if not frames:
            continue
        ts = v.get("startTime", "")
        if start and ts < start:
            continue
        if end and ts > end:
            continue

        # top frame 이 HashMap·String 같은 저수준이면 그 자체로는 소속을 모른다.
        # 아래(호출자) 프레임을 훑어 처음 만나는 실계층에 귀속한다. depth 가 5 로 잘려 있어
        # 그래도 못 찾는 것이 남는데, 그건 "미분류" 로 남긴다 — 억지로 배분하지 않는다.
        layer = "미분류"
        for f in frames:
            cand = classify(frame_class(f))
            if cand not in ("JDK-기타", "기타"):
                layer = cand
                break
        total[layer] += 1
        tname = v.get("sampledThread", {}).get("javaName", "?")
        threads[tname] += 1

        if tname.startswith(worker):
            n_seat += 1
            seat[layer] += 1
            seat_top[frame_method(frames[0])] += 1

    def table(title, counter, n):
        print(f"\n=== {title} (n={n}) ===")
        if not n:
            print("  샘플 없음")
            return
        for label, c in counter.most_common():
            print(f"  {label:<16} {c:>6}  {100.0 * c / n:6.2f}%")

    table("전체 샘플 계층 분포", total, sum(total.values()))
    table("seat-counts 경로 샘플만", seat, n_seat)

    print(f"\n=== seat-counts 경로 top-frame 상위 15 (n={n_seat}) ===")
    for m, c in seat_top.most_common(15):
        print(f"  {c:>5}  {m}")

    print("\n=== 스레드별 샘플 상위 10 ===")
    for t, c in threads.most_common(10):
        print(f"  {c:>6}  {t}")


if __name__ == "__main__":
    a = sys.argv[1:]
    main(
        a[0] if len(a) > 0 else "es.json",
        start=a[1] if len(a) > 1 else None,
        end=a[2] if len(a) > 2 else None,
        worker=a[3] if len(a) > 3 else "http-nio-8086-exec-",
    )
