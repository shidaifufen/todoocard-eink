"""Render random online scenes for the desktop scheduler without an open page."""

import json
import random
import re
import time
from collections import deque
from io import BytesIO
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

from PIL import Image, ImageDraw, ImageOps

import card_render as render
from image_tone import VIVID, tune

CATEGORIES = {
    "online_music": "随机音乐封面", "online_movie": "随机电影海报",
    "online_english": "随机英语短语", "online_idiom": "随机成语",
}
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


def text(draw, value, x, y, size, width, color="black", bold=False):
    font = render.font_px("bold" if bold else "light", size)
    value = str(value or "")
    if draw.textlength(value, font=font) > width:
        while value and draw.textlength(value + "…", font=font) > width:
            value = value[:-1]
        value += "…"
    draw.text((x, y), value, font=font, fill=color, anchor="lt")


def art_card(mode, title, entry):
    movie = mode == "movie"
    address = entry["im:image"][-1]["label"]
    parsed = urlsplit(address)
    if parsed.scheme != "https" or not re.fullmatch(r"is\d+-ssl\.mzstatic\.com", parsed.netloc):
        raise ValueError("封面地址无效")
    address = re.sub(r"/\d+x\d+bb\.(?:jpg|png)$", f"/600x{900 if movie else 600}bb.jpg", address)
    with Image.open(BytesIO(read(address, 6 * 1024 * 1024))) as source:
        size = (W, H if movie else W)
        artwork = ImageOps.fit(source.convert("RGB"), size, Image.Resampling.LANCZOS)
    image = Image.new("RGB", (W, H), "black")
    image.paste(tune(artwork, VIVID), (0, 0))
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
        painter.rectangle((28, 551, 92, 559), fill="yellow")
        text(painter, title, 28, 575, 41, 472, "white", True)
        text(painter, entry.get("im:artist", {}).get("label"), 28, 662, 24, 472, "white")
        text(painter, "Apple Music", 28, 758, 17, 472, "white")
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
