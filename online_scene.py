"""Render random online scenes for the desktop scheduler without an open page."""

import json
import random
import re
import time
from collections import deque
from datetime import date, datetime, timezone
from io import BytesIO
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

from PIL import Image, ImageDraw, ImageOps

import card_render as render
from image_tone import INKS, VIVID, panel_preview, tune

CATEGORIES = {
    "online_music": "随机音乐封面", "online_movie": "随机电影海报",
    "online_english": "随机英语短语", "online_idiom": "随机成语",
    "online_rates": "汇率速览", "online_holidays": "假日倒数",
    "online_earthquake": "一周地震", "online_television": "随机剧集档案",
}
INFORMATION_COLORS = {"rates": "blue", "holidays": "yellow", "earthquake": "red", "television": "black"}
SHOWS = ("Sherlock", "The Office", "Planet Earth", "Friends", "Doctor Who", "The Crown")
VERBS = "bring call carry come cut drop fall figure find get give go hold keep leave look make move pick point pull put run set show stand take turn work".split()
W, H = 528, 792
recent = {kind: deque(maxlen=45) for kind in CATEGORIES}
cache = {}


def read(url, limit):
    request = Request(url, headers={"User-Agent": "TodooCard/1.0 (https://github.com/shidaifufen/todoocard-eink)"})
    with urlopen(request, timeout=12) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("在线来源返回的数据过大")
    return data


def feed(url, limit=1024 * 1024):
    now = time.monotonic()
    old = cache.get(url)
    if old and old[0] > now:
        return old[1]
    data = json.loads(read(url, limit))
    cache[url] = (now + 900, data)
    return data


def chart(mode):
    countries = ["hk", "us"] if mode == "music" else ["us"]
    feed_type = "songs" if mode == "music" else "movies"
    random.shuffle(countries)
    for country in countries:
        try:
            data = feed(f"https://itunes.apple.com/{country}/rss/top{feed_type}/limit=50/json")
            if entries := data.get("feed", {}).get("entry"):
                return entries
        except (OSError, ValueError):
            continue
    raise ValueError("Apple 榜单暂时不可用")


def draw(kind):
    mode = kind.removeprefix("online_")
    if mode in INFORMATION_COLORS:
        title, subtitle, rows, source = information_fields(mode)
        return information_card(mode, title, subtitle, rows, source)
    if mode in ("music", "movie"):
        entries = chart(mode)
        keys = [entry["im:name"]["label"] for entry in entries]
    elif mode == "english":
        entries = []
        for verb in random.sample(VERBS, 3):
            query = urlencode({"sp": f"{verb} *", "md": "d", "max": 45})
            entries = [entry for entry in feed("https://api.datamuse.com/words?" + query)
                       if " " in entry["word"] and len(entry["word"]) <= 42 and entry.get("defs")]
            if any(entry["word"] not in recent[kind] for entry in entries):
                break
        keys = [entry["word"] for entry in entries]
    else:
        dictionary = feed("https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/idiom.json", 16 * 1024 * 1024)
        entries = [entry for entry in dictionary if len(entry["word"]) == 4 and entry.get("explanation", "").strip()]
        keys = [entry["word"] for entry in entries]
    candidates = [index for index, key in enumerate(keys) if key not in recent[kind]]
    if not candidates:
        candidates = [index for index, key in enumerate(keys) if not recent[kind] or key != recent[kind][-1]]
    if not candidates:
        raise ValueError("此类别暂时没有其他在线内容可抽取")
    for index in random.sample(candidates, min(3, len(candidates))):
        title = keys[index]
        entry = entries[index]
        try:
            image = art_card(mode, title, entry) if mode in ("music", "movie") else text_card(mode, title, entry)
        except (OSError, ValueError):
            continue
        recent[kind].append(title)
        return image
    raise ValueError("暂时无法生成此类别的在线卡片")


def information_fields(mode):
    if mode == "rates":
        symbols = ("CNY", "HKD", "EUR", "JPY")
        data = feed("https://api.frankfurter.dev/v1/latest?base=USD&symbols=" + ",".join(symbols))
        rows = [(code, f"{data['rates'][code]:.6f}".rstrip("0").rstrip(".")) for code in symbols if code in data["rates"]]
        return "1 USD", data["date"] + " · 参考汇率", rows, "Frankfurter"
    if mode == "holidays":
        today = date.today()
        upcoming = {}
        for year in (today.year, today.year + 1):
            try:
                holidays = feed(f"https://date.nager.at/api/v3/PublicHolidays/{year}/HK")
            except (OSError, ValueError):
                if not upcoming:
                    raise
                break
            for item in holidays:
                day = date.fromisoformat(item["date"])
                if day >= today and item.get("global"):
                    upcoming.setdefault(day, item.get("localName") or item["name"])
            if len(upcoming) >= 4:
                break
        if not upcoming:
            raise ValueError("暂时查不到未来公共假日")
        days = (min(upcoming) - today).days
        rows = [(day.strftime("%m-%d"), upcoming[day]) for day in sorted(upcoming)[:4]]
        return f"还有 {days} 天" if days else "今天是假日", "中国香港", rows, "Nager.Date · 全国或全区公共假日"
    if mode == "earthquake":
        endpoint = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_week.geojson"
        features = feed(endpoint)["features"]
        eligible = [item for item in features if item["properties"].get("mag") is not None]
        if not eligible:
            raise ValueError("近一周没有符合条件的重要地震")
        latest = max(eligible, key=lambda item: item["properties"]["time"])
        number = latest["properties"]
        timestamp = datetime.fromtimestamp(number["time"] / 1000, timezone.utc)
        depth = latest["geometry"]["coordinates"][2]
        rows = [("时间 UTC", timestamp.strftime("%Y-%m-%d %H:%M")), ("深度", f"{depth:.1f} km")]
        return f"M {number['mag']:.1f}", number["place"], rows, "USGS · 近一周重要地震"
    for query in random.sample(SHOWS, 3):
        matches = feed("https://api.tvmaze.com/search/shows?" + urlencode({"q": query}))
        available = [item["show"] for item in matches if item["show"]["id"] not in recent["online_television"]]
        if available:
            show = random.choice(available[:5])
            break
    else:
        raise ValueError("暂时没有其他剧集可抽取")
    recent["online_television"].append(show["id"])
    provider = show.get("network") or show.get("webChannel") or {}
    status = {"Running": "播出中", "Ended": "已完结", "In Development": "制作中"}
    rating = (show.get("rating") or {}).get("average")
    rows = [("首播", show.get("premiered")), ("状态", status.get(show.get("status"), show.get("status"))),
            ("平台", provider.get("name")), ("评分", f"{rating:.1f} / 10" if rating is not None else "暂无")]
    return show["name"], " · ".join(show["genres"]) or show.get("language"), rows, "TVmaze · CC BY-SA"


def information_card(mode, title, subtitle, rows, source):
    image = Image.new("RGB", (W, H), "white")
    painter = ImageDraw.Draw(image)
    color = INFORMATION_COLORS[mode]
    ink = "black" if color == "yellow" else "white"
    painter.rectangle((0, 0, W, 241), fill=color)
    text(painter, CATEGORIES["online_" + mode].removeprefix("随机"), 28, 25, 25, 472, ink, True)
    painter.rectangle((28, 64, 74, 69), fill=ink)
    text(painter, title, 28, 89, 49 if mode == "television" else 74, 472, ink, True)
    text(painter, subtitle, 28, 188, 23, 472, ink)
    rows = [(label, value) for label, value in rows if value]
    height = 464 // max(1, len(rows))
    for index, (label, value) in enumerate(rows):
        y = 252 + index * height
        if len(rows) >= 3:
            text(painter, label, 28, y + (height - 23) // 2, 23, 118, bold=True)
            text(painter, value, 152, y + (height - 33) // 2, 33, 348, bold=True)
        else:
            text(painter, label, 28, y + 30, 24, 472, bold=True)
            text(painter, value, 28, y + 77, 37, 472, bold=True)
        painter.rectangle((28, y + height - 3, 500, y + height - 2), fill="black")
    painter.rectangle((0, 736, W, H), fill="black")
    text(painter, source, 28, 754, 19, 472, "white")
    return render.snap_palette(image)


def text(draw, value, x, y, size, width, color="black", bold=False):
    font = render.font_px("bold" if bold else "light", size)
    value = str(value or "")
    if draw.textlength(value, font=font) > width:
        while value and draw.textlength(value + "…", font=font) > width:
            value = value[:-1]
        value += "…"
    draw.text((x, y), value, font=font, fill=color, anchor="lt")
    return draw.textbbox((x, y), value, font=font, anchor="lt")[3]


def analyze_cover(image):
    size = 128
    sample = image.resize((size, size), Image.Resampling.BILINEAR).load()

    def matte_depth(pixel_at):
        for depth in range(int(size * .16)):
            pixels = [pixel_at(depth, position) for position in range(size)]
            dark = sum(max(pixel) <= 22 for pixel in pixels)
            light = sum(min(pixel) >= 241 for pixel in pixels)
            if max(dark, light) < size * .98:
                return depth
        return int(size * .16)

    left = matte_depth(lambda d, y: sample[d, y])
    right = matte_depth(lambda d, y: sample[size - 1 - d, y])
    top = matte_depth(lambda d, x: sample[x, d])
    bottom = matte_depth(lambda d, x: sample[x, size - 1 - d])
    if min(left, right) < 2 or abs(left - right) > 3:
        left = right = 0
    if min(top, bottom) < 2 or abs(top - bottom) > 3:
        top = bottom = 0
    counts = [0] * len(INKS)
    for y in range(top, size - bottom):
        for x in range(left, size - right):
            rgb = sample[x, y]
            high, low = max(rgb), min(rgb)
            if high < 52 or high - low < 24:
                counts[int(sum(rgb) >= 384)] += 1
                continue
            vivid = tuple((channel - low) * 255 / (high - low) for channel in rgb)
            winner = min(range(len(INKS)),
                         key=lambda index: sum((channel - target) ** 2 for channel, target in zip(vivid, INKS[index])))
            counts[winner] += 1
    winner = max(range(2, len(INKS)), key=counts.__getitem__)
    area = (size - left - right) * (size - top - bottom)
    if counts[winner] < area * .035:
        winner = int(counts[1] > counts[0])
    crop = (round(image.width * left / size), round(image.height * top / size),
            round(image.width * (size - right) / size), round(image.height * (size - bottom) / size))
    return INKS[winner], image.crop(crop)


def art_card(mode, title, entry):
    movie = mode == "movie"
    address = entry["im:image"][-1]["label"]
    parsed = urlsplit(address)
    if parsed.scheme != "https" or not re.fullmatch(r"is\d+-ssl\.mzstatic\.com", parsed.netloc):
        raise ValueError("封面地址无效")
    address = re.sub(r"/\d+x\d+bb\.(?:jpg|png)$", f"/600x{900 if movie else 600}bb.jpg", address)
    with Image.open(BytesIO(read(address, 6 * 1024 * 1024))) as source:
        size = (W, H if movie else W)
        cover = source.convert("RGB")
        theme, cover = (INKS[0], cover) if movie else analyze_cover(cover)
        artwork = ImageOps.fit(cover, size, Image.Resampling.LANCZOS)
    image = Image.new("RGB", (W, H), theme)
    adjusted = tune(artwork, VIVID)
    image.paste(adjusted if movie else panel_preview(adjusted), (0, 0))
    painter = ImageDraw.Draw(image)
    if movie:
        named_year = re.search(r"\s*\(((?:18|19|20)\d{2})\)$", title)
        year = named_year[1] if named_year else entry.get("im:releaseDate", {}).get("label", "")[:4]
        if named_year:
            title = title[:named_year.start()]
        mask = Image.linear_gradient("L").resize((W, 96))
        image.paste("black", (0, 590, W, 686), mask)
        painter.rectangle((0, 686, W, H), fill="black")
        text(painter, title, 26, 700, 38, 476, "white", True)
        text(painter, year, 26, 754, 22, 476, "yellow", True)
    else:
        ink = "black" if theme in (INKS[1], INKS[2]) else "white"
        accent = "blue" if theme in (INKS[1], INKS[2]) else "yellow"
        painter.rectangle((28, 551, 92, 559), fill=accent)
        title_bottom = text(painter, title, 28, 575, 41, 472, ink, True)
        text(painter, entry.get("im:artist", {}).get("label"), 28, title_bottom + 9, 24, 472, ink)
        text(painter, "Apple Music", 28, 758, 17, 472, ink)
        image = render.snap_palette(image, keep=[(0, 0, W, W)])
    return image


def text_card(mode, title, entry):
    idiom = mode == "idiom"
    image = Image.new("RGB", (W, H), "white")
    painter = ImageDraw.Draw(image)
    background, ink = ("yellow", "black") if idiom else ("blue", "white")
    painter.rectangle((0, 0, W, 247), fill=background)
    text(painter, "成语" if idiom else "英语短语", 30, 26, 24, 468, ink, True)
    text(painter, title, 30, 86, 86 if idiom else 45, 468, ink, True)
    if idiom:
        text(painter, entry.get("pinyin"), 32, 197, 26, 464, ink)
        fields = [(label, entry.get(key)) for label, key in zip(("释义", "例句", "出处"), ("explanation", "example", "derivation"))]
    else:
        fields = [(f"释义 {index}", value.rsplit("\t", 1)[-1]) for index, value in enumerate(entry["defs"][:3], 1)]
    fields = [(label, value) for label, value in fields if value and value.strip()]
    height = 464 // max(1, len(fields))
    for index, (label, value) in enumerate(fields):
        y = 272 + height * index
        painter.rectangle((30, y + 4, 78, y + 10), fill=("blue", "red", "yellow")[index])
        text(painter, label, 95, y, 18, 402, bold=True)
        size = 25
        step = 32
        lines = render.wrap(painter, value, render.font_px("light", size), 468)
        limit = max(1, (height - 37) // step)
        for row, line in enumerate(lines[:limit]):
            if row + 1 == limit and len(lines) > limit:
                line += "…"
            text(painter, line, 30, y + 35 + row * step, size, 468)
    painter.rectangle((0, 744, W, H), fill="black")
    text(painter, "chinese-xinhua" if idiom else "Datamuse / Wiktionary", 30, 756, 18, 468, "white")
    return render.snap_palette(image)
