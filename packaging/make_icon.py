"""生成打包用图标：一张六色墨水屏卡片。

只用 Pillow，不依赖任何外部素材；尺寸按 Windows 图标规范给全套。
用法：python packaging/make_icon.py packaging/app.ico
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

BLACK = (0, 0, 0)
WHITE = (255, 255, 255)
YELLOW = (255, 255, 0)
RED = (255, 0, 0)
BLUE = (0, 0, 255)
GREEN = (0, 255, 0)

S = 1024  # 先画大的再缩小，边缘干净


def draw() -> Image.Image:
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = int(S * 0.10)
    r = int(S * 0.12)
    # 卡片本体（白底 + 黑边）
    d.rounded_rectangle([pad, pad, S - pad, S - pad], radius=r, fill=WHITE,
                        outline=BLACK, width=int(S * 0.035))
    # 左上红色方块 + 标题条
    x0, y0 = int(S * 0.20), int(S * 0.19)
    side = int(S * 0.085)
    d.rectangle([x0, y0, x0 + side, y0 + side], fill=RED)
    d.rectangle([x0 + int(side * 1.5), y0 + int(side * 0.25), int(S * 0.72), y0 + int(side * 0.8)], fill=BLACK)
    # 中间大数字 10（用方块拼，避免依赖字体）
    bx, by = int(S * 0.20), int(S * 0.35)
    bw = int(S * 0.085)
    bh = int(S * 0.20)
    d.rectangle([bx, by, bx + bw, by + bh], fill=RED)                       # 1
    d.rectangle([bx + bw * 1.6, by, bx + bw * 2.6, by + bh], fill=RED)      # 0 左
    d.rectangle([bx + bw * 1.6, by, bx + bw * 2.6, by + int(bh * 0.1)], fill=WHITE)
    # 黄条
    d.rectangle([int(S * 0.20), int(S * 0.60), int(S * 0.80), int(S * 0.645)], fill=YELLOW)
    # 下方三根数据条：绿 / 蓝 / 黑
    for i, (col, w) in enumerate(((GREEN, 0.38), (BLUE, 0.26), (BLACK, 0.32))):
        y = int(S * (0.68 + i * 0.075))
        d.rectangle([int(S * 0.20), y, int(S * (0.20 + w)), y + int(S * 0.035)], fill=col)
    return img


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "packaging/app.ico")
    out.parent.mkdir(parents=True, exist_ok=True)
    big = draw()
    big.save(out, format="ICO",
             sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    png = out.with_suffix(".png")
    big.resize((256, 256), Image.LANCZOS).save(png)
    print(f"icon -> {out} ({out.stat().st_size} bytes), preview -> {png}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
