"""土豆片卡片渲染器（528×792 六色）—— 模板集合。

全部用**精确调色板色**作画：六色墨水屏只有 黑/白/黄/红/蓝/绿 六个值，
文字/色块用准确 RGB 时，上游的 Floyd–Steinberg 误差为 0，不会被抖动糊掉；
只有照片需要真正的抖动。

═══════════════════════ 版式规范（所有模板共用，改版先改这里）═══════════════════════

画布 528×792。真机边框会吃掉 2~3mm，所以四周留出安全边距 M=32，
可用区 x:32~496（宽 464）、y:32~760。所有纵向坐标落在 8px 网格上。

槽位（每个模板必须落在这几条线上）：
    Y_HEAD = 28    标题（30pt）顶部；左上红方块 13×13 画在 (M, 36)
    Y_META = 36    右上角小字（16pt）
    Y_RULE = 88    页眉分隔线（需要的模板才画）
    Y_HERO = 104   主视觉（大数字/大图）顶部
    Y_BODY = 272   正文起点
    Y_BODY_END=632 正文终点（内容不得越过这条线）
    Y_FOOT_RULE=656  页脚分隔线
    Y_FOOT = 672     页脚文字（行距 24，最多 2 行 + 右侧署名）

字号阶梯（FS）只有这几档，不许随手写别的值：
    title 30 / meta 16 / hero 96 / hero_unit 38 / h2 20 / body 19 / small 16 / tiny 13
行距：正文 24/28，小字 20/24。文字一律 左对齐 x=M、右对齐右边缘 CARD_W-M。

字号缩放（网页上的 100%~130%）走 `P(px)`：所有行距、块高都用 P() 换算，
所以放大字号时行距同步放大，不会出现"字大了压到下一行"。每个模板在排之前
先算一遍需要多高，超了就按比例降字号（最低 0.85），不做"事后整体挪动"。

原则：
  1) 先算高度再画，绝不画完再挪（旧版的 fit_content / distribute_vertical 已废弃）；
  2) 同一张卡最多 3 种彩色，颜色只用于强调，不铺底；
  3) 数字用等宽感更强的 num 字体，中文用 bold/light，右对齐一律量宽后再画。
"""
from __future__ import annotations

import calendar as _cal
from datetime import date, datetime, timedelta

CARD_W, CARD_H = 528, 792
BLACK = (0, 0, 0)
WHITE = (255, 255, 255)
YELLOW = (255, 255, 0)
RED = (255, 0, 0)
BLUE = (0, 0, 255)
GREEN = (0, 255, 0)

WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
WEEKDAYS_SHORT = ["一", "二", "三", "四", "五", "六", "日"]

# ══════════════════════════════ 版式规范 ══════════════════════════════
M = 32                            # 安全边距（左右统一）
GRID_W = CARD_W - M * 2           # 464
COL = GRID_W / 12                 # 38.67，12 栏网格
Y_HEAD = 28                       # 标题顶
Y_META = 36                       # 右上小字顶
Y_RULE = 88                       # 页眉分隔线
Y_HERO = 104                      # 主视觉顶
Y_BODY = 272                      # 正文顶
Y_BODY_END = 676                  # 正文底（不得越过）
Y_FOOT_RULE = 700                 # 页脚分隔线
Y_FOOT = 716                      # 页脚文字顶（1 行到 734，2 行到 758）
Y_FOOT_LINE = 24                  # 页脚行距
Y_SAFE_BOTTOM = 760               # 安全区下沿

FS = {"title": 30, "meta": 16, "hero": 96, "hero_unit": 38,
      "h2": 20, "body": 19, "small": 16, "tiny": 13}

FONTS = {
    "bold": [
        "/System/Library/Fonts/STHeiti Medium.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
        "/System/Library/Fonts/Supplemental/Songti.ttc",
        "C:/Windows/Fonts/msyhbd.ttc",
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    ],
    "light": [
        "/System/Library/Fonts/STHeiti Light.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
        "/System/Library/Fonts/Supplemental/Songti.ttc",
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/simsun.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    ],
    "num": [
        "/System/Library/Fonts/HelveticaNeue.ttc",
        "/System/Library/Fonts/Avenir Next.ttc",
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/SFNS.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
        "C:/Windows/Fonts/arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ],
}

#: 全局字号缩放（网页上的 100%~130%）。只影响字号与行距，不改边距。
_SCALE = 1.0


def set_scale(v: float):
    global _SCALE
    try:
        _SCALE = max(0.85, min(1.35, float(v)))
    except (TypeError, ValueError):
        _SCALE = 1.0


def scale() -> float:
    return _SCALE


def P(px) -> int:
    """把规范里的像素值按当前字号缩放换算（行距/块高都要过它）。"""
    return int(round(float(px) * _SCALE))


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
    # 避头尾会把字符挪到下一行，可能把那一行顶出宽度：再按实测宽度顺延一次
    i = 0
    while i < len(lines):
        guard = 0
        while len(lines[i]) > 1 and d.textlength(lines[i], font=f) > max_w and guard < 40:
            moved = lines[i][-1]
            lines[i] = lines[i][:-1]
            if i + 1 < len(lines):
                lines[i + 1] = moved + lines[i + 1]
            else:
                lines.append(moved)
            guard += 1
        i += 1
    return lines or [""]


def _right_font(text: str, size: int):
    """右侧文字可能是中文（周五/个口），拉丁字体没有汉字会渲染成方框。"""
    has_cjk = any(ord(ch) > 127 for ch in str(text))
    return font("bold" if has_cjk else "num", size)


#: 六色调色板（顺序必须和 payload.py 的 PALETTE 一致）
PALETTE_RGB = [BLACK, WHITE, YELLOW, RED, BLUE, GREEN]


def snap_palette(img, keep=None):
    """把整张卡的像素吸附到 6 个精确色，不抖动。

    为什么必须做：PIL 画字/画圆时会把边缘做抗锯齿，产生大量灰色中间值；
    上游 payload.py 又会对整张图做 Floyd–Steinberg 抖动，这些灰边就被"扩散"
    成背景上的彩色噪点（真机上看起来就是白底有杂色斑点）。
    只要送进去的每个像素本来就在调色板里，抖动误差为 0，背景就是干净的纯白。

    keep: 需要保留原样（继续抖动）的矩形区域列表 [(x0,y0,x1,y1), ...]，
          用于照片/图片块——照片需要抖动，文字区域需要吸附。
    """
    from PIL import Image

    pal = Image.new("P", (1, 1))
    flat = []
    for c in PALETTE_RGB:
        flat += list(c)
    flat += [0] * (768 - len(flat))
    pal.putpalette(flat)
    quant = img.quantize(palette=pal, dither=Image.NONE).convert("RGB")
    if keep:
        for box in keep:
            x0, y0, x1, y1 = [int(v) for v in box]
            x0, y0 = max(0, x0), max(0, y0)
            x1, y1 = min(CARD_W, x1), min(CARD_H, y1)
            if x1 > x0 and y1 > y0:
                quant.paste(img.crop((x0, y0, x1, y1)), (x0, y0))
    return quant


def palette_errors(img) -> int:
    """统计有多少像素不在调色板里（调试用：正常卡应该是 0）。"""
    px = img.load()
    w, h = img.size
    palset = set(PALETTE_RGB)
    bad = 0
    for y in range(h):
        for x in range(w):
            if px[x, y] not in palset:
                bad += 1
    return bad


def _snapped(fn):
    """给"纯文字/图形"模板统一加一步：出图前吸附到 6 色，去掉抗锯齿灰边。"""
    def wrapper(ctx):
        return snap_palette(fn(ctx))
    wrapper.__name__ = getattr(fn, "__name__", "render")
    wrapper.__doc__ = getattr(fn, "__doc__", "")
    return wrapper



def _right(d, text, y, size, kind="num", color=BLACK):
    """右对齐：右边缘固定 CARD_W-M。"""
    f = _right_font(text, size) if kind is None else font(kind, size)
    w = text_width(d, text, f)
    x = CARD_W - M - w
    d.text((x, y), str(text), font=f, fill=color)
    return x


def _rule(d, y, x0=M, x1=CARD_W - M, w=2, color=BLACK):
    d.line([x0, y, x1, y], fill=color, width=w)


def _head(d, title, meta="", accent=RED):
    """统一页眉：左上红方块 + 标题 + 右上小字。"""
    d.rectangle([M, Y_HEAD + 8, M + 13, Y_HEAD + 21], fill=accent)
    d.text((M + 24, Y_HEAD), title, font=font("bold", FS["title"]), fill=BLACK)
    if meta:
        _right(d, meta, Y_META, FS["meta"], kind=None)


def _foot(d, left="", right="", rule=True):
    """统一页脚：分隔线 + 左侧说明 + 右侧署名/时间。"""
    if rule:
        _rule(d, Y_FOOT_RULE)
    if left:
        d.text((M, Y_FOOT), left, font=font("light", FS["small"]), fill=BLACK)
    if right:
        _right(d, right, Y_FOOT, FS["small"], kind=None)


def _section(d, y, title, right=""):
    """小节标题：左侧 20pt 粗体，右侧小字，下面一条细线。返回内容起始 y。"""
    d.text((M, y), title, font=font("bold", FS["h2"]), fill=BLACK)
    tw = text_width(d, title, font("bold", FS["h2"]))
    x1 = CARD_W - M
    if right:
        rf = _right_font(right, FS["meta"])
        rw = text_width(d, right, rf)
        d.text((CARD_W - M - rw, y + P(2)), right, font=rf, fill=BLACK)
        x1 = CARD_W - M - rw - P(12)
    _rule(d, y + P(28), x0=M + tw + P(12), x1=x1, w=2)
    return y + P(44)


def _bar(d, x, y, w, h, pct, color=RED, outline=True):
    pct = max(0.0, min(1.0, float(pct)))
    if outline:
        d.rectangle([x, y, x + w, y + h], outline=BLACK, width=2)
        if pct > 0:
            d.rectangle([x + 2, y + 2, x + 2 + int((w - 4) * pct), y + h - 2], fill=color)
    else:
        d.rectangle([x, y, x + int(w * pct), y + h], fill=color)


def _circle(d, cx, cy, r, color=BLACK, outline=False, width=3):
    box = [cx - r, cy - r, cx + r, cy + r]
    if outline:
        d.ellipse(box, outline=color, width=width)
    else:
        d.ellipse(box, fill=color)


def _num(d, x, y, value, size, color=BLACK, unit="", unit_size=None, unit_color=None):
    """数字 + 单位（单位按数字字号的比例缩小，基线下沉对齐）。"""
    f = font("num", size)
    d.text((x, y), value, font=f, fill=color)
    w = text_width(d, value, f)
    if unit:
        uf = font("num", unit_size or int(size * 0.42))
        d.text((x + w + 2, y + int(size * 0.22)), unit, font=uf,
               fill=unit_color or color)
        w += text_width(d, unit, uf) + 2
    return x + w


def _aqi_label(aqi):
    if aqi is None:
        return "", BLACK
    if aqi <= 50:
        return "优", GREEN
    if aqi <= 100:
        return "良", YELLOW
    if aqi <= 150:
        return "轻度污染", RED
    if aqi <= 200:
        return "中度污染", RED
    return "重度污染", RED


def _aqi_advice(aqi):
    if aqi is None:
        return ""
    if aqi <= 50:
        return "空气很好，出门走走"
    if aqi <= 100:
        return "可以正常活动"
    if aqi <= 150:
        return "敏感人群少出门"
    if aqi <= 200:
        return "建议戴口罩"
    return "建议关窗"


def _wx_icon(d, x: int, y: int, size: int, code):
    """六色天气小图标：晴=黄太阳+黑光芒，多云=黄日+蓝云，雨=蓝云+斜线，雪=蓝点。"""
    try:
        c = int(code)
    except (TypeError, ValueError):
        c = 0
    s = size
    if c in (0, 1):
        _sun(d, x + s // 2, y + s // 2, int(s * 0.26))
        if c == 1:
            _cloud(d, x + int(s * 0.52), y + int(s * 0.52), int(s * 0.34), BLUE)
    elif c in (2, 3, 45, 48):
        _sun(d, x + int(s * 0.30), y + int(s * 0.30), int(s * 0.20))
        _cloud(d, x + int(s * 0.52), y + int(s * 0.55), int(s * 0.36), BLUE)
    elif 51 <= c <= 67:
        _cloud(d, x + s // 2, y + int(s * 0.42), int(s * 0.38), BLUE)
        for i in range(3):
            x0 = x + int(s * 0.26) + i * int(s * 0.20)
            d.line([x0, y + int(s * 0.68), x0 - int(s * 0.08), y + int(s * 0.90)],
                   fill=BLUE, width=max(3, s // 16))
    elif 71 <= c <= 77:
        _cloud(d, x + s // 2, y + int(s * 0.42), int(s * 0.38), BLUE)
        for i in range(3):
            cx = x + int(s * 0.30) + i * int(s * 0.20)
            _circle(d, cx, y + int(s * 0.78), max(3, s // 18), BLACK)
    elif c >= 95:
        _cloud(d, x + s // 2, y + int(s * 0.44), int(s * 0.40), BLACK)
        d.line([x + int(s * 0.34), y + int(s * 0.70), x + int(s * 0.28), y + int(s * 0.92)],
               fill=YELLOW, width=max(4, s // 12))
        d.line([x + int(s * 0.64), y + int(s * 0.70), x + int(s * 0.58), y + int(s * 0.92)],
               fill=YELLOW, width=max(4, s // 12))
    else:
        _sun(d, x + s // 2, y + s // 2, int(s * 0.26))


def _sun(d, cx, cy, r):
    import math
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=YELLOW, outline=BLACK, width=3)
    for i in range(8):
        a = math.pi * i / 4
        d.line([cx + math.cos(a) * (r + 5), cy + math.sin(a) * (r + 5),
                cx + math.cos(a) * (r + 13), cy + math.sin(a) * (r + 13)],
               fill=BLACK, width=4)


def _cloud(d, cx, cy, r, color=BLUE):
    d.ellipse([cx - r, cy - r * 0.7, cx + r * 0.1, cy + r * 0.7], fill=color)
    d.ellipse([cx - r * 0.4, cy - r, cx + r * 0.6, cy + r * 0.5], fill=color)
    d.ellipse([cx + r * 0.1, cy - r * 0.6, cx + r * 1.2, cy + r * 0.6], fill=color)


# ══════════════════════════════ 模板 ══════════════════════════════

def _fit(need_h: int, avail_h: int, floor: float = 0.85) -> int:
    """内容比可用区高就按比例降字号（返回新的正文起点不变，靠临时改 _SCALE）。"""
    global _SCALE
    if need_h <= avail_h or need_h <= 0:
        return 0
    want = max(floor, _SCALE * (avail_h / need_h))
    if want >= _SCALE:
        return 0
    old = _SCALE
    _SCALE = want
    return 0 if want == old else -1


def _restore_scale(backup: float) -> None:
    global _SCALE
    _SCALE = backup


def _scale_backup() -> float:
    return _SCALE


# ── 每日总结 ──
def render_summary(ctx: dict):
    now: datetime = ctx["now"]
    wd = ctx.get("weather") or {}
    todos = [t for t in (ctx.get("todos") or []) if t]
    quote = ctx.get("quote") or {}
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, "每日总结", f"{now.month:02d}/{now.day:02d} {WEEKDAYS[now.weekday()]}")

    # ── 主视觉：左边日期，右边温度 + 天气图标（区块底部统一收在 236 之前）──
    day_f = font("num", 88)
    d.text((M, Y_HERO), f"{now.day:02d}", font=day_f, fill=RED)
    d.text((M, Y_HERO + P(84)), f"{now.month}月", font=font("bold", 18), fill=BLACK)

    icon_size = P(76)
    icon_x = CARD_W - M - icon_size
    right_x = M + int(COL * 6)
    avail_w = icon_x - right_x - P(18)
    if wd.get("temp") is not None:
        temp = f"{wd['temp']:.0f}"
        size = 84
        while size > 48:
            tf = font("num", size)
            df = font("num", int(size * 0.42))
            if text_width(d, temp, tf) + text_width(d, "°C", df) + 2 <= avail_w:
                break
            size -= 4
        tf = font("num", size)
        df = font("num", int(size * 0.42))
        d.text((right_x, Y_HERO + P(6)), temp, font=tf, fill=BLACK)
        tw = text_width(d, temp, tf)
        d.text((right_x + tw + 2, Y_HERO + P(6) + int(size * 0.16)), "°C",
               font=df, fill=RED)
        cond = f"{wd.get('text', '')} · {wd.get('city', '')}".strip(" ·")
        cf = font("light", 17)
        while text_width(d, cond, cf) > avail_w and cf.size > 12:
            cf = font("light", cf.size - 1)
        d.text((right_x, Y_HERO + P(96)), cond, font=cf, fill=BLACK)
        _wx_icon(d, icon_x, Y_HERO + P(4), icon_size, wd.get("code"))

    # ── 黄条分隔：固定落在 249~258，和下面的正文之间留 14 ──
    d.rectangle([M, Y_HERO + P(132), CARD_W - M, Y_HERO + P(141)], fill=YELLOW)

    # ── 今日待办 ──
    # 字号放大后正文会变高：锚点从页脚线往回收，保证点阵/计数不压到页脚
    year_h = P(58)
    year_y = min(Y_BODY + P(324), Y_FOOT_RULE - P(10) - year_h)
    month_y = min(Y_BODY + P(248), year_y - P(88))
    y = _section(d, Y_BODY, "今日待办", f"{len(todos)} 项")
    row_h = P(40)
    max_rows = max(1, int((month_y - P(16) - y) / row_h))
    show = todos[:min(4, max_rows)]
    if not show:
        d.text((M, y), "今天还没有安排", font=font("light", FS["body"]), fill=BLACK)
        y += row_h
    colors = [RED, BLUE, GREEN, BLACK]
    for i, item in enumerate(show):
        _circle(d, M + P(12), y + P(12), P(12), colors[i % 4])
        d.text((M + P(5), y + P(5)), str(i + 1), font=font("num", 13), fill=WHITE)
        f = font("light", 24)
        lines = wrap(d, item, f, CARD_W - M * 2 - P(40))[:2]
        for j, ln in enumerate(lines):
            d.text((M + P(32), y + j * P(28)), ln, font=f, fill=BLACK)
        y += row_h

    # ── 本月进度 ──
    d.text((M, month_y), "本月", font=font("bold", 18), fill=BLACK)
    mpct = now.day / _cal.monthrange(now.year, now.month)[1]
    _right(d, f"{mpct * 100:.0f}%", month_y, 18, kind="num")
    _bar(d, M, month_y + P(26), GRID_W, P(14), mpct, GREEN)

    # ── 全年点阵（左右各留 6 给圆点半径，右端与进度条严格对齐）──
    passed = (now - datetime(now.year, 1, 1)).days + 1
    total = 365 + (1 if now.year % 4 == 0 else 0)
    d.text((M, year_y), "全年", font=font("bold", 14), fill=BLACK)
    dots = 52
    dx0, dx1 = M + P(56), CARD_W - M - P(6)
    gap = (dx1 - dx0) / (dots - 1)
    filled = int(dots * passed / total)
    for i in range(dots):
        _circle(d, dx0 + i * gap, year_y + P(9), P(3.2),
                RED if i < filled else BLACK, outline=i >= filled, width=1)
    d.text((M, year_y + P(22)), f"{passed}/{total}", font=font("num", 15), fill=BLACK)

    # ── 页脚：每日一句（右下留给署名，行数按安全区剩余高度定）──
    _rule(d, Y_FOOT_RULE)
    qf = font("light", 18)
    sig_f = font("light", FS["small"])
    sig = "土豆片"
    sig_w = text_width(d, sig, sig_f)
    quote_txt = f"“{quote.get('text', '把今天过好。')}”"
    room_lines = max(1, int((Y_SAFE_BOTTOM - Y_FOOT - P(4)) / max(1, P(Y_FOOT_LINE))))
    all_lines = wrap(d, quote_txt, qf, GRID_W - int(sig_w) - P(18))
    qlines = all_lines[:min(2, room_lines)]
    if len(all_lines) > len(qlines) and qlines:
        qlines[-1] = qlines[-1][:-1] + "…”" if len(qlines[-1]) > 1 else qlines[-1] + "…”"
    yy = Y_FOOT
    for ln in qlines:
        d.text((M, yy), ln, font=qf, fill=BLACK)
        yy += P(Y_FOOT_LINE)
    # 署名与该段最后一行同基线，右对齐
    _right(d, sig, Y_FOOT + P(Y_FOOT_LINE) * (len(qlines) - 1), FS["small"], kind=None)
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
    if temp is None:
        y = Y_BODY
        d.text((M, y), "天气获取失败", font=font("bold", 28), fill=BLACK)
        d.text((M, y + P(44)), "检查网络，或在设置里换一个城市", font=font("light", FS["body"]),
               fill=BLACK)
        return img

    # ── 主视觉：温度 + 图标（区块底部统一收在 250 之前）──
    icon_size = P(88)
    icon_x = CARD_W - M - icon_size
    avail_w = icon_x - M - P(20)
    txt = f"{temp:.0f}"
    size = 108
    while size > 64:
        tf = font("num", size)
        df = font("num", int(size * 0.40))
        if text_width(d, txt, tf) + text_width(d, "°C", df) + 2 <= avail_w:
            break
        size -= 4
    tf = font("num", size)
    df = font("num", int(size * 0.40))
    d.text((M, Y_HERO), txt, font=tf, fill=BLACK)
    tw = text_width(d, txt, tf)
    d.text((M + tw + 2, Y_HERO + int(size * 0.14)), "°C", font=df, fill=RED)
    _wx_icon(d, icon_x, Y_HERO + P(6), icon_size, w.get("code"))

    # ── 天气词 + 横线：横线由天气词底部往下推，字不会顶到线上 ──
    cond_f = font("bold", 32)
    cond_y = Y_HERO + P(112)
    d.text((M, cond_y), w.get("text", ""), font=cond_f, fill=BLACK)
    rule_y = cond_y + int(cond_f.size * 1.2) + P(12)

    # ── 三段信息（最高/最低/体感）：三栏等宽 + 内边距，竖线画在栏边界上 ──
    cell_w = GRID_W / 3
    pad = max(6, P(10))
    y2 = max(Y_HERO + P(160), rule_y + P(14))
    _rule(d, rule_y, w=3)
    cells = [("最高", f"{round(w.get('hi', 0))}°", RED),
             ("最低", f"{round(w.get('lo', 0))}°", BLUE),
             ("体感", f"{round(w.get('feels', 0))}°", GREEN)]
    lab_f = font("light", FS["small"])
    val_f = font("num", 28)
    lab_h = int(lab_f.size * 1.25)
    val_h = int(val_f.size * 1.2)
    bar_h = max(4, P(6))
    for i, (label, val, color) in enumerate(cells):
        cx = M + cell_w * i
        d.text((cx + pad, y2), label, font=lab_f, fill=BLACK)
        d.text((cx + pad, y2 + lab_h + P(2)), val, font=val_f, fill=BLACK)
        bar_y = y2 + lab_h + P(2) + val_h + P(8)
        d.rectangle([cx + pad, bar_y, cx + pad + 32, bar_y + bar_h], fill=color)
        if i:
            d.line([cx - P(2), y2 - P(4), cx - P(2), bar_y + bar_h], fill=BLACK, width=1)

    # ── 未来几小时（AQI 药丸先占好位置，折线区域按剩余高度算，绝不压字）──
    hours = (w.get("hourly") or [])[:4]
    aqi = w.get("aqi")
    pill_h = P(52)
    pill_gap = P(16)
    aqi_top = (Y_FOOT_RULE - P(6) - pill_h) if aqi is not None else Y_FOOT_RULE - P(6)
    y = _section(d, y2 + P(88), "未来几小时")
    if hours:
        temps = [h.get("temp") or 0 for h in hours]
        lo, hi = min(temps), max(temps)
        span = max(6, hi - lo)
        mid = (hi + lo) / 2
        # 折线区 = 药丸上方全部空间 - 图上温度标注 - 下方两行文字
        avail = aqi_top - pill_gap - y - P(24) - P(12) - P(38)
        plot_h = max(P(56), min(P(150), avail))
        top = y + P(24)
        bottom = top + plot_h
        colw = GRID_W / max(1, len(hours))
        pts = []
        for i, t in enumerate(temps):
            px = M + colw * (i + 0.5)
            py = bottom - (t - (mid - span / 2)) / span * plot_h
            pts.append((px, py))
        for i in range(len(pts) - 1):
            d.line([pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]],
                   fill=BLUE, width=4)
        for i, (px, py) in enumerate(pts):
            d.ellipse([px - 6, py - 6, px + 6, py + 6], fill=BLUE)
            t_lbl = f"{temps[i]}°"
            d.text((px - text_width(d, t_lbl, font("num", 17)) / 2, py - P(30)),
                   t_lbl, font=font("num", 17), fill=BLACK)
            h_lbl = hours[i].get("t", "")
            d.text((px - text_width(d, h_lbl, font("num", 15)) / 2, bottom + P(12)),
                   h_lbl, font=font("num", 15), fill=BLACK)
            cnd = hours[i].get("text", "")
            d.text((px - text_width(d, cnd, font("light", 15)) / 2, bottom + P(34)),
                   cnd, font=font("light", 15), fill=BLACK)

    # ── 空气质量 ──
    if aqi is not None:
        label, color = _aqi_label(aqi)
        num_f, lab_f = font("num", 26), font("bold", 16)
        w_num = text_width(d, str(aqi), num_f)
        w_lab = text_width(d, label, lab_f)
        pill_w = P(20) + w_num + P(8) + w_lab + P(20)
        h = pill_h
        py = aqi_top
        d.rounded_rectangle([M, py, M + pill_w, py + h], radius=h // 2,
                            fill=color, outline=BLACK, width=3)
        tc = WHITE if color in (RED, BLUE, BLACK) else BLACK
        d.text((M + P(20), py + P(9)), str(aqi), font=num_f, fill=tc)
        d.text((M + P(28) + w_num, py + P(16)), label, font=lab_f, fill=tc)
        d.text((M + pill_w + P(16), py + P(15)), _aqi_advice(aqi),
               font=font("light", FS["small"]), fill=BLACK)
    _foot(d, "数据来自 Open-Meteo", now.strftime("%H:%M"))
    return img


# ── 色块天气版（分区拼贴：当前 + 空气质量 + 三天预报 + 日出日落 + 穿衣参考）──
def _fit_font(d, text, weight, size, max_w, min_size=10):
    """单行文字按宽度收字号，保证不撑破格子。"""
    s = size
    while s > min_size:
        f = font(weight, s)
        if text_width(d, text, f) <= max_w:
            return f
        s -= 1
    return font(weight, min_size)


def render_weather_blocks(ctx: dict):
    now: datetime = ctx["now"]
    w = ctx.get("weather") or {}
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    # 行高按像素固定（这样 130% 字号也不会把格子挤变形），字号按格子高度封顶
    top = M
    rows = [84, 168, 148, 124, 116, 88]          # 顶栏 / 主区 / 三天 / 日出+明天 / 穿衣 / 来源
    ys = [top]
    for h in rows:
        ys.append(ys[-1] + h)
    bottom = ys[-1]
    if bottom > Y_SAFE_BOTTOM:                    # 兜底：整块等比压缩到安全区
        k = (Y_SAFE_BOTTOM - top) / (bottom - top)
        rows = [int(h * k) for h in rows]
        ys = [top]
        for h in rows:
            ys.append(ys[-1] + h)
    x0, x1 = M, CARD_W - M
    mid = x0 + int(GRID_W * 0.66)                 # 主区左右分栏
    right = x1 - int(GRID_W * 0.34)

    def cell(a, b, c, e, fill=None, width=2):
        d.rectangle([a, b, c, e], fill=fill if fill else WHITE, outline=BLACK, width=width)

    def cap(row_h, ratio, base):
        """字号封顶：既要跟随字号缩放，又不能超出格子。"""
        return min(P(base), max(10, int(row_h * ratio)))

    # ── 格子内边距：所有文字都离边框至少 pad，真机上才不会像"压线" ──
    pad = max(6, P(9))
    gap = max(3, P(5))

    def th(f):
        return int(f.size * 1.2)

    # ── 顶栏：城市 / 风格 + 星期时间 ──
    r0, r1 = ys[0], ys[1]
    cell(x0, r0, mid, r1)
    cell(mid, r0, x1, r1, fill=YELLOW)
    rh = r1 - r0
    city = (w.get("city") or "本地").strip()
    sub_f = font("light", cap(rh, 0.15, 12))
    city_f = _fit_font(d, city, "bold", cap(rh, 0.34, 26), mid - x0 - 2 * pad)
    d.text((x0 + pad, r0 + pad), city, font=city_f, fill=BLACK)
    d.text((x0 + pad, r1 - pad - th(sub_f)), "色块天气版", font=sub_f, fill=BLACK)
    wk = f"{WEEKDAYS[now.weekday()]} {now.strftime('%H:%M')}"
    wk_f = _fit_font(d, wk, "bold", cap(rh, 0.30, 20), x1 - mid - 2 * pad)
    d.text((mid + (x1 - mid - text_width(d, wk, wk_f)) / 2, r0 + (rh - th(wk_f)) / 2),
           wk, font=wk_f, fill=BLACK)

    # ── 主区左：当前温度 / 天气 / 今日范围（自下而上排，底部留 pad）──
    r0, r1 = ys[1], ys[2]
    rh = r1 - r0
    cell(x0, r0, right, r1)
    temp = w.get("temp")
    lo, hi = w.get("lo"), w.get("hi")
    rng = ""
    if lo is not None and hi is not None:
        rng = f"今日 {round(lo)}—{round(hi)}°C"
    elif hi is not None:
        rng = f"今日最高 {round(hi)}°C"
    rng_f = font("light", cap(rh, 0.10, 13))
    rng_y = r1 - pad - th(rng_f)
    wx_f = font("bold", cap(rh, 0.15, 21))
    wx_y = rng_y - gap - th(wx_f)
    if temp is None:
        d.text((x0 + pad, r0 + pad), "天气获取失败", font=font("bold", 24), fill=BLACK)
    else:
        t_txt = f"{temp:.0f}"
        t_top = r0 + pad
        t_room = max(P(24), wx_y - gap - t_top)
        t_size = min(cap(rh, 0.52, 92), max(18, int(t_room / (1.05 * max(0.6, _SCALE)))))
        tf = _fit_font(d, t_txt, "num", t_size, right - x0 - P(84), 18)
        d.text((x0 + pad, t_top), t_txt, font=tf, fill=BLACK)
        uf = font("num", min(cap(rh, 0.22, 30), int(t_size * 0.36)))
        d.text((x0 + pad + text_width(d, t_txt, tf) + 2,
                t_top + int(th(tf) * 0.42)), "°C", font=uf, fill=RED)
        d.text((x0 + pad, wx_y), w.get("text", ""), font=wx_f, fill=BLACK)
        if rng:
            d.text((x0 + pad, rng_y), rng, font=rng_f, fill=BLACK)

    # ── 主区右上：空气质量（标签贴 pad，数字居中）──
    aqi = w.get("aqi")
    aqi_b = r0 + int(rh * 0.50)
    if aqi is not None:
        label, color = _aqi_label(aqi)
        cell(right, r0, x1, aqi_b, fill=color)
        tc = WHITE if color in (RED, BLUE, BLACK) else BLACK
        ah = aqi_b - r0
        lab_f = font("bold", cap(ah, 0.16, 11))
        d.text((right + pad, r0 + pad), "空气质量", font=lab_f, fill=tc)
        cat_y = aqi_b - pad - th(lab_f)
        d.text((right + pad, cat_y), label, font=lab_f, fill=tc)
        num_top = r0 + pad + th(lab_f) + gap
        num_room = max(P(14), cat_y - gap - num_top)
        aqi_f = _fit_font(d, str(aqi), "num",
                          min(cap(ah, 0.40, 30), max(12, int(num_room / 1.05))),
                          x1 - right - 2 * pad)
        d.text((right + pad, num_top + max(0, (num_room - th(aqi_f)) // 3)), str(aqi),
               font=aqi_f, fill=tc)
    # ── 主区右下：湿度 / 体感 / 风（内容区均分）──
    cell(right, aqi_b, x1, r1)
    rows_ = []
    if w.get("hum") is not None:
        rows_.append(("湿度", f"{round(w['hum'])}%"))
    if w.get("feels") is not None:
        rows_.append(("体感", f"{round(w['feels'])}°"))
    if w.get("wind_text"):
        rows_.append(("风", w["wind_text"]))
    rows_ = rows_[:3]
    if rows_:
        area_top, area_bottom = aqi_b + pad, r1 - pad
        slot = (area_bottom - area_top) / len(rows_)
        for i, (k, v) in enumerate(rows_):
            kf = font("light", cap(rh, 0.075, 11))
            has_cjk = any(ord(ch) > 127 for ch in v)
            vf = _fit_font(d, v, "bold" if has_cjk else "num", cap(rh, 0.085, 13),
                           x1 - right - 2 * pad - P(26))
            y = area_top + i * slot + max(0, (slot - th(vf)) / 2)
            d.text((right + pad, y), k, font=kf, fill=BLACK)
            d.text((x1 - pad - text_width(d, v, vf), y), v, font=vf, fill=BLACK)

    # ── 三天预报 ──
    r0, r1 = ys[2], ys[3]
    rh = r1 - r0
    lab_w = int(GRID_W * 0.18)
    cell(x0, r0, x0 + lab_w, r1, fill=BLUE)
    side_f = font("bold", cap(rh, 0.12, 14))
    d.text((x0 + (lab_w - text_width(d, "3天预报", side_f)) / 2,
            r0 + (rh - th(side_f)) / 2), "3天预报", font=side_f, fill=WHITE)
    days = (w.get("daily3") or [])[:3]
    day_w = (GRID_W - lab_w) / 3
    for i in range(3):
        cx0 = x0 + lab_w + i * day_w
        cx1 = cx0 + day_w
        cell(cx0, r0, cx1, r1)
        cx = (cx0 + cx1) / 2
        if i >= len(days):
            continue
        it = days[i]
        lbl = it.get("label") or ""
        lf = font("bold", cap(rh, 0.10, 12))
        d.text((cx - text_width(d, lbl, lf) / 2, r0 + pad), lbl, font=lf, fill=BLACK)
        date_f = font("light", cap(rh, 0.075, 10))
        date_y = r1 - pad - th(date_f)
        rng_f2 = font("num", cap(rh, 0.10, 13))
        rng2 = ""
        if it.get("hi") is not None and it.get("lo") is not None:
            rng2 = f"{it['lo']}°~{it['hi']}°"
        rng_y2 = date_y - gap - th(rng_f2) if rng2 else date_y
        wx_top = r0 + pad + th(lf) + gap
        wx_room = max(P(18), rng_y2 - gap - wx_top if rng2 else date_y - gap - wx_top)
        wx_txt = it.get("text") or "—"
        tf2 = _fit_font(d, wx_txt, "bold",
                        min(cap(rh, 0.17, 20), max(12, int(wx_room / 1.05))),
                        day_w - 2 * pad)
        d.text((cx - text_width(d, wx_txt, tf2) / 2, wx_top + max(0, (wx_room - th(tf2)) // 3)),
               wx_txt, font=tf2, fill=BLACK)
        if rng2:
            d.text((cx - text_width(d, rng2, rng_f2) / 2, rng_y2), rng2, font=rng_f2, fill=BLACK)
        if it.get("date"):
            d.text((cx - text_width(d, it["date"], date_f) / 2, date_y), it["date"],
                   font=date_f, fill=BLACK)

    # ── 日出 / 日落 + 明天 ──
    r0, r1 = ys[3], ys[4]
    rh = r1 - r0
    sun_w = int(GRID_W * 0.40)
    cell(x0, r0, x0 + sun_w, r1, fill=BLUE)
    sun_lab = font("bold", cap(rh, 0.09, 11))
    d.text((x0 + pad, r0 + pad), "日出 / 日落", font=sun_lab, fill=WHITE)
    sf = font("num", cap(rh, 0.18, 22))
    sunset_y = r1 - pad - th(sf)
    sunrise_y = sunset_y - gap - th(sf)
    sunrise_y = max(sunrise_y, r0 + pad + th(sun_lab) + gap)
    d.text((x0 + pad, sunrise_y), w.get("sunrise") or "--:--", font=sf, fill=WHITE)
    d.text((x0 + pad, sunset_y), w.get("sunset") or "--:--", font=sf, fill=WHITE)
    cell(x0 + sun_w, r0, x1, r1)
    tomorrow = days[1] if len(days) > 1 else {}
    tx = x0 + sun_w + pad
    tmax_w = x1 - tx - pad
    t_head = font("bold", cap(rh, 0.09, 12))
    d.text((tx, r0 + pad), f"明天 {tomorrow.get('date','')}".strip(), font=t_head, fill=BLACK)
    p = w.get("precip")
    today_line = f"今日：{w.get('text','')}"
    if p is not None:
        today_line += f" · 降水 {round(p)}%"
    tl_f = font("light", cap(rh, 0.085, 11))
    tl_y = r1 - pad - th(tl_f)
    t1 = (f"{tomorrow.get('text','')} {tomorrow.get('lo','')}—{tomorrow.get('hi','')}°C"
          if tomorrow.get("hi") is not None else (tomorrow.get("text") or "—"))
    big_room = max(P(18), tl_y - gap - (r0 + pad + th(t_head) + gap))
    t1_f = _fit_font(d, t1, "bold",
                     min(cap(rh, 0.17, 20), max(12, int(big_room / 1.05))), tmax_w)
    d.text((tx, r0 + pad + th(t_head) + gap + max(0, (big_room - th(t1_f)) // 3)), t1,
           font=t1_f, fill=BLACK)
    d.text((tx, tl_y), today_line, font=tl_f, fill=BLACK)

    # ── 穿衣参考（文字行数按剩余高度限制）──
    r0, r1 = ys[4], ys[5]
    rh = r1 - r0
    cell(x0, r0, x1, r1, fill=YELLOW)
    adv_lab = font("bold", cap(rh, 0.10, 12))
    d.text((x0 + pad, r0 + pad), "穿衣参考", font=adv_lab, fill=BLACK)
    af = font("light", cap(rh, 0.14, 15))
    avail_h = (r1 - pad) - (r0 + pad + th(adv_lab) + gap)
    max_lines = max(1, int(avail_h / max(1, int(af.size * 1.4))))
    yy = r0 + pad + th(adv_lab) + gap
    for ln in wrap(d, _clothing_advice(w.get("temp"), aqi), af,
                   GRID_W - 2 * pad - P(8))[:max_lines]:
        d.text((x0 + pad, yy), ln, font=af, fill=BLACK)
        yy += int(af.size * 1.4)

    # ── 来源条 ──
    r0, r1 = ys[5], ys[6]
    cell(x0, r0, x1, r1)
    src_f = font("light", 11)
    src = f"数据来源 Open-Meteo · 更新 {now.strftime('%H:%M')}"
    d.text((x0 + pad, r0 + (r1 - r0 - th(src_f)) / 2), src, font=src_f, fill=BLACK)
    return img


def _clothing_advice(temp, aqi) -> str:
    """按气温给穿衣建议（色块天气卡的底部条）。"""
    try:
        t = float(temp)
    except (TypeError, ValueError):
        return "天气数据还没取到，稍后再看。"
    if t <= 5:
        s = "气温较低，建议穿厚外套、毛衣，注意保暖。"
    elif t <= 12:
        s = "偏冷，外套 + 长袖，早晚记得加件衣服。"
    elif t <= 18:
        s = "凉爽舒适，长袖或薄外套都合适。"
    elif t <= 26:
        s = "舒适，短袖或薄外套都行。"
    else:
        s = "偏热，注意防晒、多补水。"
    try:
        if aqi is not None and int(aqi) > 150:
            s += "空气较差，出门建议戴口罩。"
    except (TypeError, ValueError):
        pass
    return s


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
    _num(d, M, Y_HERO, f"{pct * 100:.0f}", FS["hero"], RED, "%", 36, RED)
    info_y = Y_HERO + int(FS["hero"] * _SCALE) + P(8)
    d.text((M, info_y), f"已过 {passed} / {total} 天", font=font("light", 20), fill=BLACK)

    # ── 先分层分配空间（从下往上），再按分到的高度定尺寸，任何元素都不会越界 ──
    foot_gap = P(10)
    bottom_h = min(P(76), int(P(76) * 1.0))      # 「今年还剩」块
    bottom_y = Y_FOOT_RULE - foot_gap - bottom_h
    bars_pitch = P(40)
    bars_h = bars_pitch * 3
    bars_y = bottom_y - P(20) - bars_h
    dots_top = info_y + P(34)
    dots_bottom = bars_y - P(28)                 # 给分隔线留位置

    # 点阵：列数固定 32，行距同时受宽度和可用高度约束；半径由行距推出（永不粘连）
    cols = 32
    rows = (total + cols - 1) // cols
    dx0, dx1 = M + P(6), CARD_W - M - P(6)
    gap_h = (dx1 - dx0) / (cols - 1)
    gap_v = gap_h if rows <= 1 else min(gap_h, max(P(9), (dots_bottom - dots_top) / (rows - 1)))
    radius = max(P(1.6), min(P(5.5), gap_h * 0.40, gap_v * 0.40))
    dy = dots_top + radius
    for i in range(total):
        cx = dx0 + (i % cols) * gap_h
        cy = dy + (i // cols) * gap_v
        _circle(d, cx, cy, radius, RED if i < passed else BLACK,
                outline=i >= passed, width=max(1, min(2, int(radius / 3))))
    yb = dy + (rows - 1) * gap_v + radius + P(16)
    _rule(d, yb, w=2)

    # 本月 / 本周 / 今天：pitch 按剩余高度收缩
    m0 = datetime(now.year, now.month, 1)
    m1 = datetime(now.year + (now.month // 12), (now.month % 12) + 1, 1)
    mpct = (now - m0).total_seconds() / (m1 - m0).total_seconds()
    w0 = datetime(now.year, now.month, now.day) - timedelta(days=now.weekday())
    wpct = (now - w0).total_seconds() / (7 * 86400)
    dpct = (now.hour * 3600 + now.minute * 60) / 86400

    yy = yb + P(18)
    avail_bars = bottom_y - P(16) - yy
    pitch = max(P(26), min(bars_pitch, avail_bars // 3))
    labels = (("本月", mpct, GREEN), ("本周", wpct, BLUE), ("今天", dpct, RED))
    bar_h = max(P(12), min(P(20), int(pitch * 0.50)))
    lab_f = font("bold", min(18, max(12, int(pitch / _SCALE * 0.42))))
    pct_f = font("num", min(18, max(12, int(pitch / _SCALE * 0.42))))
    for label, val, color in labels:
        d.text((M, yy), label, font=lab_f, fill=BLACK)
        d.text((M + P(56), yy - P(2)), f"{val * 100:.0f}%", font=pct_f, fill=BLACK)
        _bar(d, M + P(130), yy, GRID_W - P(130), bar_h, val, color)
        yy += pitch

    # 今年还剩：固定贴页脚上方，块内自上而下排
    left_days = (y1 - now).days
    fy = bottom_y
    d.text((M, fy), "今年还剩", font=font("bold", min(18, max(13, int(bottom_h / _SCALE * 0.24)))),
           fill=BLACK)
    num_size = min(28, max(16, int(bottom_h / _SCALE * 0.36)))
    bar_y = fy + P(26)
    d.rectangle([M, bar_y, M + 10, min(bar_y + P(28), fy + bottom_h)], fill=RED)
    _num(d, M + P(20), bar_y - P(4), f"{left_days}", num_size, BLACK)
    d.text((M + P(20) + text_width(d, f"{left_days}", font("num", num_size)) + P(6),
            bar_y + P(2)), "天", font=font("bold", min(17, max(12, int(bottom_h / _SCALE * 0.22)))),
           fill=BLACK)
    _foot(d, "", "土豆片")
    return img


# ── 今日待办 ──
def render_todo(ctx: dict):
    now: datetime = ctx["now"]
    todos = [t for t in (ctx.get("todos") or []) if t]
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    # 页眉用黄色块：标题 / 日期 / 计数全部收在色块里，并给字号缩放留 6px 余量
    d.rectangle([0, 0, CARD_W, Y_RULE], fill=YELLOW)
    title_f = font("bold", 30)
    title = "今日待办"
    d.text((M, Y_HEAD), title, font=title_f, fill=BLACK)
    cnt = str(len(todos))
    cnt_f = font("num", 34)
    cnt_w = text_width(d, cnt, cnt_f)
    cnt_x = CARD_W - M - cnt_w
    d.text((cnt_x, Y_HEAD + P(2)), cnt, font=cnt_f, fill=BLACK)
    unit_f = font("bold", 13)
    unit_w = text_width(d, "件", unit_f)
    d.text((cnt_x - P(6) - unit_w, Y_HEAD + P(26)), "件", font=unit_f, fill=BLACK)
    # 日期能塞进标题右边就同行，塞不下就不画（不硬挤，避免越出色块）
    date_txt = f"{now.month:02d}/{now.day:02d} {WEEKDAYS[now.weekday()]}"
    date_f = font("light", 14)
    date_w = text_width(d, date_txt, date_f)
    title_w = text_width(d, title, title_f)
    room = cnt_x - P(18) - (M + title_w + P(12))
    if date_w <= room:
        d.text((M + title_w + P(12), Y_HEAD + P(12)), date_txt, font=date_f, fill=BLACK)

    # 书写行：按可用高度均分，剩余空白上下等分（视觉重心才落在正中）
    row_top = Y_RULE + P(22)
    colors = [RED, BLUE, GREEN, BLACK, RED, BLUE]
    avail_h = Y_FOOT_RULE - P(28) - row_top
    items = todos[:6]
    row_content = P(40)
    n = max(1, 6 if not items else len(items))
    row_h = max(P(48), min(P(96), avail_h // n))
    used = (n - 1) * row_h + row_content
    row_top += max(0, (avail_h - used) // 2)
    if not items:
        for i in range(6):
            y = row_top + i * row_h
            d.rectangle([M + P(8), y + P(4), M + P(30), y + P(26)], outline=BLACK, width=2)
            d.line([M + P(44), y + P(26), CARD_W - M, y + P(26)], fill=BLACK, width=1)
    else:
        for i, item in enumerate(items):
            y = row_top + i * row_h
            _circle(d, M + P(12), y + P(12), P(12), colors[i % len(colors)])
            d.text((M + P(4), y + P(4)), str(i + 1), font=font("num", 14), fill=WHITE)
            d.rectangle([M + P(36), y + P(2), M + P(56), y + P(22)], outline=BLACK, width=2)
            f = font("light", 24)
            lines = wrap(d, item, f, CARD_W - M * 2 - P(70))[:2]
            for j, ln in enumerate(lines):
                d.text((M + P(66), y + j * P(26)), ln, font=f, fill=BLACK)
            d.line([M + P(66), y + P(40), CARD_W - M, y + P(40)], fill=BLACK, width=1)
    if not items:
        d.text((M, Y_FOOT_RULE - P(44)), "在网页里添加今天要做的事",
               font=font("light", 17), fill=BLACK)

    _foot(d, "勾掉一件算一件", now.strftime("%H:%M"))
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

    # 先按"能把正文区填满"选字号：从大到小试，取放得下的最大字号，
    # 短句就撑成海报，长句自动收小；末行只剩一两个字时再降一档（避免孤字）
    top_limit = Y_RULE + P(16)
    body_h = Y_FOOT_RULE - P(16) - top_limit
    reserve = P(56)                     # 留给「— 出处」那行
    sizes = (88, 80, 72, 64, 58, 52, 46, 40, 36, 32, 28)
    f, lines, lh = font("bold", 30), [text], P(40)
    for idx, size in enumerate(sizes):
        f = font("bold", size)
        cand = wrap(d, text, f, GRID_W - P(36))
        cand_lh = int(size * _SCALE * 1.26)
        if len(cand) * cand_lh > body_h - reserve:
            continue
        # 孤字处理：末行太短就再试小一档
        if idx + 1 < len(sizes) and len(cand) > 1 and len(cand[-1]) <= 2:
            continue
        lines, lh = cand, cand_lh
        break
    block_h = len(lines) * lh
    y0 = top_limit + max(0, (body_h - reserve - block_h) // 2)

    d.rectangle([M, y0 + P(6), M + 6, y0 + block_h - P(8)], fill=RED)
    d.text((M - P(2), y0 - P(40)), "\u201c", font=font("num", 56), fill=RED)

    y = y0
    for ln in lines:
        d.text((M + P(24), y), ln, font=f, fill=BLACK)
        y += lh
    if q.get("from"):
        d.text((M + P(24), min(y + P(12), Y_FOOT_RULE - P(28))), f"— {q['from']}",
               font=font("light", 17), fill=BLACK)

    # 页脚：来源 + 日期（三个小圆点作为品牌色）
    for i, c in enumerate((RED, YELLOW, BLUE)):
        _circle(d, M + 8 + i * P(22), Y_FOOT_RULE - P(18), P(5), c)
    _rule(d, Y_FOOT_RULE)
    d.text((M, Y_FOOT), "hitokoto", font=font("light", FS["small"]), fill=BLACK)
    _right(d, now.strftime("%Y-%m-%d"), Y_FOOT, FS["small"], kind=None)
    return img


# ── 每日诗词 ──
def render_poem(ctx: dict):
    now: datetime = ctx["now"]
    p = ctx.get("poem") or {}
    note = (ctx.get("note") or "").strip()
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    title = p.get("title") or "今日诗词"
    # 标题先缩字号，缩到 22 还放不下就在「·」处断成两行
    title_lines = [title]
    tf = font("bold", FS["title"])
    while text_width(d, title, tf) > GRID_W - P(60) and tf.size > 22:
        tf = font("bold", tf.size - 2)
    if text_width(d, title, tf) > GRID_W - P(60) and "·" in title:
        a, b = title.split("·", 1)
        title_lines = [a.strip() + "·", b.strip()]
        tf = font("bold", 24)
        while any(text_width(d, t, tf) > GRID_W - P(60) for t in title_lines) and tf.size > 18:
            tf = font("bold", tf.size - 2)

    d.rectangle([M, Y_HEAD + 8, M + 13, Y_HEAD + 21], fill=RED)
    ty = Y_HEAD
    for tl in title_lines:
        d.text((M + 24, ty), tl, font=tf, fill=BLACK)
        ty += int(tf.size * 1.18)
    ay = ty + P(2)
    if p.get("author"):
        _right(d, f"{p.get('dynasty', '')} · {p['author']}".strip(" ·"), ay, FS["meta"],
               kind=None)
    ry = max(Y_RULE, ay + P(30))
    _rule(d, ry, w=3)
    d.line([M, ry, M + P(90), ry], fill=RED, width=6)

    raw_lines = p.get("lines") or ["今天也要好好生活。"]
    nf = font("light", 20)
    # 赏析文字避开右下角的印章区（印章宽 50 + 间距），否则会压到章上
    note_w = GRID_W - P(64)
    note_lines = wrap(d, note, nf, note_w)[:9] if note else []
    note_h = (len(note_lines) * P(32) + P(48)) if note_lines else 0

    avail_h = Y_FOOT_RULE - P(16) - (ry + P(24)) - note_h
    f, disp = font("bold", 32), []
    for size in (32, 28, 24, 21, 19):
        f = font("bold", size)
        lh = P(int(size * 1.6))
        disp = []
        for ln in raw_lines:
            disp += wrap(d, ln, f, GRID_W)
        if len(disp) * lh <= avail_h:
            break

    lh = P(int(f.size / max(0.01, _SCALE) * 1.6))
    total_h = len(disp) * lh
    y = ry + P(24) + max(0, min(P(56), (avail_h - total_h) // 3))
    for ln in disp:
        d.text((M, y), ln, font=f, fill=BLACK)
        y += lh

    if note_lines and y + P(40) < Y_FOOT_RULE - P(8):
        y += P(8)
        _rule(d, y, w=1)
        y += P(14)
        d.text((M, y), "赏析", font=font("bold", 17), fill=RED)
        y += P(28)
        for ln in note_lines:
            if y > Y_FOOT_RULE - P(20):
                break
            d.text((M, y), ln, font=nf, fill=BLACK)
            y += P(32)

    # 印章固定在右下角（赏析文字已让出这块），和页脚不打架
    seal = font("bold", 24)
    sx = CARD_W - M - P(50)
    sy = Y_FOOT_RULE - P(64)
    d.rectangle([sx, sy, sx + P(50), sy + P(50)], fill=RED)
    d.text((sx + (P(50) - text_width(d, "诗", seal)) / 2, sy + P(10)), "诗",
           font=seal, fill=WHITE)
    _foot(d, "每日诗词 · 土豆片", "", rule=False)
    return img


# ── 月历 ──
def render_calendar(ctx: dict):
    now: datetime = ctx["now"]
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, f"{now.year} 年 {now.month} 月", f"第 {now.timetuple().tm_yday} 天")
    _rule(d, Y_RULE)

    col_w = GRID_W / 7
    # 星期表头
    y = Y_RULE + P(24)
    for i, wd in enumerate(WEEKDAYS_SHORT):
        color = RED if i >= 5 else BLACK
        f = font("bold", 17)
        d.text((M + i * col_w + (col_w - text_width(d, wd, f)) / 2, y), wd, font=f, fill=color)
    y += P(32)

    weeks = _cal.Calendar(firstweekday=0).monthdayscalendar(now.year, now.month)
    # 网格吃掉可用高度：下方固定留出 本周条 + 页脚
    week_strip_h = P(76)
    grid_bottom = Y_FOOT_RULE - week_strip_h - P(16)
    cell_h = max(P(48), int((grid_bottom - y) / max(1, len(weeks))))
    for week in weeks:
        for i, day in enumerate(week):
            if day == 0:
                continue
            cx = M + i * col_w
            is_today = (day == now.day)
            color = WHITE if is_today else (RED if i >= 5 else BLACK)
            f = font("num", 26)
            txt = str(day)
            tw_ = text_width(d, txt, f)
            tx = cx + (col_w - tw_) / 2
            ty = y + max(P(2), (cell_h - int(26 * _SCALE)) // 2)
            if is_today:
                bb = d.textbbox((tx, ty), txt, font=f)
                ccx, ccy = (bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2
                rr = max(bb[2] - bb[0], bb[3] - bb[1]) / 2 + P(8)
                d.ellipse([ccx - rr, ccy - rr, ccx + rr, ccy + rr], fill=RED)
            d.text((tx, ty), txt, font=f, fill=color)
        y += cell_h

    # 本周 7 天条（贴着页脚上方，块高固定，不再"跟着 grid 漂"）
    wk_y = Y_FOOT_RULE - week_strip_h
    d.text((M, wk_y), "本周", font=font("bold", 17), fill=BLACK)
    start = now - timedelta(days=now.weekday())
    strip_y = wk_y + P(24)
    for i in range(7):
        day = start + timedelta(days=i)
        x = M + i * (GRID_W / 7)
        is_today = day.date() == now.date()
        col = WHITE if is_today else (RED if i >= 5 else BLACK)
        if is_today:
            d.rectangle([x + 2, strip_y, x + P(48), strip_y + P(44)], fill=RED)
        d.text((x + P(6), strip_y + P(4)), WEEKDAYS_SHORT[i], font=font("bold", 13), fill=col)
        d.text((x + P(6), strip_y + P(22)), str(day.day), font=font("num", 17), fill=col)

    days_left = _cal.monthrange(now.year, now.month)[1] - now.day
    _rule(d, Y_FOOT_RULE)
    d.text((M, Y_FOOT), "本月还剩", font=font("bold", 17), fill=BLACK)
    _num(d, M + P(96), Y_FOOT - P(4), str(days_left), 24, BLACK)
    d.text((M + P(96) + text_width(d, str(days_left), font("num", 24)) + P(6), Y_FOOT),
           "天", font=font("bold", 16), fill=BLACK)
    _right(d, "土豆片 · 月历", Y_FOOT, FS["small"], kind=None)
    return img


# ── 倒计时 ──
def render_countdown(ctx: dict):
    now: datetime = ctx["now"]
    items = ctx.get("countdowns") or []
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, "倒计时", f"{now.month:02d}/{now.day:02d}")
    _rule(d, Y_RULE)

    def _key(it):
        try:
            target = datetime.strptime(it.get("date", ""), "%Y-%m-%d").date()
            return (0, (target - now.date()).days) if target >= now.date() \
                else (1, -(target - now.date()).days)
        except Exception:
            return (2, 0)

    colors = [RED, BLUE, GREEN, BLACK, RED]
    rows = sorted(items, key=_key)[:5]
    avail = Y_FOOT_RULE - Y_BODY - P(8)
    if not rows:
        d.text((M, Y_BODY), "还没有纪念日", font=font("bold", 22), fill=BLACK)
        d.text((M, Y_BODY + P(38)), "在网页里写：2027-01-01 元旦",
               font=font("light", FS["body"]), fill=BLACK)
        yy = Y_BODY + P(90)
        for _ in range(3):
            d.line([M, yy + P(30), CARD_W - M, yy + P(30)], fill=BLACK, width=1)
            d.text((M, yy + P(44)), "— —", font=font("num", 22), fill=BLACK)
            yy += P(96)
        return img

    # 条目按可用高度均分（平均分布，而不是从顶部往下堆）
    block_h = min(P(120), int(avail / max(1, len(rows))))
    pad = (avail - block_h * len(rows)) // max(1, len(rows) + 1)
    y = Y_BODY + pad
    for i, it in enumerate(rows):
        try:
            target = datetime.strptime(it.get("date", ""), "%Y-%m-%d").date()
        except Exception:
            continue
        days = (target - now.date()).days
        big, note = (str(days), "天后") if days >= 0 else (str(-days), "天前")
        d.text((M, y), it.get("name") or "纪念日", font=font("bold", 23), fill=BLACK)
        _right(d, it.get("date", ""), y + P(6), FS["small"], kind=None)
        y += P(36)
        f = font("num", 56)
        d.text((M, y), big, font=f, fill=colors[i % len(colors)])
        d.text((M + text_width(d, big, f) + P(10), y + P(22)), note,
               font=font("bold", 18), fill=BLACK)
        y += P(68)
        if i < len(rows) - 1:
            _rule(d, y, w=1)
        y += pad
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
    _num(d, M, Y_HERO, f"{total:.0f}", 84, BLACK, "Wh", 28, BLACK)
    sub_y = Y_HERO + int(84 * _SCALE) + P(8)
    d.text((M, sub_y), f"{now.month} 月 {now.day} 日 · 昨天的充电量",
           font=font("light", 17), fill=BLACK)

    colors = [YELLOW, RED, BLUE, GREEN]
    peak = max([x["wh"] for x in stats] or [1])

    # 今天累计块：先按行数算出块高，贴着页脚上方放
    today_shown = today[:3]
    row_pitch = P(26)
    block_h = P(28) + row_pitch * max(1, len(today_shown)) + P(14)
    block_top = Y_FOOT_RULE - P(10) - block_h
    t_total = sum(x["wh"] for x in today)

    # 昨天各口：在 正文顶 ~ 今天累计块 之间按可用高度分配
    y = Y_BODY
    d.text((M, y), "各口用电", font=font("bold", FS["h2"]), fill=BLACK)
    _rule(d, y + P(28), x0=M + P(84), w=2)
    y += P(44)
    room = block_top - P(16) - y
    item_h = P(64)
    max_ports = max(0, int(room / item_h)) if stats else 0
    shown = stats[: min(4, max_ports)]
    if not shown:
        d.text((M, y), "昨天没有充电记录", font=font("light", FS["body"]), fill=BLACK)
        y += P(36)
    else:
        # 条目少时把行距均分，别把内容挤在顶部
        pitch = min(item_h, max(P(48), int(room / len(shown))))
        for i, st in enumerate(shown):
            yy = y + i * pitch
            port_f = font("bold", 18)
            d.text((M, yy), st["port"], font=port_f, fill=BLACK)
            val_x = M + max(P(56), text_width(d, st["port"], port_f) + P(10))
            d.text((val_x, yy), f"{st['wh']:.1f} Wh", font=font("num", 18), fill=BLACK)
            _right(d, f"峰值 {st['peak']} W", yy + P(2), FS["tiny"], kind=None)
            _bar(d, M, yy + P(26), GRID_W, min(P(16), max(P(10), int(pitch * 0.26))),
                 st["wh"] / peak, colors[i % 4])

    # ── 今天累计（固定块，块内自上而下）──
    ty = block_top
    _rule(d, ty, w=2)
    ty += P(12)
    d.text((M, ty), "今天累计", font=font("bold", 17), fill=BLACK)
    _right(d, f"{t_total:.1f} Wh", ty - P(2), 22, kind="num")
    ty += P(28)
    if today_shown:
        for st in today_shown:
            d.text((M, ty), st["port"], font=font("bold", 18), fill=BLACK)
            d.text((M + P(50), ty), f"{st['wh']:.1f} Wh", font=font("num", 16), fill=BLACK)
            d.text((M + P(150), ty + P(2)), f"{st['count']} 次",
                   font=font("light", 15), fill=BLACK)
            ty += row_pitch
    else:
        d.text((M, ty), "今天还没有充电", font=font("light", 18), fill=BLACK)

    _foot(d, "数据来自本机 port_history.db", "", rule=True)
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
    r = s // 3
    _circle(d, x + s * 0.4, y + s * 0.6, r * 0.7, color)
    _circle(d, x + s * 0.6, y + s * 0.45, r * 0.8, color)
    _circle(d, x + s * 0.8, y + s * 0.62, r * 0.6, color)


def _icon_bolt(d, x, y, s, color=YELLOW):
    d.polygon([(x + s * 0.55, y), (x + s * 0.25, y + s * 0.55),
               (x + s * 0.45, y + s * 0.55), (x + s * 0.35, y + s),
               (x + s * 0.75, y + s * 0.4), (x + s * 0.55, y + s * 0.4)], fill=color)


def _icon_heart(d, x, y, s, color=RED):
    _circle(d, x + s * 0.35, y + s * 0.4, s * 0.2, color)
    _circle(d, x + s * 0.65, y + s * 0.4, s * 0.2, color)
    d.polygon([(x + s * 0.15, y + s * 0.45), (x + s * 0.85, y + s * 0.45),
               (x + s * 0.5, y + s * 0.9)], fill=color)


def _icon_cup(d, x, y, s, color=RED):
    d.rectangle([x + s * 0.2, y + s * 0.3, x + s * 0.7, y + s * 0.9], fill=color)
    d.arc([x + s * 0.65, y + s * 0.4, x + s * 0.95, y + s * 0.7], 270, 90, fill=color, width=3)


def _icon_book(d, x, y, s, color=BLUE):
    d.rectangle([x + s * 0.15, y + s * 0.2, x + s * 0.85, y + s * 0.85], fill=color)
    d.line([x + s * 0.5, y + s * 0.2, x + s * 0.5, y + s * 0.85], fill=WHITE, width=3)


def _icon_leaf(d, x, y, s, color=GREEN):
    d.pieslice([x + s * 0.2, y + s * 0.2, x + s * 0.8, y + s * 0.8], 90, 270, fill=color)


def _icon_smiley(d, x, y, s, color=YELLOW):
    _circle(d, x + s / 2, y + s / 2, s * 0.35, color)
    _circle(d, x + s * 0.38, y + s * 0.42, s * 0.06, BLACK)
    _circle(d, x + s * 0.62, y + s * 0.42, s * 0.06, BLACK)
    d.arc([x + s * 0.3, y + s * 0.35, x + s * 0.7, y + s * 0.7], 20, 160, fill=BLACK, width=3)


_ICONS = {"sun": _icon_sun, "cloud": _icon_cloud, "bolt": _icon_bolt, "heart": _icon_heart,
          "cup": _icon_cup, "book": _icon_book, "leaf": _icon_leaf, "smiley": _icon_smiley}


def draw_icon(d, name, x, y, size):
    fn = _ICONS.get(name)
    if fn:
        fn(d, x, y, size)


def icon_row(d, y, names, size=34, gap=10, x=None):
    x = M if x is None else x
    for n in names:
        draw_icon(d, n, x, y, size)
        x += size + gap


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
        # 整图 + 底部信息带：带子里文字严格走页脚槽位
        from PIL import Image
        band = Y_FOOT_RULE - Y_BODY + P(8)     # 信息带高度
        src = Image.open(img_path).convert("RGB")
        w0, h0 = src.size
        src = src.crop((0, 0, w0, int(h0 * 0.96)))       # 去掉出图服务角标
        img.paste(_cover(src, CARD_W, CARD_H - band), (0, 0))
        d.rectangle([0, CARD_H - band, CARD_W, CARD_H], fill=WHITE)
        d.line([0, CARD_H - band, CARD_W, CARD_H - band], fill=BLACK, width=3)
        lines = wrap(d, prompt, font("light", 17), GRID_W, word_wrap=True)[:2]
        yy = CARD_H - band + P(18)
        for ln in lines:
            d.text((M, yy), ln, font=font("light", 17), fill=BLACK)
            yy += P(Y_FOOT_LINE)
        _right(d, "AI 生图 · 土豆片", CARD_H - P(28), FS["small"], kind=None)
        # 照片区继续抖动，底部信息带吸附成 6 色（否则带子里会是彩色噪点）
        return snap_palette(img, keep=[(0, 0, CARD_W, CARD_H - band)])

    # 还没生成：一张干净的"待生成"海报
    _head(d, "AI 生图", now.strftime("%m/%d"))
    d.text((M, Y_HERO), "还没有生成图片", font=font("bold", 30), fill=BLACK)
    hint_y = Y_HERO + P(48)
    for ln in wrap(d, "在控制台点「生成一张」，或在提示词里写你想要的画面。",
                   font("light", FS["small"]), GRID_W)[:2]:
        d.text((M, hint_y), ln, font=font("light", FS["small"]), fill=BLACK)
        hint_y += P(Y_FOOT_LINE)

    box = [M, Y_BODY, CARD_W - M, Y_BODY + P(220)]
    d.rectangle(box, outline=BLACK, width=3)
    d.text((M + P(18), Y_BODY + P(18)), "提示词", font=font("bold", FS["tiny"]), fill=BLACK)
    yy = Y_BODY + P(52)
    for ln in wrap(d, prompt or "a quiet desk at night, warm lamp, cinematic",
                   font("light", 18), GRID_W - P(36))[:6]:
        d.text((M + P(18), yy), ln, font=font("light", 18), fill=BLACK)
        yy += P(28)
    _foot(d, "生成后这里会变成整张图")
    return snap_palette(img)


# ─────────────────────────── 自定义卡片（积木式） ───────────────────────────
ACCENTS = {"red": RED, "blue": BLUE, "green": GREEN, "black": BLACK, "yellow": YELLOW}


def _draw_block(d, img, blk, ctx, y, width, accent, photo_rects=None):
    """画一个积木，返回它占的高度（高度按 P() 缩放，块之间不会互相压）。

    photo_rects: 图片块的位置会追加到这里，出图时这些区域保留原样（继续抖动）。
    """
    t = blk.get("type")
    align = blk.get("align") or "left"
    x = M
    w = width
    if t == "title":
        text = blk.get("text") or ctx.get("title") or "土豆片"
        f = font("bold", 30)
        d.rectangle([x, y + P(2), x + 10, y + P(30)], fill=accent)
        d.text((x + P(20), y), text, font=f, fill=BLACK)
        return P(44)
    if t == "bigdate":
        now = ctx["now"]
        f = font("num", 84)
        txt = f"{now.day:02d}"
        d.text((x, y), txt, font=f, fill=accent)
        wd = text_width(d, txt, f)
        d.text((x + wd + P(8), y + P(40)), f"{now.month}月", font=font("bold", 20), fill=BLACK)
        d.text((x + wd + P(8), y + P(68)), WEEKDAYS[now.weekday()],
               font=font("light", 18), fill=BLACK)
        return int(84 * _SCALE) + P(10)
    if t == "weather":
        wdata = ctx.get("weather") or {}
        if wdata.get("temp") is None:
            return 0
        f = font("num", 52)
        s = f"{wdata['temp']:.0f}°"
        d.text((x, y), s, font=f, fill=BLACK)
        ww = text_width(d, s, f)
        d.text((x + ww + P(10), y + P(8)), wdata.get("text", ""),
               font=font("bold", 20), fill=accent)
        d.text((x + ww + P(10), y + P(36)),
               f"{wdata.get('city','')} {round(wdata.get('lo',0))}~{round(wdata.get('hi',0))}°",
               font=font("light", 17), fill=BLACK)
        return int(52 * _SCALE) + P(14)
    if t == "todos":
        todos = [x_ for x_ in (ctx.get("todos") or []) if x_][:5]
        yy = y
        if not todos:
            d.text((x, yy), "今天还没有安排", font=font("light", 18), fill=BLACK)
            return P(32)
        for item in todos:
            _circle(d, x + P(10), yy + P(12), P(10), accent)
            f = font("light", 21)
            lines = wrap(d, item, f, w - P(34))[:1]
            d.text((x + P(30), yy), lines[0] if lines else item, font=f, fill=BLACK)
            yy += P(34)
        return yy - y
    if t == "quote":
        q = ctx.get("quote") or {}
        f = font("light", 20)
        lines = wrap(d, f"“{q.get('text','')}”", f, w)[:2]
        yy = y
        for ln in lines:
            d.text((x, yy), ln, font=f, fill=BLACK)
            yy += P(36)
        if q.get("from"):
            d.text((x, yy), f"— {q['from']}", font=font("light", 13), fill=accent)
            yy += P(20)
        return yy - y + P(4)
    if t == "dots":
        now = ctx["now"]
        passed = (now - datetime(now.year, 1, 1)).days + 1
        total = 365 + (1 if now.year % 4 == 0 else 0)
        n = 52
        filled = int(n * passed / total)
        inset = P(5)
        gap = (w - 2 * inset) / (n - 1)
        for i in range(n):
            _circle(d, x + inset + i * gap, y + P(8), P(3.2),
                    accent if i < filled else BLACK, outline=i >= filled, width=1)
        d.text((x, y + P(24)), f"已过 {passed}/{total} 天", font=font("light", 15), fill=BLACK)
        return P(40)
    if t == "text":
        f = font(blk.get("weight") or "light", int(blk.get("size") or 20))
        txt = blk.get("text") or ""
        lines = wrap(d, txt, f, w)[:4]
        yy = y
        lh = int(f.size * 1.35)
        for ln in lines:
            if align == "center":
                d.text((x + (w - text_width(d, ln, f)) / 2, yy), ln, font=f, fill=BLACK)
            else:
                d.text((x, yy), ln, font=f, fill=BLACK)
            yy += lh
        return yy - y + P(4)
    if t == "rule":
        _rule(d, y + P(6))
        return P(18)
    if t == "image":
        src = ctx.get("image_path")
        h = int(blk.get("height") or 260)
        if photo_rects is not None:
            photo_rects.append((x, y, x + w, y + h))
        if not src:
            d.rectangle([x, y, x + w, y + h], outline=BLACK, width=1)
            d.text((x + P(16), y + P(16)), "（没有可用图片）",
                   font=font("light", 18), fill=BLACK)
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
    """按用户给的 blocks 顺序渲染。积木高度用 P() 累加，块间距固定 16，绝不重叠。"""
    cfg = ctx.get("custom") or {}
    blocks = [b for b in (cfg.get("blocks") or []) if b.get("on") is not False]
    accent = ACCENTS.get(cfg.get("accent") or "red", RED)
    img = new_canvas()
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    _head(d, cfg.get("title") or "我的卡片", ctx["now"].strftime("%m/%d"))

    # 先量高度再画：放不下就整体降字号（最低 0.85），不做画完再挪
    backup = _scale_backup()
    gap = P(16)
    width = GRID_W
    top = Y_HERO + P(4)          # 页眉已经有标题了，积木从主视觉位开始堆
    room = Y_FOOT_RULE - top - P(8)
    for _ in range(3):
        est = 0
        for blk in blocks:
            est += _block_height(d, blk, ctx, width) + gap
        if est <= room or est <= 0 or _SCALE <= 0.86:
            break
        _fit(est, room)

    y = top
    photo_rects: list[tuple[int, int, int, int]] = []
    bottom_limit = Y_FOOT_RULE - P(16)
    for blk in blocks:
        h_est = _block_height(d, blk, ctx, width)
        if h_est <= 0:
            continue
        # 放不下就整块不画（宁可少一个积木，也不要压到页脚线/出安全区）
        if y + h_est > bottom_limit:
            break
        h = _draw_block(d, img, blk, ctx, y, width, accent, photo_rects)
        if h <= 0:
            continue
        y += h + gap
    _foot(d, "自定义 · 土豆片")
    _restore_scale(backup)
    return snap_palette(img, keep=photo_rects)


def _block_height(d, blk, ctx, width) -> int:
    """估算积木高度（只量不画），用于整体 auto-fit。"""
    t = blk.get("type")
    if t == "title":
        return P(44)
    if t == "bigdate":
        return int(84 * _SCALE) + P(10)
    if t == "weather":
        return 0 if (ctx.get("weather") or {}).get("temp") is None else int(52 * _SCALE) + P(14)
    if t == "todos":
        n = len([x for x in (ctx.get("todos") or []) if x][:5]) or 1
        return P(32) if n == 1 else n * P(34)
    if t == "quote":
        q = ctx.get("quote") or {}
        f = font("light", 20)
        n = len(wrap(d, f"“{q.get('text','')}”", f, width)[:2])
        return n * P(36) + (P(20) if q.get("from") else 0) + P(4)
    if t == "dots":
        return P(40)
    if t == "text":
        f = font(blk.get("weight") or "light", int(blk.get("size") or 20))
        n = len(wrap(d, blk.get("text") or "", f, width)[:4])
        return n * int(f.size * 1.35) + P(4)
    if t == "rule":
        return P(18)
    if t == "image":
        return int(blk.get("height") or 260)
    return 0


# ─────────────────────────── 兼容旧接口 ───────────────────────────
def fit_content(img, margin: int = 18, max_scale: float = 1.6, min_scale: float = 1.0):
    """已废弃：旧版用来"画完再整体放大"，会破坏对齐。保留空实现兼容调用方。"""
    return img


def distribute_vertical(img, margin: int = 20, max_gap: int = 64, merge: int = 30):
    """已废弃：旧版用来"画完再把间距摊开"，会破坏版式节奏。保留空实现兼容调用方。"""
    return img


# 纯文字/图形模板统一吸附到 6 色（照片类模板 render_ai / render_custom 内部自己处理保留区）
for _name in ("render_summary", "render_weather", "render_weather_blocks", "render_progress",
              "render_todo", "render_quote", "render_poem", "render_calendar",
              "render_countdown", "render_energy"):
    globals()[_name] = _snapped(globals()[_name])
del _name


# ─────────────────────────── 主题：流浪地球（工业 HUD） ───────────────────────────
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
    return RED


def tc_second():
    return YELLOW


def hud_frame(img):
    """流浪地球式边框：四角刻度 + 顶部编号 + 细网格。只在 wo 主题下调用。"""
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    m = 10
    d.rectangle([m, m, CARD_W - m, CARD_H - m], outline=WHITE, width=2)
    L = 34
    for (x, y, dx, dy) in ((m, m, 1, 1), (CARD_W - m, m, -1, 1),
                           (m, CARD_H - m, 1, -1), (CARD_W - m, CARD_H - m, -1, -1)):
        d.line([x, y, x + dx * L, y], fill=YELLOW, width=4)
        d.line([x, y, x, y + dy * L], fill=YELLOW, width=4)
    for i in range(1, 4):
        yy = m + i * (CARD_H - 2 * m) / 4
        for xx in range(m + 40, CARD_W - m - 40, 60):
            d.line([xx, yy, xx + 18, yy], fill=WHITE, width=1)
    return img


def hud_header(d, code: str, title: str, right: str = ""):
    d.text((M, 18), code, font=font("num", 12), fill=YELLOW)
    d.text((M, 34), title, font=font("bold", 28), fill=WHITE)
    if right:
        w = text_width(d, right, font("num", 14))
        d.text((CARD_W - M - w, 20), right, font=font("num", 14), fill=WHITE)
    d.line([M, 82, CARD_W - M, 82], fill=WHITE, width=3)
    d.line([M, 74, M + 90, 74], fill=RED, width=6)


def to_dark_hud(img):
    """把浅色卡整体转成「流浪地球」工业风：白底→黑底、黑字→白字、彩色保持。"""
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            mx, mn = max(r, g, b), min(r, g, b)
            if mx - mn > 60:
                continue
            px[x, y] = (0, 0, 0) if mx > 140 else (255, 255, 255)
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
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    m = 10
    d.rectangle([m, m, w - m, h - m], outline=WHITE, width=2)
    L = 36
    for (x, y, dx, dy) in ((m, m, 1, 1), (w - m, m, -1, 1),
                           (m, h - m, 1, -1), (w - m, h - m, -1, -1)):
        d.line([x, y, x + dx * L, y], fill=YELLOW, width=4)
        d.line([x, y, x, y + dy * L], fill=YELLOW, width=4)
    return img


def _cover(img, w: int, h: int):
    """等比缩放到铺满 w×h 后居中裁切。"""
    from PIL import Image
    sw, sh = img.size
    factor = max(w / sw, h / sh)
    nw, nh = max(1, int(sw * factor + 0.5)), max(1, int(sh * factor + 0.5))
    img = img.resize((nw, nh), Image.LANCZOS)
    left, top = (nw - w) // 2, (nh - h) // 2
    return img.crop((left, top, left + w, top + h))
