"""Render portable schemaVersion 1 layout packs using the local card data."""
import calendar
import json
import math
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import date
from io import BytesIO

from PIL import Image, ImageDraw

import card_render as R

LIMIT = 256 * 1024
KINDS = set("summary quote todo progress calendar weather weather2 countdown poem custom".split())
SOURCES = {"weather", "quote", "poem"}
COLORS = {k: getattr(R, k.upper()) for k in ("black", "white", "red", "blue", "green", "yellow")}
FIELDS = set(("weatherCity weatherText weatherTemp weatherHigh weatherLow weatherAqi weatherHumidity "
              "weatherFeels weatherSunrise weatherSunset weatherUpdated wearAdvice date time week year "
              "month day yearPercent quote quoteBy poemTitle poemBy poemText poemNote customTitle customText todoCount").split())
LISTS = {"todos", "poemLines", "customLines", "weatherForecast", "countdowns"}
BLOCKS = set("title bigdate weather todos quote poem dots countdown text qr rule".split())
BIND = re.compile(r"\{\{([A-Za-z][A-Za-z0-9]*)\}\}")
STYLE = "textSize color bold align lineHeight"
OPTIONS = {
    "rect": "fill border borderWidth radius", "text": STYLE + " text wrap shrink",
    "list": STYLE + " source numbered emptyText", "weatherIcon": "", "weatherHours": "color",
    "forecastBars": "color", "block": "blockType text textSize centered color", "builtin": "kind",
}
BOUNDS = {"textSize": (10, 160), "lineHeight": (1, 2.5), "borderWidth": (0, 20), "radius": (0, 80)}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def keys(obj, allowed):
    require(isinstance(obj, dict) and obj.keys() <= set(allowed.split()), "版式含不支持的字段")


def geometry(node):
    x, y = node.get("x", 0), node.get("y", 0)
    return x, y, node.get("width", 528 - x), node.get("height", 792 - y)


def parse(raw):
    require(len(raw) <= LIMIT, "版式包不能超过 256 KB")
    pack = json.loads(raw.decode("utf-8-sig"))
    keys(pack, "schemaVersion name templates")
    require(type(pack.get("schemaVersion")) in (int, float) and pack["schemaVersion"] == 1, "仅支持 schemaVersion 1")
    label(pack, "name")
    templates = pack.get("templates")
    require(isinstance(templates, dict) and templates and templates.keys() <= KINDS, "卡片类型无效")
    for template in templates.values():
        keys(template, "name sources nodes")
        label(template, "name")
        sources = template.get("sources", [])
        require(isinstance(sources, list) and all(isinstance(s, str) and s in SOURCES for s in sources), "数据来源无效")
        nodes = template.get("nodes")
        require(isinstance(nodes, list) and 1 <= len(nodes) <= 96, "每张卡片需要 1～96 个元素")
        for node in nodes:
            validate_node(node)
    return pack


def label(obj, key):
    value = obj.get(key, "")
    require(isinstance(value, str) and len(value) <= 4096, f"{key} 文字无效")
    return value


def validate_node(node):
    kind = node.get("type") if isinstance(node, dict) else None
    require(isinstance(kind, str) and kind in OPTIONS, "元素类型无效")
    keys(node, "type x y width height showIf " + OPTIONS[kind])
    for key, (lo, hi) in {**BOUNDS, "x": (0, 527), "y": (0, 791), "width": (1, 528), "height": (1, 792)}.items():
        if key in node:
            value = node[key]
            require(type(value) in (int, float) and lo <= value <= hi, f"{key} 数值越界")
    x, y, w, h = geometry(node)
    require(w >= 1 and h >= 1 and x + w <= 528.01 and y + h <= 792.01, "元素超出 528×792 画布")
    for key in {"fill", "border", "color"} & node.keys():
        require(label(node, key) in {*COLORS, "aqiColor", "aqiInk"}, "请使用六色名称")
    for key in {"bold", "wrap", "shrink", "numbered", "centered"} & node.keys():
        require(isinstance(node[key], bool), f"{key} 必须为布尔值")
    for key, allowed in {"align": {"left", "center", "right"}, "showIf": FIELDS | {""}}.items():
        if key in node:
            require(label(node, key) in allowed, f"{key} 无效")
    for key in ("text", "emptyText"):
        value = label(node, key)
        require(set(BIND.findall(value)) <= FIELDS and "{{" not in BIND.sub("", value), "内容绑定字段无效")
    if kind in ("list", "block", "builtin"):
        key, choices = {"list": ("source", LISTS), "block": ("blockType", BLOCKS), "builtin": ("kind", KINDS)}[kind]
        require(label(node, key) in choices, f"{key} 无效")


def sources(template):
    result = set(template.get("sources", []))
    for n in template["nodes"]:
        used = BIND.findall(n.get("text", "") + n.get("emptyText", "")) + [n.get("showIf", "")]
        kind = n.get("kind") or n.get("blockType")
        if n["type"] in ("weatherIcon", "weatherHours", "forecastBars") or kind in ("summary", "weather", "weather2", "custom") or n.get("source") == "weatherForecast" or any(v.startswith("weather") or v == "wearAdvice" for v in used):
            result.add("weather")
        if kind in ("summary", "quote", "custom") or any(v.startswith("quote") for v in used):
            result.add("quote")
        if kind == "poem" or n.get("source") == "poemLines" or any(v.startswith("poem") for v in used):
            result.add("poem")
    return result


def github_url(link):
    u = urllib.parse.urlsplit(link.strip())
    require(u.scheme == "https" and u.netloc in ("github.com", "raw.githubusercontent.com"), "请输入公开 GitHub HTTPS 文件链接")
    parts = u.path.strip("/").split("/")
    if u.hostname == "raw.githubusercontent.com" and len(parts) >= 4:
        return "https://raw.githubusercontent.com" + u.path
    if len(parts) >= 5 and parts[2] in ("blob", "raw"):
        return "https://raw.githubusercontent.com/" + "/".join(parts[:2] + parts[3:])
    if len(parts) >= 6 and parts[2:4] == ["releases", "download"] or len(parts) >= 6 and parts[2:5] == ["releases", "latest", "download"]:
        return "https://github.com" + u.path
    raise ValueError("请使用 GitHub 文件或 Release 附件下载链接")


class GitHubRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        require(parsed.scheme == "https" and parsed.netloc in {
            "github.com", "raw.githubusercontent.com", "api.github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com", "github-releases.githubusercontent.com",
        }, "GitHub 下载重定向地址不受支持")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(link):
    url = github_url(link)
    opener = urllib.request.build_opener(GitHubRedirect())
    def read(address, accept):
        req = urllib.request.Request(address, headers={"Accept": accept, "Cache-Control": "no-cache",
                                                        "User-Agent": "TodooCard-Layouts"})
        with opener.open(req, timeout=10) as response:
            return response.read(LIMIT + 1)
    try:
        raw = read(url, "application/json, text/plain")
    except (OSError, urllib.error.URLError):
        p = urllib.parse.urlsplit(url)
        if p.hostname != "raw.githubusercontent.com":
            raise
        owner, repo, ref, *path = p.path.strip("/").split("/")
        if ref == "refs" and len(path) >= 3 and path[0] in ("heads", "tags"):
            ref, path = path[1], path[2:]
        api = f"https://api.github.com/repos/{owner}/{repo}/contents/" + "/".join(path)
        raw = read(api + "?ref=" + urllib.parse.quote(urllib.parse.unquote(ref), safe=""), "application/vnd.github.raw+json")
    return parse(raw)


def content(ctx):
    now = ctx["now"]
    weather = ctx.get("weather") or {}
    quote = ctx.get("quote") or {}
    poem = ctx.get("poem") or {}
    custom = ctx.get("custom") or {}
    todos = ctx.get("todos") or []
    def number(key):
        value = weather.get(key)
        return str(round(value)) if isinstance(value, (float, int)) else "--"
    temp = weather.get("temp")
    advice = ("选择城市后显示" if temp is None else "厚外套，注意保暖" if temp < 10
              else "外套加长袖" if temp < 18 else "长袖或薄外套" if temp < 26 else "轻薄短袖，注意补水")
    values = {
        "weatherCity": weather.get("city", ""), "weatherText": weather.get("text", ""),
        "weatherTemp": number("temp"), "weatherHigh": number("hi"), "weatherLow": number("lo"),
        "weatherAqi": number("aqi"), "weatherHumidity": number("hum"), "weatherFeels": number("feels"),
        "weatherSunrise": weather.get("sunrise") or "--:--", "weatherSunset": weather.get("sunset") or "--:--",
        "weatherUpdated": weather.get("clock", "未更新"), "wearAdvice": advice,
        "date": now.date().isoformat(), "time": now.strftime("%H:%M"), "week": R.WEEKDAYS[now.weekday()],
        "year": now.year, "month": now.month, "day": now.day,
        "yearPercent": now.timetuple().tm_yday * 100 // (365 + calendar.isleap(now.year)),
        "quote": quote.get("text", ""), "quoteBy": quote.get("from", ""), "poemTitle": poem.get("title", ""),
        "poemBy": " · ".join(filter(None, (poem.get("dynasty"), poem.get("author")))),
        "poemText": "\n".join(poem.get("lines") or []), "poemNote": ctx.get("note", ""),
        "customTitle": custom.get("title", ""), "customText": "\n".join(custom.get("lines") or []),
        "todoCount": len(todos),
    }
    countdowns = []
    for item in ctx.get("countdowns", []):
        try:
            days = (date.fromisoformat(item["date"]) - now.date()).days
            countdowns.append(f"{item['name']}  {days} 天")
        except (KeyError, TypeError, ValueError):
            continue
    forecast = weather.get("daily3") or []
    lists = {"todos": todos, "poemLines": poem.get("lines") or [], "customLines": custom.get("lines") or [],
             "weatherForecast": [f"{d['label']} {d['text']}  {d['lo']}~{d['hi']}°" for d in forecast], "countdowns": countdowns}
    return values, lists


def bind(text, values):
    return BIND.sub(lambda m: str(values.get(m[1], ""))[:4000], text)[:12000]


def color(name, weather):
    aqi = weather.get("aqi")
    if name == "aqiInk":
        return R.WHITE if aqi is not None and aqi > 100 else R.BLACK
    if name == "aqiColor":
        return R.WHITE if aqi is None else R.GREEN if aqi <= 50 else R.YELLOW if aqi <= 100 else R.RED
    return COLORS[name]


def write_text(d, node, text, w, h, ink, scale, spacing):
    size = max(10, round(node.get("textSize", 24) * scale))
    while True:
        font = R.font_px("bold" if node.get("bold") else "light", size)
        bounds = font.getbbox("国Ag")
        glyph = bounds[3] - min(bounds[1], 0)
        step = max(glyph, size * node.get("lineHeight", 1.4) * spacing)
        rows = []
        for paragraph in text.split("\n"):
            rows.extend(R.wrap(d, paragraph, font, w, word_wrap=False) or [""] if node.get("wrap", True) else [paragraph])
        if (not node.get("shrink", True) or size <= 10 or
                (glyph + max(0, len(rows) - 1) * step <= h and all(font.getlength(s) <= w for s in rows))):
            break
        size -= 1
    count = max(0, math.floor((h - glyph) / step) + 1)
    for i, row in enumerate(rows[:count]):
        if font.getlength(row) > w or i == count - 1 and len(rows) > count:
            while row and font.getlength(row + "…") > w:
                row = row[:-1]
            row += "…"
        spare = w - font.getlength(row)
        x = {"left": 0, "center": spare / 2, "right": spare}[node.get("align", "left")]
        d.text((x, i * step), row, font=font, fill=ink)


def weather_graph(d, kind, weather, w, h, ink):
    font = R.font_px("light", max(8, min(19, round(h / (5 if kind == "weatherHours" else 4.6)))))
    if kind == "weatherHours":
        hours = [v for v in weather.get("hourly", []) if v.get("temp") is not None][:6]
        if len(hours) > 1:
            lo, hi = min(v["temp"] for v in hours) - 2, max(v["temp"] for v in hours) + 2
            pad = w * .065
            pts = [(pad + i * (w - 2 * pad) / (len(hours) - 1),
                    h * (.74 - .35 * (v["temp"] - lo) / (hi - lo))) for i, v in enumerate(hours)]
            d.text((0, 0), "未来时段 · 温度趋势", font=font, fill=R.BLACK)
            d.line(pts, fill=ink, width=3)
            for (x, y), v in zip(pts, hours):
                d.ellipse((x-3, y-3, x+3, y+3), fill=R.RED)
                d.text((x, y-font.size), f"{v['temp']}°", fill=R.BLACK, font=font, anchor="mb")
                d.text((x, h), v["t"], fill=R.BLACK, font=font, anchor="mb")
            return
    else:
        days = [v for v in weather.get("daily3", []) if v.get("lo") is not None and v.get("hi") is not None][:3]
        if days:
            lo, hi = min(v["lo"] for v in days), max(v["hi"] for v in days)
            span = max(1, hi - lo)
            for i, v in enumerate(days):
                y = h * (i + .5) / 3
                a, b = [w * (.42 + .30 * (v[k] - lo) / span) for k in ("lo", "hi")]
                label = f"{v['label']} {v['text']}"
                while label and font.getlength(label) > w * .37:
                    label = label[:-1]
                d.text((0, y), label, font=font, fill=R.BLACK, anchor="lm")
                d.rounded_rectangle((a, y-4, max(a+4, b), y+4), radius=3, fill=ink)
                d.text((w*.77, y), f"{v['lo']}~{v['hi']}°", font=font, fill=R.BLACK, anchor="lm")
            return
    d.text((0, max(0, h/3)), "刷新天气后显示", font=font, fill=R.BLACK)


NATIVE = {k: getattr(R, "render_" + ("weather_blocks" if k == "weather2" else k)) for k in KINDS}


def render(template, ctx, scale=1, spacing=1):
    image = Image.new("RGB", (528, 792), R.WHITE)
    values, lists = content(ctx)
    weather = ctx.get("weather") or {}
    for n in template["nodes"]:
        if n.get("showIf") and not values[n["showIf"]]:
            continue
        x, y, width, height = geometry(n)
        x, y = round(x), round(y)
        w, h = max(1, round(width)), max(1, round(height))
        layer = Image.new("RGBA", (w, h))
        d = ImageDraw.Draw(layer)
        kind = n["type"]
        ink = color(n.get("color", "blue" if kind in ("forecastBars", "weatherHours") else "black"), weather)
        if kind == "rect":
            d.rounded_rectangle((0, 0, w-1, h-1), radius=n.get("radius", 0), fill=color(n.get("fill", "white"), weather),
                                outline=color(n["border"], weather) if "border" in n else None,
                                width=round(n.get("borderWidth", 2)))
        elif kind in ("list", "text"):
            rows = lists.get(n.get("source"), [])[:80]
            if n.get("numbered"):
                rows = [f"{i}. {row}" for i, row in enumerate(rows, 1)]
            value = bind(n.get("text", ""), values) if kind == "text" else "\n".join(rows) or bind(n.get("emptyText", ""), values)
            write_text(d, n, value, w, h, ink, scale, spacing)
        elif kind == "weatherIcon":
            R._wx_icon(d, 0, 0, min(w, h), weather.get("code"))
        elif kind in ("forecastBars", "weatherHours"):
            weather_graph(d, kind, weather, w, h, ink)
        elif kind == "builtin":
            layer = NATIVE[n["kind"]](ctx).resize((w, h), Image.Resampling.NEAREST)
        else:
            layer = render_block(n, ctx, values, w, h, ink)
        image.paste(layer, (x, y), layer if layer.mode == "RGBA" else None)
    palette = Image.new("P", (1, 1))
    palette.putpalette([value for rgb in [*COLORS.values(), *([R.WHITE] * 250)] for value in rgb])
    return image.quantize(palette=palette, dither=Image.Dither.NONE).convert("RGB")


def render_block(node, ctx, values, w, h, ink):
    block_type = node["blockType"]
    text = bind(node.get("text", ""), values)
    if block_type == "poem":
        block_type, text = "text", values["poemTitle"] + "\n" + values["poemText"]
    if block_type == "countdown":
        block_type, text = "text", "\n".join(content(ctx)[1]["countdowns"])
    tile = Image.new("RGB", (528, 792), R.WHITE)
    block = {"type": block_type, "text": text, "size": node.get("textSize", 24), "height": h,
             "align": "center" if node.get("centered") else "left"}
    used = R._draw_block(ImageDraw.Draw(tile), tile, block, ctx, 0, 464, ink)
    tile = tile.crop((R.M, 0, 528-R.M, max(1, min(792, used))))
    tile.thumbnail((w, h), Image.Resampling.NEAREST)
    return tile


def png(image):
    output = BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()
