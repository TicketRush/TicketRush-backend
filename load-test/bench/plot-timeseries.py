#!/usr/bin/env python3
"""덤프한 시계열 JSON 에서 곡선 SVG 를 그린다 (#598).

사용법:
  python load-test/bench/plot-timeseries.py <덤프디렉터리> <arm접미사> <슬러그>[,<슬러그>...] [제목]

  예) python load-test/bench/plot-timeseries.py load-tests/k6/results/260813-598-... s1c1 \
        node-cpu-user,node-cpu-system,node-iowait "호스트 CPU 모드 분해"

  슬러그를 쉼표로 여러 개 주면 한 장에 겹쳐 그린다(패널 하나 = SVG 하나).
  출력: <덤프디렉터리>/graph-<첫슬러그|제목슬러그>-<arm>.svg

왜 Grafana 캡처가 아니라 이걸 쓰나 (#598):
  1. Prometheus 보존이 15일이라 캡처 링크는 그 뒤 빈 화면이 된다. 이 SVG 는 커밋된
     JSON 에서 다시 만들 수 있어 회차가 영구히 재현된다.
  2. 그림과 수치의 출처가 같은 파일이라 서로 어긋날 수 없다. 캡처는 다른 창을
     찍어도 아무도 모른다.
  3. Grafana Explore 를 브라우저 자동화로 여는 것이 불안정하다 — #598 에서 CDP
     screenshot 이 5회 중 4회 렌더러 타임아웃으로 실패했다.
  캡처가 여전히 나은 경우: Grafana 에만 있는 것(패널 옵션·주석·대시보드 문맥)을
  보여줘야 할 때. 그때는 grafana-links.py 를 쓴다.

외부 의존성 없음 — 표준 라이브러리만 쓴다(설치 절차를 늘리지 않는다).
"""

import json
import os
import sys

W, H = 1100, 420
PAD_L, PAD_R, PAD_T, PAD_B = 70, 24, 44, 96
COLORS = [
    "#73bf69", "#f2cc0c", "#5794f2", "#ff9830", "#b877d9",
    "#ff7383", "#8ab8ff", "#c0d8ff", "#e0b400", "#37872d",
]


def load(dumpdir, slug, arm):
    path = os.path.join(dumpdir, f"timeseries-{slug}-{arm}.json")
    if not os.path.exists(path):
        return None, []
    doc = json.load(open(path, encoding="utf-8"))
    out = []
    for s in doc.get("data", []):
        m = s.get("metric", {})
        label = m.get("container") or m.get("instance") or m.get("__name__") or slug
        if len(doc["data"]) > 1 and m.get("__name__") and not m.get("instance"):
            label = m["__name__"]
        pts = [(int(t), float(v)) for t, v in s["values"]]
        out.append((f"{slug} {label}".strip(), pts))
    return doc, out


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def render(series, title, subtitle):
    xs = [t for _, pts in series for t, _ in pts]
    ys = [v for _, pts in series for _, v in pts]
    if not xs:
        return None
    x0, x1 = min(xs), max(xs)
    y0, y1 = 0.0, max(ys)
    if y1 <= 0:
        y1 = 1.0
    y1 *= 1.08
    span_x = max(x1 - x0, 1)

    def px(t):
        return PAD_L + (t - x0) / span_x * (W - PAD_L - PAD_R)

    def py(v):
        return H - PAD_B - (v - y0) / (y1 - y0) * (H - PAD_T - PAD_B)

    p = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
        f'viewBox="0 0 {W} {H}" font-family="ui-monospace,Menlo,Consolas,monospace">',
        f'<rect width="{W}" height="{H}" fill="#181b1f"/>',
        f'<text x="{PAD_L}" y="24" fill="#ccccdc" font-size="15">{esc(title)}</text>',
        f'<text x="{PAD_L}" y="40" fill="#8e8e9e" font-size="11">{esc(subtitle)}</text>',
    ]

    # y 격자 5칸. 눈금 값은 실제 데이터 범위에서 뽑는다.
    for i in range(6):
        v = y1 * i / 5
        y = py(v)
        p.append(f'<line x1="{PAD_L}" y1="{y:.1f}" x2="{W - PAD_R}" y2="{y:.1f}" stroke="#2c3235"/>')
        p.append(
            f'<text x="{PAD_L - 8}" y="{y + 4:.1f}" fill="#8e8e9e" font-size="10" '
            f'text-anchor="end">{v:.4g}</text>'
        )

    # x 축 눈금 5개 (UTC hh:mm:ss)
    import time as _t
    for i in range(6):
        t = x0 + span_x * i / 5
        x = px(t)
        lab = _t.strftime("%H:%M:%S", _t.gmtime(t))
        p.append(f'<line x1="{x:.1f}" y1="{PAD_T}" x2="{x:.1f}" y2="{H - PAD_B}" stroke="#2c3235"/>')
        p.append(
            f'<text x="{x:.1f}" y="{H - PAD_B + 16}" fill="#8e8e9e" font-size="10" '
            f'text-anchor="middle">{lab}Z</text>'
        )

    for i, (label, pts) in enumerate(series):
        c = COLORS[i % len(COLORS)]
        d = " ".join(
            f"{'M' if j == 0 else 'L'}{px(t):.1f},{py(v):.1f}" for j, (t, v) in enumerate(pts)
        )
        p.append(f'<path d="{d}" fill="none" stroke="{c}" stroke-width="1.6"/>')
        vals = [v for _, v in pts]
        ly = H - PAD_B + 36 + (i % 4) * 14
        lx = PAD_L + (i // 4) * 520
        p.append(f'<rect x="{lx}" y="{ly - 8}" width="10" height="3" fill="{c}"/>')
        p.append(
            f'<text x="{lx + 16}" y="{ly}" fill="#ccccdc" font-size="10">'
            f'{esc(label)}  min {min(vals):.4g} / avg {sum(vals) / len(vals):.4g} '
            f'/ max {max(vals):.4g}</text>'
        )

    p.append("</svg>")
    return "\n".join(p)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    dumpdir, arm, slugs = sys.argv[1], sys.argv[2], sys.argv[3].split(",")
    title = sys.argv[4] if len(sys.argv) > 4 else slugs[0]

    series, doc = [], None
    for slug in slugs:
        d, s = load(dumpdir, slug, arm)
        doc = doc or d
        series.extend(s)
    if not series:
        print(f"  건너뜀 (시계열 없음): {','.join(slugs)} [{arm}]")
        return 0

    sub = f"{doc['start']} ~ {doc['end']}  step {doc['step']}  arm={arm}" if doc else arm
    svg = render(series, title, sub)
    if svg is None:
        print(f"  건너뜀 (표본 없음): {','.join(slugs)} [{arm}]")
        return 0
    out = os.path.join(dumpdir, f"graph-{slugs[0]}-{arm}.svg")
    open(out, "w", encoding="utf-8").write(svg)
    print(f"  {os.path.basename(out)}  ({len(series)} 계열)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
