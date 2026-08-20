"""P1 포트폴리오 다이어그램 생성. 레이아웃 수치를 코드로 계산해 어긋남을 없앤다."""
import cairosvg, os

OUT = os.path.dirname(os.path.abspath(__file__))
os.makedirs(OUT, exist_ok=True)

FONT = "Helvetica, Arial, 'DejaVu Sans', sans-serif"
MONO = "'DejaVu Sans Mono', Menlo, Consolas, monospace"

INK   = "#0f172a"   # 본문
MUTE  = "#64748b"   # 보조
LINE  = "#cbd5e1"   # 테두리
BG    = "#ffffff"
STOP  = "#b91c1c"   # 거부
STOPB = "#fef2f2"
WAIT  = "#b45309"   # UNKNOWN
WAITB = "#fffbeb"
GO    = "#15803d"   # 성공
GOB   = "#f0fdf4"
PREV  = "#1d4ed8"   # 미리보기
PREVB = "#eff6ff"
GATEB = "#f8fafc"


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def box(x, y, w, h, fill, stroke, rx=8, sw=1.5):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')


def text(x, y, s, size=15, fill=INK, weight="400", anchor="middle", font=FONT, ls="0"):
    return (f'<text x="{x}" y="{y}" font-family="{font}" font-size="{size}" '
            f'fill="{fill}" font-weight="{weight}" text-anchor="{anchor}" '
            f'letter-spacing="{ls}">{esc(s)}</text>')


def vline(x, y1, y2, color=INK, dash=None, arrow="url(#a)"):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return (f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" stroke="{color}" '
            f'stroke-width="1.8"{d} marker-end="{arrow}"/>')


def hline(x1, y, x2, color, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return (f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" stroke="{color}" '
            f'stroke-width="1.8"{d} marker-end="url(#{"as" if color==STOP else "ap"})"/>')


def defs():
    return f'''<defs>
    <marker id="a" markerWidth="9" markerHeight="9" refX="7.5" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" fill="{INK}"/></marker>
    <marker id="as" markerWidth="9" markerHeight="9" refX="7.5" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" fill="{STOP}"/></marker>
    <marker id="ap" markerWidth="9" markerHeight="9" refX="7.5" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" fill="{PREV}"/></marker>
    <marker id="ag" markerWidth="9" markerHeight="9" refX="7.5" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" fill="{GO}"/></marker>
    <marker id="aw" markerWidth="9" markerHeight="9" refX="7.5" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" fill="{WAIT}"/></marker>
  </defs>'''


# ─────────────────────────────────────────────────────────── 다이어그램 1
def diagram_order_path():
    W, H = 1100, 1180
    CX, BW = 330, 400          # 척추 중심 / 박스 폭
    L, R = CX - BW // 2, CX + BW // 2
    RX, RW = 640, 410          # 우측 결과 열
    RCX = RX + RW // 2
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
         f'viewBox="0 0 {W} {H}"><rect width="{W}" height="{H}" fill="{BG}"/>', defs()]

    s.append(text(50, 52, "How an order reaches the broker — and where it doesn't",
                  22, INK, "700", "start"))
    s.append(text(50, 78, "toss-invest-mcp  ·  every gate below is enforced in code and pinned by a named test",
                  14, MUTE, "400", "start"))
    s.append(f'<line x1="50" y1="96" x2="{W-50}" y2="96" stroke="{LINE}" stroke-width="1"/>')

    y = 125
    rows = []

    def spine(label, sub=None, h=52, fill="#ffffff", stroke=LINE, bold="600"):
        nonlocal y
        s.append(box(L, y, BW, h, fill, stroke))
        if sub:
            s.append(text(CX, y + 23, label, 15, INK, bold))
            s.append(text(CX, y + 41, sub, 12.5, MUTE, "400", font=MONO))
        else:
            s.append(text(CX, y + h / 2 + 5, label, 15, INK, bold))
        top, bot = y, y + h
        y = bot
        return top, bot

    def gap(px=34):
        nonlocal y
        s.append(vline(CX, y + 2, y + px - 2))
        y += px

    t, b = spine("Agent calls place_order", h=48)
    gap()
    t1, b1 = spine("Validate input")
    gap()
    t2, b2 = spine("Estimate notional from a live quote")
    gap()

    # 가드레일 (큰 박스)
    gh = 138
    s.append(box(L, y, BW, gh, GATEB, LINE))
    s.append(text(CX, y + 26, "GUARDRAILS", 14, INK, "700", ls="1.2"))
    checks = ["is the amount computable?", "is the currency known?",
              "under the per-order cap?", "under the daily count?",
              "symbol on the allowlist?"]
    for i, c in enumerate(checks):
        s.append(text(L + 26, y + 52 + i * 17, "· " + c, 12.5, MUTE, "400", "start"))
    tg, bg = y, y + gh
    y += gh
    gap()

    tk, bk = spine("Kill switch", "toss.trading.enabled")
    gap()
    te, be = spine("execute flag", "set on this specific call")
    gap()
    tr, br = spine("Rate limit", "6/s  ·  3/s during 09:00–09:10 KST")
    gap()
    tc, bc = spine("Increment the daily counter", "before the request, not after", fill="#fffdf5",
                   stroke="#fcd34d")
    gap()
    tp, bp = spine("POST order  +  clientOrderId", h=48)

    # 우측 결과들
    def rbox(ytop, h, title, lines, col, bgc, src_y):
        s.append(box(RX, ytop, RW, h, bgc, col))
        s.append(text(RCX, ytop + 25, title, 14, col, "700", ls="1"))
        for i, ln in enumerate(lines):
            s.append(text(RCX, ytop + 46 + i * 16, ln, 12, MUTE))
        s.append(hline(R + 4, src_y, RX - 8, col))

    rbox(t1, 52, "REJECTED", ["bad side, quantity, or price"], STOP, STOPB, t1 + 26)
    rbox(tg, gh, "REJECTED",
         ["an amount we cannot compute is not",
          "\"no limit\" — it is \"the limit is",
          "unverifiable\", so we stop.",
          "",
          "This fires in dry-run mode too."], STOP, STOPB, tg + gh / 2)
    # 킬스위치 + execute → 하나의 DRY_RUN 박스
    ph = (be - tk)
    s.append(box(RX, tk, RW, ph, PREVB, PREV))
    s.append(text(RCX, tk + 25, "DRY_RUN", 14, PREV, "700", ls="1"))
    for i, ln in enumerate(["Shows exactly what would happen.",
                            "Nothing is transmitted.",
                            "Both switches must be open."]):
        s.append(text(RCX, tk + 48 + i * 17, ln, 12, MUTE))
    s.append(hline(R + 4, tk + 26, RX - 8, PREV))
    s.append(hline(R + 4, te + 26, RX - 8, PREV))

    rbox(tr, 52, "REJECTED", ["immediately — never sleeps inside the call"], STOP, STOPB, tr + 26)

    # 하단 3결과
    oy = bp + 56
    s.append(vline(CX, bp + 2, oy - 22, arrow="none"))
    s.append(f'<line x1="200" y1="{oy-22}" x2="900" y2="{oy-22}" stroke="{INK}" stroke-width="1.8"/>')
    outs = [(200, "PLACED", "2xx received", GO, GOB),
            (550, "REJECTED", "broker refused (4xx / 5xx)", STOP, STOPB),
            (900, "UNKNOWN", "no response came back", WAIT, WAITB)]
    for cx, title, sub, col, bgc in outs:
        s.append(vline(cx, oy - 22, oy - 4))
        s.append(box(cx - 150, oy, 300, 66, bgc, col))
        s.append(text(cx, oy + 27, title, 15, col, "700", ls="1"))
        s.append(text(cx, oy + 48, sub, 12, MUTE))

    # 각주
    fy = oy + 96
    s.append(box(50, fy, W - 100, 62, "#f8fafc", LINE))
    s.append(text(72, fy + 26,
                  "Guardrails are evaluated BEFORE the kill switch.",
                  14, INK, "700", "start"))
    s.append(text(72, fy + 47,
                  "If the switch short-circuited first, an order that breaks your limits would still render as \"here is what would happen\" — the preview would lie by omission.",
                  12.5, MUTE, "400", "start"))

    s.append("</svg>")
    return "".join(s), W, H


# ─────────────────────────────────────────────────────────── 다이어그램 2
def diagram_states():
    W, H = 1100, 580
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
         f'viewBox="0 0 {W} {H}"><rect width="{W}" height="{H}" fill="{BG}"/>', defs()]

    s.append(text(50, 52, "\"No response\" is not \"no effect\"", 22, INK, "700", "start"))
    s.append(text(50, 78, "Four outcomes an order tool can return — and the one most clients get wrong",
                  14, MUTE, "400", "start"))
    s.append(f'<line x1="50" y1="96" x2="{W-50}" y2="96" stroke="{LINE}" stroke-width="1"/>')

    # 시작점
    s.append(f'<circle cx="120" cy="270" r="9" fill="{INK}"/>')
    s.append(text(120, 300, "order", 12, MUTE))
    s.append(text(120, 316, "attempt", 12, MUTE))

    MK = {STOP: "as", PREV: "ap", GO: "ag", WAIT: "aw"}
    states = [(400, 150, "DRY_RUN", "gates closed — nothing sent", PREV, PREVB),
              (400, 250, "REJECTED", "a guardrail or the broker refused", STOP, STOPB),
              (400, 350, "PLACED", "2xx received", GO, GOB),
              (400, 460, "UNKNOWN", "the request may or may not exist", WAIT, WAITB)]
    for cx, cy, title, sub, col, bgc in states:
        sw = 2.5 if title == "UNKNOWN" else 1.5
        s.append(box(cx - 165, cy - 32, 330, 64, bgc, col, sw=sw))
        s.append(text(cx, cy - 4, title, 16, col, "700", ls="1"))
        s.append(text(cx, cy + 17, sub, 12, MUTE))
        s.append(f'<path d="M133,270 C210,270 210,{cy} 232,{cy}" fill="none" '
                 f'stroke="{col}" stroke-width="1.8" marker-end="url(#{MK[col]})"/>')

    # UNKNOWN 강조 박스
    s.append(box(620, 372, 430, 176, WAITB, WAIT, sw=2.5))
    s.append(text(645, 400, "Never reported as success.", 14, WAIT, "700", "start"))
    s.append(text(645, 421, "Never reported as failure.", 14, WAIT, "700", "start"))
    s.append(f'<line x1="645" y1="437" x2="1025" y2="437" stroke="{WAIT}" stroke-width="1" opacity="0.4"/>')
    for i, ln in enumerate([
            "The response carries its own recovery path:",
            "retry with the same clientOrderId within",
            "10 minutes and get the same outcome —",
            "without placing a second order."]):
        s.append(text(645, 460 + i * 20, ln, 12.5, MUTE, "400", "start"))
    s.append(f'<line x1="568" y1="460" x2="612" y2="460" stroke="{WAIT}" '
             f'stroke-width="1.8" marker-end="url(#aw)"/>')

    # 우상단 설명
    s.append(box(620, 130, 430, 212, "#f8fafc", LINE))
    s.append(text(645, 160, "Why this matters", 14, INK, "700", "start"))
    for i, ln in enumerate([
            "A retried search returns the same page.",
            "A retried order buys the stock twice.",
            "",
            "Collapsing a timeout into \"failed\" is a lie when",
            "the write may have landed. The caller then",
            "retries blind — and one order becomes two.",
            "",
            "The idempotency key is returned even when the",
            "call fails. A key you cannot see is a key you",
            "cannot retry with."]):
        s.append(text(645, 188 + i * 15.5, ln, 11.5, MUTE, "400", "start"))

    s.append("</svg>")
    return "".join(s), W, H


for name, fn in [("order-path", diagram_order_path), ("order-states", diagram_states)]:
    svg, w, h = fn()
    p = f"{OUT}/{name}.svg"
    open(p, "w", encoding="utf-8").write(svg)
    cairosvg.svg2png(bytestring=svg.encode(), write_to=f"{OUT}/{name}.png",
                     output_width=w * 2, output_height=h * 2, background_color="white")
    print(f"{name}: {w}x{h} → svg + png(2x)")
