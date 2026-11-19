#!/usr/bin/env python3
"""生成六色校准图：推到卡片上，拍照后交给 analyze_calibration.py。

    python tools/make_calibration.py            # 输出 calib/chart.png
    python tools/make_calibration.py --send     # 直接推到卡片（需已配置设备）
"""
from __future__ import annotations
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from PIL import Image, ImageDraw  # noqa: E402

W, H = 528, 792
COLORS = [
    ("BLACK", (0, 0, 0)), ("WHITE", (255, 255, 255)), ("YELLOW", (255, 255, 0)),
    ("RED", (255, 0, 0)), ("BLUE", (0, 0, 255)), ("GREEN", (0, 255, 0)),
]
GRAYS = [0, 32, 64, 96, 128, 160, 192, 224]


def build(path: Path) -> Path:
    img = Image.new("RGB", (W, H), (255, 255, 255))
    d = ImageDraw.Draw(img)

    # 四角定位块：拍照后用它判断卡片边界
    for x, y in ((8, 8), (W - 68, 8), (8, H - 68), (W - 68, H - 68)):
        d.rectangle([x, y, x + 60, y + 60], fill=(0, 0, 0))

    d.text((80, 26), "CALIBRATION", fill=(0, 0, 0))
    d.text((80, 46), "crop the card edges, then shoot straight", fill=(0, 0, 0))

    # 六色块（3×2），每块 168×150
    y0 = 90
    for i, (name, rgb) in enumerate(COLORS):
        cx = 8 + (i % 3) * 172
        cy = y0 + (i // 3) * 158
        d.rectangle([cx, cy, cx + 164, cy + 150], fill=rgb, outline=(0, 0, 0))
        d.text((cx + 6, cy + 6), name, fill=(0, 0, 0) if name == "WHITE" else (255, 255, 255))

    # 灰阶条（8 级）
    gy = y0 + 2 * 158 + 10
    for i, g in enumerate(GRAYS):
        cx = 8 + i * 64
        d.rectangle([cx, gy, cx + 60, gy + 70], fill=(g, g, g), outline=(0, 0, 0))
        d.text((cx + 4, gy + 74), str(g), fill=(0, 0, 0))

    # 底部：真实照片区域（用来看肤色/天空过渡）
    d.rectangle([8, gy + 100, W - 8, H - 8], fill=(255, 255, 255), outline=(0, 0, 0))
    for i in range(0, W - 16, 8):
        t = i / (W - 16)
        d.rectangle([8 + i, gy + 100, 8 + i + 8, H - 8],
                    fill=(int(255 * t), int(180 + 60 * (1 - t)), int(255 * (1 - t))))

    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    return path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="calib/chart.png")
    ap.add_argument("--send", action="store_true")
    args = ap.parse_args()

    out = build(Path(args.out))
    print(f"已生成：{out.resolve()}")

    if args.send:
        from pathlib import Path as P
        import card
        svc = card.init_service(P("."), P("data"))
        res = svc.send_image(out)
        print(res)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
