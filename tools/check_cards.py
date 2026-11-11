"""卡片版式自检：13 个模板 × 100%/110%/130% 字号。

检查四件事：
  1. 渲染异常
  2. 调色板外像素（>0 = 抗锯齿灰边没吸附，真机白底会出彩色噪点）
  3. 安全边距外的墨迹（压边框 / 出界）
  4. **文字互相压字**（记录每个 text 的包围盒，两两求交，排除下标/单位这类贴身元素）

用法：.venv\\Scripts\\python.exe tools\\check_cards.py [输出目录]
"""
from __future__ import annotations

import pathlib
import sys
from datetime import datetime

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import card as C          # noqa: E402
import card_render as R    # noqa: E402


def build_ctx(svc) -> dict:
    now = datetime.now()
    return {
        "now": now,
        "todos": ["09:30 项目周会：确认下周排期", "13:00 和设计过一版卡片排版",
                  "18:30 健身房", "读完《设计中的设计》第三章"],
        "quote": svc.daily_quote(),
        "poem": svc.daily_poem(),
        "note": "此诗以春江潮平与明月相映，描绘了江水与月光交织的景象。作者用连绵的潮声和星光般的月色，"
                "展示江面无边的光晕；随后描写月光照射花林，似柔霜点缀。通过流动与静止的对比，"
                "突显春江的柔美与宁静。",
        "weather": svc.weather_data(),
        "countdowns": [{"date": "2027-01-01", "name": "元旦"},
                       {"date": "2026-12-25", "name": "圣诞"}],
        "energy": [
            {"port": "C1", "wh": 12.4, "peak": 65, "count": 2},
            {"port": "C2", "wh": 3.1, "peak": 18, "count": 1},
            {"port": "USB-A", "wh": 0.8, "peak": 5, "count": 1},
        ],
        "energy_today": [{"port": "C1", "wh": 5.2, "count": 1}, {"port": "C2", "wh": 3.3, "count": 2}, {"port": "USB-A", "wh": 1.1, "count": 1}],
        "ai": {"prompt": "a quiet desk at night, warm lamp, cinematic", "image": None},
        "custom": {"title": "我的卡片", "accent": "red", "blocks": [
            {"type": "bigdate"}, {"type": "weather"}, {"type": "todos"},
            {"type": "quote"}, {"type": "dots"}]},
        "image_path": None,
        "title": "我的卡片",
    }


TEMPLATES = ["summary", "weather", "weather2", "progress", "todo", "quote", "poem",
             "calendar", "countdown", "energy", "ai", "custom"]

# 贴身元素白名单：这些短文本和它左边的数字本来就该挨着
_TIGHT = {"件", "天", "月", "项", "次", "Wh", "%", "°C", "°", "日", "·"}


def install_text_recorder():
    """给 ImageDraw.text 打补丁（只打一次），记录每段文字的位置，用来查压字。"""
    from PIL import ImageDraw

    records: list[tuple[str, tuple]] = []
    if getattr(ImageDraw.ImageDraw, "_dsh_recorded", False):
        return records
    original = ImageDraw.ImageDraw.text
    box = records

    def patched(self, xy, text, *args, **kwargs):
        out = original(self, xy, text, *args, **kwargs)
        try:
            font = kwargs.get("font")
            bbox = self.textbbox(tuple(xy), str(text), font=font)
            box.append((str(text), bbox))
        except Exception:
            pass
        return out

    ImageDraw.ImageDraw.text = patched
    ImageDraw.ImageDraw._dsh_recorded = True
    return records


def slot_violations(records, name: str) -> list[str]:
    """文字越过槽位边界：正文压到页脚线、页眉文字跑出色块、越过安全区。"""
    out = []
    for text, b in records:
        if not text.strip():
            continue
        x0, y0, x1, y1 = b
        if name == "todo" and y0 < R.Y_RULE - 2 and y1 > R.Y_RULE - 2:
            out.append(f"{text[:14]!r} 越出待办页眉色块 (底边 {y1} > {R.Y_RULE - 2})")
        if y0 < R.Y_FOOT_RULE - 4 and y1 > R.Y_FOOT_RULE - 2:
            out.append(f"{text[:14]!r} 压到页脚线 (底边 {y1} > {R.Y_FOOT_RULE - 2})")
        if y1 > R.Y_SAFE_BOTTOM:
            out.append(f"{text[:14]!r} 越过安全区下沿 ({y1} > {R.Y_SAFE_BOTTOM})")
        if y0 < 2 or x1 > R.CARD_W - 2:
            out.append(f"{text[:14]!r} 出画布 ({b})")
    return out


def find_overlaps(records, min_area: int = 24) -> list[str]:
    out = []
    for i in range(len(records)):
        t1, b1 = records[i]
        for j in range(i + 1, len(records)):
            t2, b2 = records[j]
            if not t1.strip() or not t2.strip():
                continue
            if t1.strip() in _TIGHT or t2.strip() in _TIGHT:
                continue
            dx = min(b1[2], b2[2]) - max(b1[0], b2[0])
            dy = min(b1[3], b2[3]) - max(b1[1], b2[1])
            if dx > 1 and dy > 1 and dx * dy >= min_area:
                out.append(f"{t1[:16]!r}@{b1} × {t2[:16]!r}@{b2}  重叠 {dx}x{dy}")
    return out


def ink_outside_safe_area(img, top_band: bool = False):
    px = img.load()
    w, h = img.size
    bad = 0
    box = None
    for y in range(h):
        for x in range(w):
            if px[x, y] == (255, 255, 255):
                continue
            if top_band and y <= R.Y_RULE + 2:
                continue
            if x < R.M - 2 or x > w - R.M + 2 or y < 2 or y > h - 2:
                bad += 1
                box = (x, y, x, y) if box is None else (
                    min(box[0], x), min(box[1], y), max(box[2], x), max(box[3], y))
    return bad, box


def main() -> int:
    out_dir = pathlib.Path(sys.argv[1] if len(sys.argv) > 1
                           else pathlib.Path.home() / "Desktop" / "card_check")
    out_dir.mkdir(parents=True, exist_ok=True)
    svc = C.init_service(ROOT, ROOT / "data")
    svc.cfg["folders"] = [str(pathlib.Path.home() / "Pictures")]
    ctx = build_ctx(svc)

    problems = 0
    for sc in (1.0, 1.1, 1.3):
        R.set_scale(sc)
        print(f"\n=== 字号 {int(sc * 100)}% ===")
        for name in TEMPLATES:
            fn_name = "render_weather_blocks" if name == "weather2" else f"render_{name}"
            fn = getattr(R, fn_name)
            records = install_text_recorder()
            records.clear()
            try:
                img = fn(ctx)
            except Exception as e:
                print(f"  {name:10s} 渲染异常: {type(e).__name__}: {e}")
                problems += 1
                continue
            bad_px = R.palette_errors(img)
            margin, mbox = ink_outside_safe_area(img, top_band=(name == "todo"))
            overlaps = find_overlaps(records)
            slots = slot_violations(records, name)
            flags = []
            if bad_px:
                flags.append(f"调色板外 {bad_px}")
            if margin:
                flags.append(f"安全区外 {margin}@{mbox}")
            if overlaps:
                flags.append(f"压字 {len(overlaps)} 处")
            if slots:
                flags.append(f"越界 {len(slots)} 处")
            if flags:
                problems += 1
                print(f"  {name:10s} " + " / ".join(flags))
                for line in (overlaps[:3] + slots[:3]):
                    print(f"      {line}")
            else:
                print(f"  {name:10s} OK")
            img.save(out_dir / f"{name}_{int(sc * 100)}.png")
    print(f"\n产物：{out_dir}    问题数：{problems}")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
