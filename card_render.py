"""土豆片卡片渲染器（528×792 六色）—— 模板集合。

全部用**精确调色板色**作画：六色墨水屏只有 黑/白/黄/红/蓝/绿 六个值，
文字/色块用准确 RGB 时，上游的 Floyd–Steinberg 误差为 0，不会被抖动糊掉；
只有照片需要真正的抖动。

设计取向（参考「技能」那套卡片）：白底、黑色标题条、大量留白、
一个主数字撑场面、其余信息用小字和细线分层——墨水屏上层次靠留白和字重，
不靠灰色（没有灰）。
"""
from __future__ import annotations

from datetime import date, datetime

CARD_W, CARD_H = 528, 792
BLACK = (0, 0, 0)
WHITE = (255, 255, 255)
YELLOW = (255, 255, 0)
RED = (255, 0, 0)
BLUE = (0, 0, 255)
GREEN = (0, 255, 0)

WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
WEEKDAYS_SHORT = ["一", "二", "三", "四", "五", "六", "日"]

FONTS = {
    "bold": ["/System/Library/Fonts/STHeiti Medium.ttc",
             "/System/Library/Fonts/Hiragino Sans GB.ttc",
             "/System/Library/Fonts/Supplemental/Songti.ttc"],
    "light": ["/System/Library/Fonts/STHeiti Light.ttc",
              "/System/Library/Fonts/Hiragino Sans GB.ttc",
              "/System/Library/Fonts/Supplemental/Songti.ttc"],
    "num": ["/System/Library/Fonts/HelveticaNeue.ttc",
            "/System/Library/Fonts/Avenir Next.ttc",
            "/System/Library/Fonts/Helvetica.ttc",
            "/System/Library/Fonts/SFNS.ttf"],
}


#: 全局字号缩放（1.0~1.35）。只缩放字体，不改边距——用户要的是"字更大"，
#: 而不是"把整张卡放大"。所有字体都走 font()，所以一个变量就能整体调。
_SCALE = 1.0


def set_scale(v: float):
    global _SCALE
    try:
        _SCALE = max(0.85, min(1.35, float(v)))
    except (TypeError, ValueError):
        _SCALE = 1.0


def scale() -> float:
    return _SCALE


def font(kind: str, size: int):
    from PIL import ImageFont
    size = max(8, int(round(size * _SCALE)))
    for path in FONTS.get(kind, FONTS["light"]):
        try:
            from pathlib import Path
            if Path(path).exists():
                return ImageFont.truetype(path, size)
        except Exception:
            continue
    return ImageFont.load_default()


def new_canvas(bg=WHITE):
    from PIL import Image
    return Image.new("RGB", (CARD_W, CARD_H), bg)


def _right_text(d, text, y, size, weight="light", color=BLACK, margin=None):
    """右对齐画一行字：量宽和绘制复用同一个字体对象，避免量小画大导致溢出右边界。"""
    f = font(weight, size)
    x = CARD_W - (M if margin is None else margin) - text_width(d, text, f)
    d.text((x, y), text, font=f, fill=color)
    return x


def text_width(d, text, f) -> float:
    return d.textlength(text, font=f)


def wrap(d, text, f, max_w: int, word_wrap: bool | None = None) -> list[str]:
    """按像素宽度切行。纯英文优先按词切（避免 "phot / o" 这种断词）。"""
    text = str(text)
    if word_wrap is None:
        ascii_ratio = sum(1 for ch in text if ord(ch) < 128) / max(1, len(text))
        word_wrap = ascii_ratio > 0.8
    if word_wrap and " " in text:
        lines, cur = [], ""
        for word in text.split(" "):
            trial = (cur + " " + word).strip()
            if d.textlength(trial, font=f) > max_w and cur:
                lines.append(cur)
                cur = word
            else:
                cur = trial
        if cur:
            lines.append(cur)
        return lines or [""]
    lines, cur = [], ""
    for ch in str(text):
        if ch == "\n":
            lines.append(cur)
            cur = ""
            continue
        if d.textlength(cur + ch, font=f) > max_w and cur:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    # 中文避头尾：行首不能是标点，把上一行最后一个字带下来
    no_start = "。，、；：！？）】》」』”’,.;:!?)]}"
    for i in range(1, len(lines)):
        guard = 0
        while lines[i] and lines[i][0] in no_start and guard < 3:
            if len(lines[i - 1]) <= 1:
                break
            lines[i] = lines[i - 1][-1] + lines[i]
            lines[i - 1] = lines[i - 1][:-1]
            guard += 1
    return lines or [""]


def _right_font(text: str, size: int):
    """右侧文字可能是中文（周五/个口），拉丁字体没有汉字会渲染成方框。"""
    has_cjk = any(ord(ch) > 127 for ch in str(text))
    return font("bold" if has_cjk else "num", size)


def header(d, title: str, sub: str = "", right: str = "", h: int = 92):
    d.rectangle([0, 0, CARD_W, h], fill=BLACK)
    d.text((30, 20), title, font=font("bold", 36), fill=WHITE)
    if sub:
        d.text((30, 62), sub, font=font("light", 17), fill=WHITE)
    if right:
        f = _right_font(right, 30)
        w = text_width(d, right, f)
        d.text((CARD_W - 30 - w, 28), right, font=f, fill=WHITE)


def section(d, y: int, title: str, right: str = "") -> int:
    d.text((30, y), title, font=font("bold", 21), fill=BLACK)
    tw = text_width(d, title, font("bold", 21))
    right_w = 0
    if right:
        rf = _right_font(right, 20)
        right_w = text_width(d, right, rf)
        d.text((CARD_W - 30 - right_w, y), right, font=rf, fill=BLACK)
    d.line([30 + tw + 12, y + 13, CARD_W - 30 - (right_w + 12 if right else 0), y + 13],
           fill=BLACK, width=2)
    return y + 36


def bar(d, x: int, y: int, w: int, h: int, pct: float, color=BLACK, filled=True):
    pct = max(0.0, min(1.0, pct))
    d.rectangle([x, y, x + w, y + h], outline=BLACK, width=2)
    if filled and pct > 0:
        d.rectangle([x + 2, y + 2, x + 2 + int((w - 4) * pct), y + h - 2], fill=color)


def footer(d, left: str = "", right: str = ""):
    y = CARD_H - 46
    d.line([30, y - 18, CARD_W - 30, y - 18], fill=BLACK, width=2)
    if left:
        d.text((30, y), left, font=font("light", 19), fill=BLACK)
    if right:
        w = text_width(d, right, font("light", 15))
        d.text((CARD_W - 30 - w, y), right, font=font("light", 19), fill=BLACK)


# ─────────────── 混合风格：色块（Aicher）+ 网格（Müller-Brockmann）+ 点阵 ───────────────
# 三条规矩：
#  1) 白底，颜色只出现在"几何块 / 圆点 / 数字"上，单卡最多 3 种颜色；
#  2) 所有元素对齐 12 栏网格（边距 28、栏宽 39.3），标题永远在左上角红方块右侧；
#  3) 数据用点阵/条形表达，文字只留必要信息。

M = 34                      # 外边距（安全边界，真机边框会吃掉 2~3mm）
GRID_W = CARD_W - M * 2     # 472
COL = GRID_W / 12           # 39.33


def _col_x(n):              # 第 n 栏的左边
    return M + n * COL


def _rule(d, y, x0=M, x1=CARD_W - M, w=2, color=BLACK):
    d.line([x0, y, x1, y], fill=color, width=w)


def _head(d, title, meta="", color=RED):
    d.rectangle([M, 34, M + 13, 47], fill=color)
    d.text((M + 24, 20), title, font=font("bold", 30), fill=BLACK)
    if meta:
        mw = text_width(d, meta, _right_font(meta, 16))
        d.text((CARD_W - M - mw, 32), meta, font=_right_font(meta, 16), fill=BLACK)


def _num(d, x, y, value, size, color=BLACK, unit="", unit_size=None, unit_color=None):
    f = font("num", size)
    d.text((x, y), value, font=f, fill=color)
    w = text_width(d, value, f)
    if unit:
        uf = font("num", unit_size or int(size * 0.42))
        d.text((x + w + 3, y + int(size * 0.22)), unit, font=uf, fill=unit_color or color)
        w += text_width(d, unit, uf) + 3
    return x + w


def _circle(d, cx, cy, r, color=BLACK, outline=False, width=3):
    box = [cx - r, cy - r, cx + r, cy + r]
    if outline:
        d.ellipse(box, outline=color, width=width)
    else:
        d.ellipse(box, fill=color)


def _bar(d, x, y, w, h, pct, color=RED, bg_outline=True):
    if bg_outline:
        d.rectangle([x, y, x + w, y + h], outline=BLACK, width=3)
    inner = max(0, int((w - 6) * max(0, min(1, pct))))
    if inner:
        d.rectangle([x + 3, y + 3, x + 3 + inner, y + h - 3], fill=color)


def _aqi_label(aqi):
    try:
        a = int(aqi)
    except (TypeError, ValueError):
        return "未知", BLACK
    if a <= 50:
        return "优", GREEN
    if a <= 100:
        return "良", YELLOW
    if a <= 150:
        return "轻度污染", RED
    if a <= 200:
        return "中度污染", RED
    return "重度污染", RED


def _aqi_advice(aqi):
    try:
        a = int(aqi)
    except (TypeError, ValueError):
        return ""
    if a <= 50:
        return "空气很好，适合户外"
    if a <= 100:
        return "可以正常活动"
    if a <= 150:
        return "敏感人群减少户外"
    return "建议关窗"


def _wx_icon(d, x: int, y: int, size: int, code):
    """天气图标：实心色块 + 粗黑描边，墨水屏上才看得清。"""
    import math
    try:
        c = int(code)
    except (TypeError, ValueError):
        c = 0
    cx, cy, r = x + size // 2, y + size // 2, size // 5

    def _cloud_icon(ccx, ccy, sz, fill=BLACK):
        rr = sz // 6
        for dx, dy in ((-rr, 0), (0, -rr), (rr, 0)):
            d.ellipse([ccx + dx - rr, ccy + dy - rr, ccx + dx + rr, ccy + dy + rr], fill=fill)
        d.rectangle([ccx - rr, ccy, ccx + rr, ccy + rr], fill=fill)

    if c in (0, 1):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=YELLOW, outline=BLACK, width=5)
        for i in range(8):
            a = math.pi * i / 4
            d.line([cx + math.cos(a) * (r + 9), cy + math.sin(a) * (r + 9),
                    cx + math.cos(a) * (r + 24), cy + math.sin(a) * (r + 24)], fill=BLACK, width=6)
    elif c in (2, 3):
        d.ellipse([cx - r - 8, cy - r - 10, cx + r - 4, cy + r - 6], fill=YELLOW, outline=BLACK, width=4)
        _cloud_icon(cx - 2, cy + 8, size)
    elif c in (45, 48):
        for i in range(3):
            yy = cy - r + i * r
            d.line([cx - r * 2, yy, cx + r * 2, yy], fill=BLACK, width=6)
    else:
        _cloud_icon(cx, cy - r // 2, size)
        for i in range(3):
            xx = cx - r + i * r
            d.line([xx, cy + r // 2, xx - 4, cy + r + r // 2], fill=BLUE, width=5)


# ── 每日总结 ──
def render_summary(ctx: dict):
    now: datetime = ctx["now"]
    wd = ctx.get("weather") or {}
    todos = [t for t in (ctx.get("todos") or []) if t][:4]
    quote = ctx.get("quote") or {}
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, "每日总结", f"{now.month:02d}/{now.day:02d} {WEEKDAYS[now.weekday()]}")

    # 左：日期大数字；右：温度。两块的底部都按实际字高算，避免压到黄线。
    top = 76
    day_f = font("num", 96)
    d.text((M, top), f"{now.day:02d}", font=day_f, fill=RED)
    day_bottom = top + day_f.size
    d.text((M + 2, day_bottom + 2), f"{now.month}月", font=font("bold", 18), fill=BLACK)

    right_x = _col_x(6)
    icon_size = 72
    icon_x = CARD_W - M - icon_size
    avail_w = icon_x - right_x - 18          # 右侧温度块可用宽度，给太阳图标让位
    bottom_left = day_bottom + 26

    if wd.get("temp") is not None:
        temp = f"{wd['temp']:.0f}"
        deg = "°C"
        size = 92
        # 字号随可用宽度收敛：1.3 倍时也不会顶到太阳图标
        while size > 48:
            tf = font("num", size)
            df = font("num", int(size * 0.42))
            total = text_width(d, temp, tf) + text_width(d, deg, df)
            if total <= avail_w:
                break
            size -= 4
        tf = font("num", size)
        df = font("num", int(size * 0.42))
        d.text((right_x, top + 20), temp, font=tf, fill=BLACK)
        tw = text_width(d, temp, tf)
        d.text((right_x + tw + 2, top + 20 + int(size * 0.16)), deg, font=df, fill=RED)

        cond_y = top + 20 + tf.size + 10
        cond = f"{wd.get('text','')} · {wd.get('city','')}"
        cf = font("light", 17)
        while text_width(d, cond, cf) > avail_w and cf.size > 12:
            cf = font("light", cf.size - 1)
        d.text((right_x, cond_y), cond, font=cf, fill=BLACK)
        _wx_icon(d, icon_x, top + 4, icon_size, wd.get("code"))
        bottom_left = max(bottom_left, cond_y + cf.size + 8)

    bar_y = bottom_left + 14
    d.rectangle([M, bar_y, CARD_W - M, bar_y + 8], fill=YELLOW)

    y = bar_y + 34
    d.text((M, y), "今日待办", font=font("bold", 20), fill=BLACK)
    cnt = f"{len(todos)} 项"
    d.text((CARD_W - M - text_width(d, cnt, _right_font(cnt, 15)), y + 4),
           cnt, font=_right_font(cnt, 15), fill=BLACK)
    y += 34

    colors = [RED, BLUE, GREEN, YELLOW]
    if not todos:
        d.text((M, y), "今天还没有安排", font=font("light", 20), fill=BLACK)
        y += 40
    for i, item in enumerate(todos):
        _circle(d, M + 14, y + 14, 14, colors[i % 4])
        d.text((M + 7, y + 5), str(i + 1), font=font("num", 14), fill=WHITE)
        f = font("light", 25)
        lines = wrap(d, item, f, CARD_W - M * 2 - 44)[:2]
        for j, ln in enumerate(lines):
            d.text((M + 38, y + j * 30), ln, font=f, fill=BLACK)
        y += max(48, 30 * len(lines) + 14)

    # 空档太大时补一条本月进度，避免中间一大片白
    if y < CARD_H - 330:
        import calendar as _cal
        mpct = now.day / _cal.monthrange(now.year, now.month)[1]
        d.text((M, y + 10), "本月", font=font("bold", 19), fill=BLACK)
        _right_text(d, f"{mpct*100:.0f}%", y + 10, 18, "num")
        _bar(d, M, y + 36, GRID_W, 16, mpct, GREEN)

    # 全年点阵：跟着内容走，但最低留在底部信息区上方
    dots_y = min(CARD_H - 236, y + 70)
    d.text((M, dots_y), "全年", font=font("bold", 14), fill=BLACK)
    passed = (now - datetime(now.year, 1, 1)).days + 1
    total = 365 + (1 if now.year % 4 == 0 else 0)
    dots = 52
    filled = int(dots * passed / total)
    # 点阵右端与下方进度条右端严格对齐
    _dx0, _dx1 = M + 52, CARD_W - M - 3
    _dgap = (_dx1 - _dx0) / (dots - 1)
    for i in range(dots):
        cx = _dx0 + i * _dgap
        _circle(d, cx, dots_y + 9, 3.2, RED if i < filled else BLACK,
                outline=i >= filled, width=1)
    d.text((M, dots_y + 22), f"{passed}/{total}", font=font("num", 15), fill=BLACK)

    d.line([M, CARD_H - 92, CARD_W - M, CARD_H - 92], fill=BLACK, width=2)
    qf = font("light", 19)
    _qt = f"“{quote.get('text','把今天过好。')}”"
    _all = wrap(d, _qt, qf, CARD_W - M * 2)
    ql = _all[:2]
    if len(_all) > 2 and ql:
        ql[-1] = ql[-1][:-1] + "…”" if len(ql[-1]) > 1 else ql[-1] + "…”"
    yy = CARD_H - 76
    for ln in ql:
        d.text((M, yy), ln, font=qf, fill=BLACK)
        yy += 24
    _right_text(d, "土豆片", CARD_H - 32, 16, "light")
    return img


# ── 天气 ──
def render_weather(ctx: dict):
    now: datetime = ctx["now"]
    w = ctx.get("weather") or {}
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, "今日天气", w.get("city") or now.strftime("%m/%d"))

    temp = w.get("temp")
    y = 76
    if temp is None:
        d.text((M, y + 40), "天气获取失败", font=font("bold", 28), fill=BLACK)
        return img

    icon_size = 92
    icon_x = CARD_W - M - icon_size
    avail_w = icon_x - M - 18

    # 主温度：字号按"宽度可用 + 不压到下方"两条约束收敛
    txt = f"{temp:.0f}"
    size = 140
    while size > 60:
        tf = font("num", size)
        df = font("num", int(size * 0.40))
        wsum = text_width(d, txt, tf) + text_width(d, "°C", df)
        if wsum <= avail_w and size <= 140:
            break
        size -= 4
    tf = font("num", size)
    df = font("num", int(size * 0.40))
    d.text((M - 2, y), txt, font=tf, fill=BLACK)
    tw_ = text_width(d, txt, tf)
    d.text((M - 2 + tw_ + 2, y + int(size * 0.14)), "°C", font=df, fill=RED)
    _wx_icon(d, icon_x, y + 6, icon_size, w.get("code"))

    # 天气描述：温度块正下方，间隔按字号算
    cond_y = y + tf.size + 12
    d.text((M, cond_y), w.get("text", ""), font=font("bold", 40), fill=BLACK)

    # 三段信息
    y2 = cond_y + int(40 * scale()) + 26
    d.line([M, y2 - 14, CARD_W - M, y2 - 14], fill=BLACK, width=3)
    cells = [("最高", f"{round(w.get('hi',0))}°", RED),
             ("最低", f"{round(w.get('lo',0))}°", BLUE),
             ("体感", f"{round(w.get('feels',0))}°", GREEN)]
    for i, (label, val, color) in enumerate(cells):
        x = _col_x(i * 4)
        d.text((x, y2), label, font=font("light", 19), fill=BLACK)
        d.text((x, y2 + 22), val, font=font("num", 28), fill=BLACK)
        d.rectangle([x, y2 + 58, x + 32, y2 + 64], fill=color)
        if i:
            d.line([x - 14, y2 - 6, x - 14, y2 + 64], fill=BLACK, width=1)

    # 未来几小时
    y = y2 + 96
    d.text((M, y), "未来几小时", font=font("bold", 19), fill=BLACK)
    d.line([M + 120, y + 13, CARD_W - M, y + 13], fill=BLACK, width=3)
    y += 34

    hours = (w.get("hourly") or [])[:4]
    aqi = w.get("aqi")
    aqi_h = 70 if aqi is not None else 0
    bottom_limit = CARD_H - M - aqi_h - 10
    if hours:
        temps = [h.get("temp") or 0 for h in hours]
        lo, hi = min(temps), max(temps)
        span = max(6, hi - lo)
        mid = (hi + lo) / 2
        # 折线高度吃掉剩余空间，但留出下方两行文字
        plot_h = max(60, min(120, bottom_limit - y - 70))
        top, bottom = y + 20, y + 20 + plot_h
        colw = GRID_W / max(1, len(hours))
        pts = []
        for i, t in enumerate(temps):
            px = M + colw * (i + 0.5)
            py = bottom - (t - (mid - span / 2)) / span * plot_h
            pts.append((px, py))
        for i in range(len(pts) - 1):
            d.line([pts[i][0], pts[i][1], pts[i+1][0], pts[i+1][1]], fill=BLUE, width=4)
        for i, (px, py) in enumerate(pts):
            d.ellipse([px - 6, py - 6, px + 6, py + 6], fill=BLUE)
            d.text((px - text_width(d, f"{temps[i]}°", font("num", 17)) / 2, py - 30),
                   f"{temps[i]}°", font=font("num", 17), fill=BLACK)
            d.text((px - text_width(d, hours[i].get("t",""), font("num", 15)) / 2, bottom + 10),
                   hours[i].get("t",""), font=font("num", 15), fill=BLACK)
            cnd = hours[i].get("text","")
            d.text((px - text_width(d, cnd, font("light", 14)) / 2, bottom + 32),
                   cnd, font=font("light", 18), fill=BLACK)

    if aqi is not None:
        label, color = _aqi_label(aqi)
        num_f, lab_f = font("num", 28), font("bold", 16)
        w_num = text_width(d, str(aqi), num_f)
        w_lab = text_width(d, label, lab_f)
        pill_w = 22 + w_num + 8 + w_lab + 22
        py = CARD_H - M - 56
        d.rounded_rectangle([M, py, M + pill_w, py + 56], radius=28, fill=color, outline=BLACK, width=3)
        tc = WHITE if color in (RED, BLUE, BLACK) else BLACK
        d.text((M + 22, py + 9), str(aqi), font=num_f, fill=tc)
        d.text((M + 30 + w_num, py + 16), label, font=lab_f, fill=tc)
        d.text((M + pill_w + 16, py + 16), _aqi_advice(aqi), font=font("light", 18), fill=BLACK)
    return img


# ── 时间进度 ──
def render_progress(ctx: dict):
    now: datetime = ctx["now"]
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    y0, y1 = datetime(now.year, 1, 1), datetime(now.year + 1, 1, 1)
    total = (y1 - y0).days
    passed = (now - y0).days + 1
    pct = passed / total

    _head(d, "时间进度", f"{now.year}")
    big = _num(d, M, 78, f"{pct*100:.0f}", 96, RED, "%", 40, RED)
    info_y = 78 + int(96 * scale()) + 6
    d.text((M + 4, info_y), f"已过 {passed} / {total} 天", font=font("light", 21), fill=BLACK)

    # 列距按可用宽度反算，保证点阵右边缘和下方进度条右边缘严格对齐
    cols = 32
    gap = (GRID_W - 6) / (cols - 1)
    dy = info_y + 40
    for i in range(total):
        cx = M + (i % cols) * gap
        cy = dy + (i // cols) * gap
        _circle(d, cx, cy, 5.5, RED if i < passed else BLACK, outline=i >= passed, width=2)

    rows = (total + cols - 1) // cols
    yb = dy + rows * gap + 18
    d.line([M, yb, CARD_W - M, yb], fill=BLACK, width=2)

    m0 = datetime(now.year, now.month, 1)
    m1 = datetime(now.year + (now.month // 12), (now.month % 12) + 1, 1)
    mpct = (now - m0).total_seconds() / (m1 - m0).total_seconds()
    w0 = datetime(now.year, now.month, now.day) - __import__("datetime").timedelta(days=now.weekday())
    wpct = (now - w0).total_seconds() / (7 * 86400)
    dpct = (now.hour * 3600 + now.minute * 60) / 86400

    yy = yb + 14
    for label, p_, color in (("本月", mpct, GREEN), ("本周", wpct, BLUE), ("今天", dpct, RED)):
        d.text((M, yy), label, font=font("bold", 19), fill=BLACK)
        d.text((M + 58, yy - 2), f"{p_*100:.0f}%", font=font("num", 19), fill=BLACK)
        _bar(d, M + 136, yy, GRID_W - 136, 22, p_, color)
        yy += 40

    d.text((M, CARD_H - 74), f"今年还剩 {left_days}", font=font("bold", 16), fill=BLACK) if False else None
    left_days = (y1 - now).days
    d.text((M, CARD_H - 92), "今年还剩", font=font("bold", 19), fill=BLACK)
    d.rectangle([M, CARD_H - 58, M + 10, CARD_H - 20], fill=RED)
    _num(d, M + 22, CARD_H - 64, f"{left_days}", 30, BLACK)
    d.text((M + 22 + text_width(d, f"{left_days}", font("num", 30)) + 8, CARD_H - 54),
           "天", font=font("bold", 17), fill=BLACK)
    _right_text(d, "土豆片", CARD_H - 50, 16, "light")
    return img


# ── 今日待办 ──
def render_todo(ctx: dict):
    now: datetime = ctx["now"]
    todos = [t for t in (ctx.get("todos") or []) if t][:6]
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    d.rectangle([0, 0, CARD_W, 118], fill=YELLOW)
    d.text((M, 26), "今日待办", font=font("bold", 34), fill=BLACK)
    d.text((M, 76), f"{now.month:02d}/{now.day:02d} {WEEKDAYS[now.weekday()]}",
           font=font("light", 19), fill=BLACK)
    cnt = f"{len(todos)}"
    _num(d, CARD_W - M - text_width(d, cnt, font("num", 46)), 30, cnt, 46, BLACK)
    d.text((CARD_W - M - 24, 84), "件", font=font("bold", 14), fill=BLACK)

    y = 148
    colors = [RED, BLUE, GREEN, BLACK, RED, BLUE]
    if not todos:
        # 没填待办时画 6 条空白书写行，看起来像一张"待填"的卡片，而不是忘了渲染
        for i in range(6):
            d.rectangle([M + 42, y + 4, M + 64, y + 26], outline=BLACK, width=2)
            d.line([M + 76, y + 26, CARD_W - M, y + 26], fill=BLACK, width=1)
            y += 56
        y -= 40
        d.text((M, y + 40), "在网页里添加今天要做的事", font=font("light", 18), fill=BLACK)
    for i, item in enumerate(todos):
        _circle(d, M + 14, y + 14, 14, colors[i % len(colors)])
        d.text((M + 6, y + 5), str(i + 1), font=font("num", 15), fill=WHITE)
        d.rectangle([M + 42, y + 4, M + 64, y + 26], outline=BLACK, width=2)
        f = font("light", 27)
        lines = wrap(d, item, f, CARD_W - M * 2 - 80)
        for j, ln in enumerate(lines[:2]):
            d.text((M + 76, y + j * 26), ln, font=f, fill=BLACK)
        y += max(50, 26 * min(2, len(lines)) + 16)
        if y > CARD_H - 120:
            break
    d.line([M, CARD_H - 70, CARD_W - M, CARD_H - 70], fill=BLACK, width=2)
    d.text((M, CARD_H - 54), "勾掉一件算一件", font=font("light", 17), fill=BLACK)
    _right_text(d, now.strftime("%H:%M"), CARD_H - 54, 16, "num")
    return img


# ── 每日一句 ──
def render_quote(ctx: dict):
    now: datetime = ctx["now"]
    q = ctx.get("quote") or {}
    text = q.get("text") or "把今天过好。"
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, "每日一句", now.strftime("%m/%d"))

    f = font("bold", 40)
    lines = wrap(d, text, f, CARD_W - M * 2 - 46)[:7]
    lh = 56
    block_h = len(lines) * lh
    y0 = max(170, (CARD_H - 120 - block_h) // 2)

    # 红色竖条只包住文字块，长度跟着内容走，不要从顶拉到脚
    d.rectangle([M, y0 + 4, M + 6, y0 + block_h - 8], fill=RED)
    d.text((M - 2, y0 - 64), "\u201c", font=font("num", 72), fill=RED)

    y = y0
    for ln in lines:
        d.text((M + 24, y), ln, font=f, fill=BLACK)
        y += lh
    if q.get("from"):
        d.text((M + 24, y + 16), f"— {q['from']}", font=font("light", 18), fill=BLACK)

    for i, c in enumerate((RED, YELLOW, BLUE)):
        _circle(d, M + 8 + i * 22, CARD_H - 100, 6, c)
    _rule(d, CARD_H - 72)
    d.text((M, CARD_H - 56), "hitokoto", font=font("light", 16), fill=BLACK)
    d.text((CARD_W - M - text_width(d, now.strftime("%Y-%m-%d"), font("num", 12)), CARD_H - 56),
           now.strftime("%Y-%m-%d"), font=font("num", 15), fill=BLACK)
    return img


def render_poem(ctx: dict):
    now: datetime = ctx["now"]
    p = ctx.get("poem") or {}
    note = (ctx.get("note") or "").strip()
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    title = p.get("title") or "今日诗词"
    tf = font("bold", 30)
    while text_width(d, title, tf) > CARD_W - M * 2 - 90 and tf.size > 18:
        tf = font("bold", tf.size - 2)
    d.rectangle([M, 34, M + 13, 47], fill=RED)
    d.text((M + 24, 20), title, font=tf, fill=BLACK)

    ay = 20 + tf.size + 4
    if p.get("author"):
        d.text((M + 24, ay), f"{p.get('dynasty','')} · {p['author']}",
               font=font("light", 17), fill=BLACK)
    ry = ay + 32
    d.line([M, ry, CARD_W - M, ry], fill=BLACK, width=3)
    d.line([M, ry, M + 90, ry], fill=RED, width=6)

    lines = p.get("lines") or ["今天也要好好生活。"]
    nf = font("light", 20)
    note_lines = wrap(d, note, nf, CARD_W - M * 2)[:10] if note else []
    note_h = (len(note_lines) * 34 + 46) if note_lines else 0

    f = font("bold", 34)
    for size in (34, 30, 26, 22, 19):
        f = font("bold", size)
        lh = int(size * 1.7)
        body_h = len(lines) * lh
        avail = CARD_H - 120 - (ry + 24)
        if all(text_width(d, ln, f) <= CARD_W - M * 2 for ln in lines) and body_h + note_h + 30 <= avail:
            break

    # 正文 + 赏析整体在标题区与印章之间做垂直分配，避免底部一大片空
    lh = int(f.size * 1.7)
    body_h = len(lines) * lh
    total = body_h + note_h
    space = (CARD_H - 130) - (ry + 20)
    y = ry + 20 + max(0, min(130, (space - total) // 2))
    lh = int(f.size * 1.7)
    for ln in lines:
        d.text((M, y), ln, font=f, fill=BLACK)
        y += lh

    if note_lines:
        y += 6
        d.line([M, y, CARD_W - M, y], fill=BLACK, width=1)
        y += 14
        d.text((M, y), "赏析", font=font("bold", 17), fill=RED)
        y += 28
        for ln in note_lines:
            d.text((M, y), ln, font=nf, fill=BLACK)
            y += 34

    seal = font("bold", 26)
    sx = CARD_W - M - 54
    sy = CARD_H - 118
    d.rectangle([sx, sy, sx + 54, sy + 54], fill=RED)
    d.text((sx + (54 - text_width(d, "诗", seal)) / 2, sy + 12), "诗", font=seal, fill=WHITE)
    d.text((M, CARD_H - 46), "每日诗词 · 土豆片", font=font("light", 16), fill=BLACK)
    return img


# ── 月历（白底 + 红色今天） ──
def render_calendar(ctx: dict):
    now: datetime = ctx["now"]
    import calendar as _cal
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, f"{now.year} 年 {now.month} 月", f"第 {now.timetuple().tm_yday} 天")
    _rule(d, 78)

    col_w = (CARD_W - M * 2) / 7
    y = 128
    for i, wd in enumerate(WEEKDAYS_SHORT):
        color = RED if i >= 5 else BLACK
        f = font("bold", 18)
        d.text((M + i * col_w + (col_w - text_width(d, wd, f)) / 2, y), wd, font=f, fill=color)
    y += 34

    weeks = _cal.Calendar(firstweekday=0).monthdayscalendar(now.year, now.month)
    # 行高按剩余空间算：本周条 + 页脚要留够，130% 字号时也不会挤到一起
    _reserved = 96 + 92          # 页脚 + 本周条
    cell_h = int((CARD_H - _reserved - y) / max(1, len(weeks)))
    cell_h = max(54, min(74, cell_h))
    for week in weeks:
        for i, day in enumerate(week):
            if day == 0:
                continue
            cx = M + i * col_w
            is_today = (day == now.day)
            color = RED if i >= 5 else BLACK
            f = font("num", 27)
            txt = str(day)
            tw_ = text_width(d, txt, f)
            tx = cx + (col_w - tw_) / 2
            ty = y + max(4, (cell_h - int(27 * scale())) // 2)
            if is_today:
                # 用文字的实际包围盒算圆心，字和圈才同心
                bb = d.textbbox((tx, ty), txt, font=f)
                ccx, ccy = (bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2
                rr = max(bb[2] - bb[0], bb[3] - bb[1]) / 2 + 10
                d.ellipse([ccx - rr, ccy - rr, ccx + rr, ccy + rr], fill=RED)
                color = WHITE
            d.text((tx, ty), txt, font=f, fill=color)
        y += cell_h

    # 本周 7 天条：填掉网格下方的空白，也给"这周过到哪"一个直觉
    wk_y = y + 4
    d.text((M, wk_y), "本周", font=font("bold", 19), fill=BLACK)
    wk_y += 26
    start = now - __import__("datetime").timedelta(days=now.weekday())
    for i in range(7):
        day = start + __import__("datetime").timedelta(days=i)
        x = M + i * (GRID_W / 7)
        is_today = day.date() == now.date()
        col = WHITE if is_today else (RED if i >= 5 else BLACK)
        if is_today:
            d.rectangle([x + 2, wk_y, x + 52, wk_y + 46], fill=RED)
        d.text((x + 8, wk_y + 4), WEEKDAYS_SHORT[i], font=font("bold", 14), fill=col)
        d.text((x + 8, wk_y + 22), str(day.day), font=font("num", 18), fill=col)

    days_left = _cal.monthrange(now.year, now.month)[1] - now.day
    _rule(d, CARD_H - 96)
    d.text((M, CARD_H - 80), "本月还剩", font=font("bold", 17), fill=BLACK)
    d.text((30, CARD_H - 56), f"{days_left}", font=font("num", 26), fill=BLACK)
    d.text((30 + text_width(d, f"{days_left}", font("num", 26)) + 10, CARD_H - 48),
           "天", font=font("bold", 17), fill=BLACK)
    _right_text(d, "土豆片 · 月历", CARD_H - 60, 17, "light")
    return img


# ── 倒计时（白底 + 彩色天数） ──
def render_countdown(ctx: dict):
    now: datetime = ctx["now"]
    items = ctx.get("countdowns") or []
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, "倒计时", f"{now.month:02d}/{now.day:02d}")
    _rule(d, 78)

    def _key(it):
        try:
            target = datetime.strptime(it.get("date", ""), "%Y-%m-%d").date()
            return (0, (target - now.date()).days) if target >= now.date() \
                else (1, -(target - now.date()).days)
        except Exception:
            return (2, 0)

    colors = [RED, BLUE, GREEN, BLACK, RED]
    # 条目少时整体下移，别让内容全堆在标题下面
    y = 140 if len(items) >= 3 else 190
    if not items:
        d.text((30, y), "还没有纪念日", font=font("bold", 22), fill=BLACK)
        d.text((30, y + 34), "在网页里写：2027-01-01 元旦", font=font("light", 19), fill=BLACK)
        yy = y + 80
        for i in range(3):
            d.line([M, yy + 30, CARD_W - M, yy + 30], fill=BLACK, width=1)
            d.text((M, yy + 44), "— —", font=font("num", 22), fill=BLACK)
            yy += 96
    for i, it in enumerate(sorted(items, key=_key)[:5]):
        try:
            target = datetime.strptime(it.get("date", ""), "%Y-%m-%d").date()
        except Exception:
            continue
        days = (target - now.date()).days
        big, note = (str(days), "天后") if days >= 0 else (str(-days), "天前")
        d.text((30, y), it.get("name") or "纪念日", font=font("bold", 24), fill=BLACK)
        d.text((CARD_W - 30 - text_width(d, it.get("date", ""), font("light", 16),), y + 6),
               it.get("date", ""), font=font("light", 16), fill=BLACK)
        y += 40
        f = font("num", 62)
        d.text((30, y), big, font=f, fill=colors[i % len(colors)])
        d.text((30 + text_width(d, big, f) + 10, y + 24), note, font=font("bold", 18), fill=BLACK)
        y += 80
        _rule(d, y, x0=30, x1=CARD_W - 30, w=1)
        y += 22
        if y > CARD_H - 110:
            break
    return img


# ── 充电统计 ──
def render_energy(ctx: dict):
    now: datetime = ctx["now"]
    stats = ctx.get("energy") or []
    today = ctx.get("energy_today") or []
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    total = sum(x["wh"] for x in stats)
    _head(d, "充电统计", "昨日")
    _num(d, M, 76, f"{total:.0f}", 84, BLACK, "Wh", 28, BLACK)
    sub_y = 76 + int(84 * scale()) + 8
    d.text((M + 4, sub_y), f"{now.month} 月 {now.day} 日 · 昨天的充电量", font=font("light", 18), fill=BLACK)

    y = sub_y + 34
    if not stats:
        d.text((M, y), "昨天没有充电记录", font=font("light", 18), fill=BLACK)
        y += 34
        for i in range(4):
            d.text((M, y + 6), ["C1", "C2", "C3", "A"][i], font=font("bold", 19), fill=BLACK)
            d.rectangle([M + 46, y, CARD_W - M - 74, y + 18], outline=BLACK, width=2)
            d.text((CARD_W - M - 66, y + 2), "0.0 Wh", font=font("num", 13), fill=BLACK)
            y += 34
    colors = [YELLOW, RED, BLUE, GREEN]
    peak = max([x["wh"] for x in stats] or [1])
    for i, st in enumerate(stats[:4]):
        d.text((M, y), st["port"], font=font("bold", 18), fill=BLACK)
        d.text((M + 62, y), f"{st['wh']:.1f} Wh", font=font("num", 18), fill=BLACK)
        d.text((CARD_W - M - text_width(d, f"峰值 {st['peak']} W", font("light", 13)), y + 4),
               f"峰值 {st['peak']} W", font=font("light", 17), fill=BLACK)
        y += 28
        _bar(d, M, y, GRID_W, 18, st["wh"] / peak, colors[i % 4])
        y += 42

    y = max(y + 10, 560)
    d.line([M, y - 12, CARD_W - M, y - 12], fill=BLACK, width=2)
    d.text((M, y), "今天累计", font=font("bold", 16), fill=BLACK)
    t_total = sum(x["wh"] for x in today)
    _num(d, CARD_W - M - text_width(d, f"{t_total:.1f}", font("num", 22)) - 26, y - 4,
         f"{t_total:.1f}", 22, BLACK, "Wh", 13, BLACK)
    y += 30
    if today:
        for st in today[:4]:
            d.text((M, y), st["port"], font=font("bold", 19), fill=BLACK)
            d.text((M + 56, y), f"{st['wh']:.1f} Wh", font=font("num", 15), fill=BLACK)
            d.text((M + 150, y + 2), f"{st['count']} 次", font=font("light", 16), fill=BLACK)
            y += 26
    else:
        d.text((M, y), "今天还没有充电", font=font("light", 19), fill=BLACK)
    d.text((M, CARD_H - 44), "数据来自本机 port_history.db", font=font("light", 11), fill=BLACK)
    return img


# ── 装饰图标（六色矢量小图，用来补留白） ──
def _icon_sun(d, x, y, s, color=YELLOW):
    import math
    r = s // 5
    cx, cy = x + s // 2, y + s // 2
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    for i in range(8):
        a = math.pi * i / 4
        d.line([cx + math.cos(a) * (r + 3), cy + math.sin(a) * (r + 3),
                cx + math.cos(a) * (r + 8), cy + math.sin(a) * (r + 8)], fill=color, width=3)


def _icon_cloud(d, x, y, s, color=BLUE):
    r = s // 6
    cx, cy = x + s // 2, y + s // 2
    for dx, dy, rr in ((-r, 0, r), (0, -r, r), (r, 0, r)):
        d.ellipse([cx + dx - rr, cy + dy - rr, cx + dx + rr, cy + dy + rr], fill=color)
    d.rectangle([cx - r, cy, cx + r, cy + r], fill=color)


def _icon_bolt(d, x, y, s, color=YELLOW):
    d.polygon([(x + s * .55, y), (x + s * .2, y + s * .58), (x + s * .45, y + s * .58),
               (x + s * .35, y + s), (x + s * .8, y + s * .42), (x + s * .52, y + s * .42)],
              fill=color)


def _icon_heart(d, x, y, s, color=RED):
    r = s // 4
    d.ellipse([x + s * .1, y + s * .18, x + s * .5, y + s * .58], fill=color)
    d.ellipse([x + s * .5, y + s * .18, x + s * .9, y + s * .58], fill=color)
    d.polygon([(x + s * .1, y + s * .45), (x + s * .9, y + s * .45), (x + s * .5, y + s * .9)],
              fill=color)


def _icon_cup(d, x, y, s, color=RED):
    # 杯身（梯形）+ 把手 + 托盘
    d.polygon([(x + s * .16, y + s * .34), (x + s * .68, y + s * .34),
               (x + s * .60, y + s * .80), (x + s * .24, y + s * .80)], fill=color)
    d.arc([x + s * .60, y + s * .40, x + s * .92, y + s * .68], start=-80, end=80, fill=color, width=4)
    d.line([x + s * .08, y + s * .88, x + s * .78, y + s * .88], fill=BLACK, width=3)
    for i in range(2):
        d.line([x + s * (.32 + i * .16), y + s * .18, x + s * (.36 + i * .16), y + s * .06],
               fill=BLACK, width=2)


def _icon_book(d, x, y, s, color=BLUE):
    # 摊开的书：左右两页往下收
    d.polygon([(x + s * .10, y + s * .30), (x + s * .48, y + s * .22),
               (x + s * .48, y + s * .80), (x + s * .10, y + s * .86)], fill=color)
    d.polygon([(x + s * .52, y + s * .22), (x + s * .90, y + s * .30),
               (x + s * .90, y + s * .86), (x + s * .52, y + s * .80)], fill=color)
    d.line([x + s * .50, y + s * .20, x + s * .50, y + s * .84], fill=BLACK, width=2)


def _icon_leaf(d, x, y, s, color=GREEN):
    d.ellipse([x + s * .12, y + s * .08, x + s * .88, y + s * .70], fill=color)
    d.line([x + s * .5, y + s * .70, x + s * .5, y + s * .94], fill=BLACK, width=3)


def _icon_smiley(d, x, y, s, color=YELLOW):
    cx, cy, r = x + s // 2, y + s // 2, s // 2 - 2
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color, outline=BLACK, width=2)
    d.ellipse([cx - r // 3, cy - r // 4, cx - r // 3 + 3, cy - r // 4 + 3], fill=BLACK)
    d.ellipse([cx + r // 4, cy - r // 4, cx + r // 4 + 3, cy - r // 4 + 3], fill=BLACK)
    d.arc([cx - r // 2, cy - r // 6, cx + r // 2, cy + r // 2], start=20, end=160, fill=BLACK, width=2)


ICONS = {
    "sun": _icon_sun, "cloud": _icon_cloud, "bolt": _icon_bolt, "heart": _icon_heart,
    "cup": _icon_cup, "book": _icon_book, "leaf": _icon_leaf, "smiley": _icon_smiley,
}


def draw_icon(d, name, x, y, size):
    fn = ICONS.get(name)
    if fn:
        fn(d, x, y, size)


def icon_row(d, y, names, size=34, gap=10, x=None):
    """一排小图标，用来点缀留白。"""
    x = M if x is None else x
    for i, n in enumerate(names):
        draw_icon(d, n, x + i * (size + gap), y, size)


# ── AI 生图 ──
def render_ai(ctx: dict):
    now: datetime = ctx["now"]
    ai = ctx.get("ai") or {}
    prompt = (ai.get("prompt") or "").strip()
    img_path = ai.get("image")
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    if img_path:
        from PIL import Image
        band = 104
        src = Image.open(img_path).convert("RGB")
        w0, h0 = src.size
        src = src.crop((0, 0, w0, int(h0 * 0.96)))       # 去掉出图服务角标
        img.paste(_cover(src, CARD_W, CARD_H - band), (0, 0))
        d.rectangle([0, CARD_H - band, CARD_W, CARD_H], fill=WHITE)
        d.line([0, CARD_H - band, CARD_W, CARD_H - band], fill=BLACK, width=3)
        lines = wrap(d, prompt, font("light", 15), CARD_W - 48, word_wrap=True)[:2]
        yy = CARD_H - band + 14
        for ln in lines:
            d.text((24, yy), ln, font=font("light", 19), fill=BLACK)
            yy += 22
        d.text((24, CARD_H - 30), "AI 生图 · 土豆片", font=font("light", 16), fill=BLACK)
        return img

    # 还没生成：一张干净的"待生成"海报，不要报错占位
    _head(d, "AI 生图", now.strftime("%m/%d"))
    d.text((M, 120), "还没有生成图片", font=font("bold", 30), fill=BLACK)
    d.text((M, 168), "在控制台点「生成一张」，或在提示词里写你想要的画面。",
           font=font("light", 16), fill=BLACK)
    d.rectangle([M, 230, CARD_W - M, 470], outline=BLACK, width=3)
    d.text((M + 20, 250), "提示词", font=font("bold", 16), fill=BLACK)
    yy = 286
    for ln in wrap(d, prompt or "a quiet desk at night, warm lamp, cinematic",
                   font("light", 18), CARD_W - M * 2 - 40)[:6]:
        d.text((M + 20, yy), ln, font=font("light", 18), fill=BLACK)
        yy += 28
    d.text((M, CARD_H - 60), "生成后这里会变成整张图", font=font("light", 17), fill=BLACK)
    return img


# ─────────────────────────── 自定义卡片（积木式） ───────────────────────────
# 用户自己排：每个 block 是一个可开关/可排序的积木，竖着往下堆。
# 支持：标题 / 日期大字 / 天气 / 待办 / 一句 / 点阵 / 图片 / 文本 / 分割线
ACCENTS = {"red": RED, "blue": BLUE, "green": GREEN, "black": BLACK, "yellow": YELLOW}


def _draw_block(d, img, blk, ctx, y, width, accent):
    """画一个积木，返回它占的高度。"""
    t = blk.get("type")
    align = blk.get("align") or "left"
    x = M
    w = width
    if t == "title":
        text = blk.get("text") or ctx.get("title") or "土豆片"
        f = font("bold", 30)
        d.rectangle([x, y + 2, x + 10, y + 30], fill=accent)
        d.text((x + 20, y), text, font=f, fill=BLACK)
        return 46
    if t == "bigdate":
        now = ctx["now"]
        f = font("num", 88)
        txt = f"{now.day:02d}"
        d.text((x, y), txt, font=f, fill=accent)
        wd = text_width(d, txt, f)
        d.text((x + wd + 8, y + 44), f"{now.month}月", font=font("bold", 20), fill=BLACK)
        d.text((x + wd + 8, y + 72), WEEKDAYS[now.weekday()], font=font("light", 19), fill=BLACK)
        return int(88 * scale()) + 6
    if t == "weather":
        wdata = ctx.get("weather") or {}
        if wdata.get("temp") is None:
            return 0
        f = font("num", 54)
        s = f"{wdata['temp']:.0f}°"
        d.text((x, y), s, font=f, fill=BLACK)
        ww = text_width(d, s, f)
        d.text((x + ww + 10, y + 10), wdata.get("text", ""), font=font("bold", 20), fill=accent)
        d.text((x + ww + 10, y + 38), f"{wdata.get('city','')} {round(wdata.get('lo',0))}~{round(wdata.get('hi',0))}°",
               font=font("light", 18), fill=BLACK)
        return int(54 * scale()) + 18
    if t == "todos":
        todos = [x_ for x_ in (ctx.get("todos") or []) if x_][:5]
        yy = y
        if not todos:
            d.text((x, yy), "今天还没有安排", font=font("light", 19), fill=BLACK)
            return 34
        for i, item in enumerate(todos):
            _circle(d, x + 10, yy + 12, 10, accent)
            f = font("light", 21)
            lines = wrap(d, item, f, w - 34)[:1]
            d.text((x + 30, yy), lines[0] if lines else item, font=f, fill=BLACK)
            yy += 34
        return yy - y + 6
    if t == "quote":
        q = ctx.get("quote") or {}
        f = font("light", 20)
        lines = wrap(d, f"“{q.get('text','')}”", f, w)[:2]
        yy = y
        for ln in lines:
            d.text((x, yy), ln, font=f, fill=BLACK)
            yy += 40
        if q.get("from"):
            d.text((x, yy), f"— {q['from']}", font=font("light", 13), fill=accent)
            yy += 22
        return yy - y + 4
    if t == "dots":
        now = ctx["now"]
        passed = (now - datetime(now.year, 1, 1)).days + 1
        total = 365 + (1 if now.year % 4 == 0 else 0)
        n = 52
        filled = int(n * passed / total)
        for i in range(n):
            cx = x + i * 8.6
            _circle(d, cx, y + 8, 3.2, accent if i < filled else BLACK,
                    outline=i >= filled, width=1)
        d.text((x, y + 24), f"已过 {passed}/{total} 天", font=font("light", 16), fill=BLACK)
        return 38
    if t == "text":
        f = font(blk.get("weight") or "light", int(blk.get("size") or 20))
        txt = blk.get("text") or ""
        lines = wrap(d, txt, f, w)[:4]
        yy = y
        for ln in lines:
            if align == "center":
                d.text((x + (w - text_width(d, ln, f)) / 2, yy), ln, font=f, fill=BLACK)
            else:
                d.text((x, yy), ln, font=f, fill=BLACK)
            yy += int(f.size * 1.35)
        return yy - y + 4
    if t == "rule":
        d.line([x, y + 6, x + w, y + 6], fill=BLACK, width=2)
        return 18
    if t == "image":
        src = ctx.get("image_path")
        h = int(blk.get("height") or 260)
        if not src:
            d.rectangle([x, y, x + w, y + h], outline=BLACK, width=1)
            d.text((x + 16, y + 16), "（没有可用图片）", font=font("light", 19), fill=BLACK)
            return h
        from PIL import Image
        try:
            im = Image.open(src).convert("RGB")
            img.paste(_cover(im, int(w), h), (int(x), int(y)))
        except Exception:
            pass
        return h
    return 0


def render_custom(ctx: dict):
    """按用户给的 blocks 顺序渲染。放不下就整体缩字号，而不是裁掉内容。"""
    cfg = ctx.get("custom") or {}
    blocks = cfg.get("blocks") or []
    accent = ACCENTS.get(cfg.get("accent") or "red", RED)
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, cfg.get("title") or "我的卡片", ctx["now"].strftime("%m/%d"))

    y = 92
    for blk in blocks:
        if blk.get("on") is False:
            continue
        h = _draw_block(d, img, blk, ctx, y, CARD_W - M * 2, accent)
        y += h + 14
        if y > CARD_H - 40:
            break
    d.line([M, CARD_H - 40, CARD_W - M, CARD_H - 40], fill=BLACK, width=1)
    d.text((M, CARD_H - 32), "自定义 · 土豆片", font=font("light", 11), fill=BLACK)
    return img


def fit_content(img, margin: int = 18, max_scale: float = 1.6, min_scale: float = 1.0):
    """把画好的内容按"非白像素"包围盒整体放大，尽量填满画布。

    元素位置不变、比例不变，只是整体放大——这正是"元素固定时把版面撑满"的做法。
    放大会让笔画变粗，所以先用 2 倍超采样画一遍再缩回来会更锐利；
    这里直接对 528×792 放大，六色抖动后会自然把边缘量化，观感可以接受。
    """
    from PIL import Image
    gray = img.convert("L")
    mask = gray.point(lambda v: 255 if v < 248 else 0)
    bbox = mask.getbbox()
    if not bbox:
        return img
    x0, y0, x1, y1 = bbox
    cw, ch = max(1, x1 - x0), max(1, y1 - y0)
    avail_w, avail_h = CARD_W - margin * 2, CARD_H - margin * 2
    factor = min(avail_w / cw, avail_h / ch)
    factor = max(min_scale, min(max_scale, factor))
    if factor <= 1.01:
        return img
    crop = img.crop(bbox)
    nw, nh = int(cw * factor), int(ch * factor)
    crop = crop.resize((nw, nh), Image.LANCZOS)
    out = Image.new("RGB", (CARD_W, CARD_H), WHITE)
    out.paste(crop, ((CARD_W - nw) // 2, (CARD_H - nh) // 2))
    return out


def distribute_vertical(img, margin: int = 20, max_gap: int = 64, merge: int = 30):
    """把画面按"内容带"均匀铺开，填掉中间的大块留白。

    做法：找出有墨迹的横向条带（相邻行间距 < merge 的合并成一条，避免把
    同一段文字的行距拉开），再把富余高度均摊到条带之间，每条间隙最多 max_gap。
    元素本身一个像素都不缩放，只调整块与块之间的间距——这正是"元素固定、
    靠间距填满画面"的做法。
    """
    from PIL import Image
    gray = img.convert("L")
    w, h = gray.size
    px = gray.load()
    rows = []
    for yy in range(h):
        ink = False
        for xx in range(0, w, 3):
            if px[xx, yy] < 246:
                ink = True
                break
        rows.append(ink)

    bands, start = [], None
    for yy, ink in enumerate(rows):
        if ink and start is None:
            start = yy
        elif not ink and start is not None:
            bands.append((start, yy))
            start = None
    if start is not None:
        bands.append((start, h))
    if not bands:
        return img
    merged = [list(bands[0])]
    for s_, e_ in bands[1:]:
        if s_ - merged[-1][1] < merge:
            merged[-1][1] = e_
        else:
            merged.append([s_, e_])
    if len(merged) < 2:
        return img

    content_h = sum(e - s for s, e in merged)
    avail = h - margin * 2
    if content_h >= avail:
        return img
    gap = (avail - content_h) / (len(merged) - 1)
    gap = min(gap, max_gap)

    out = Image.new("RGB", (w, h), WHITE)
    y = margin
    for s, e in merged:
        out.paste(img.crop((0, s, w, e)), (0, int(y)))
        y += (e - s) + gap
    return out


# ─────────────────────────── 主题：流浪地球（工业 HUD） ───────────────────────────
# 底色黑、字白、警示红、提示黄；加边框刻度、细网格、编号与坐标前缀。
_THEME = "light"


def set_theme(name: str):
    global _THEME
    _THEME = "wo" if str(name).lower() in ("wo", "wandering", "scifi") else "light"


def theme() -> str:
    return _THEME


def tc_fg():
    return WHITE if _THEME == "wo" else BLACK


def tc_bg():
    return BLACK if _THEME == "wo" else WHITE


def tc_accent():
    return RED if _THEME == "wo" else RED


def tc_second():
    return YELLOW


def hud_frame(img):
    """流浪地球式边框：四角刻度 + 顶部编号 + 细网格。只在 wo 主题下调用。"""
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    m = 10
    # 外框
    d.rectangle([m, m, CARD_W - m, CARD_H - m], outline=WHITE, width=2)
    # 四角刻度
    L = 34
    for (x, y, dx, dy) in ((m, m, 1, 1), (CARD_W - m, m, -1, 1),
                           (m, CARD_H - m, 1, -1), (CARD_W - m, CARD_H - m, -1, -1)):
        d.line([x, y, x + dx * L, y], fill=YELLOW, width=4)
        d.line([x, y, x, y + dy * L], fill=YELLOW, width=4)
    # 细网格（很淡？六色没有淡色，用稀疏短线代替）
    for i in range(1, 4):
        yy = m + i * (CARD_H - 2 * m) / 4
        for xx in range(m + 40, CARD_W - m - 40, 60):
            d.line([xx, yy, xx + 18, yy], fill=WHITE, width=1)
    return img


def hud_header(d, code: str, title: str, right: str = ""):
    """HUD 风格标题：编号 + 中文标题 + 右侧状态。"""
    d.text((M, 18), code, font=font("num", 12), fill=YELLOW)
    d.text((M, 34), title, font=font("bold", 28), fill=WHITE)
    if right:
        w = text_width(d, right, font("num", 14))
        d.text((CARD_W - M - w, 20), right, font=font("num", 14), fill=WHITE)
    d.line([M, 82, CARD_W - M, 82], fill=WHITE, width=3)
    d.line([M, 74, M + 90, 74], fill=RED, width=6)


def to_dark_hud(img):
    """把浅色卡整体转成「流浪地球」工业风：白底→黑底、黑字→白字、彩色保持。

    逐像素换色而不是简单反相——反相会把红变成青，六色全废。
    """
    from PIL import ImageDraw
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            mx, mn = max(r, g, b), min(r, g, b)
            # 高饱和像素是红/蓝/绿/黄，原样保留；其余按亮度二值化，
            # 否则文字的抗锯齿边缘会留成灰色，在黑底上像"糊了"。
            if mx - mn > 60:
                continue
            px[x, y] = (0, 0, 0) if mx > 140 else (255, 255, 255)
    # 阈值化会把细笔画打出针孔：把白色像素向四邻扩一圈，字就实了
    from PIL import Image as _Im
    from PIL import ImageFilter as _IF
    mask = _Im.new("L", (w, h), 0)
    mp = mask.load()
    for yy in range(h):
        for xx in range(w):
            if px[xx, yy] == (255, 255, 255):
                mp[xx, yy] = 255
    grown = mask.filter(_IF.MaxFilter(3))
    gp = grown.load()
    for yy in range(h):
        for xx in range(w):
            if gp[xx, yy] and px[xx, yy] == (0, 0, 0):
                px[xx, yy] = (255, 255, 255)

    d = ImageDraw.Draw(img)
    m = 10
    d.rectangle([m, m, w - m, h - m], outline=WHITE, width=2)
    L = 36
    for (x, y, dx, dy) in ((m, m, 1, 1), (w - m, m, -1, 1),
                           (m, h - m, 1, -1), (w - m, h - m, -1, -1)):
        d.line([x, y, x + dx * L, y], fill=YELLOW, width=4)
        d.line([x, y, x, y + dy * L], fill=YELLOW, width=4)

    return img
