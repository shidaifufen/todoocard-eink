"""把 PNG 转成设备可用的六色位图 payload —— image_to_payload.swift 的 Python 版。

为什么重写：上游转换器是 Swift 编译产物，Windows 上跑不了。算法本身很短：

1. 等比铺满 528×792（cover），白底
2. Floyd–Steinberg 抖动量化到 6 个原生色（每像素 4bit 色码）
3. 两像素一字节（高半字节在前）
4. T3 面板标定：整体旋转 180° 再水平翻转
5. 控制器视角：顺时针旋转 + 镜像
6. 老固件兼容包：每 64 字节加 3 字节头 0x74 0x43 0x40
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageOps

CARD_W, CARD_H = 528, 792

# (R, G, B, code) —— 顺序即"等距时取靠前"的优先级，和 Swift 版一致
PALETTE = [
    (0, 0, 0, 0),
    (255, 255, 255, 1),
    (255, 255, 0, 2),
    (255, 0, 0, 3),
    (0, 0, 255, 5),
    (0, 255, 0, 6),
]


def _cover(img: Image.Image, w: int = CARD_W, h: int = CARD_H) -> Image.Image:
    src_w, src_h = img.size
    scale = max(w / src_w, h / src_h)
    draw_w, draw_h = src_w * scale, src_h * scale
    canvas = Image.new("RGB", (w, h), (255, 255, 255))
    resized = img.resize((max(1, round(draw_w)), max(1, round(draw_h))), Image.LANCZOS)
    canvas.paste(resized, (int((w - resized.width) / 2), int((h - resized.height) / 2)))
    return canvas


def _quantize(img: Image.Image) -> bytes:
    """Floyd–Steinberg，返回 4bit/像素的位图。"""
    w, h = img.size
    px = list(img.getdata())
    work = [[float(p[0]), float(p[1]), float(p[2])] for p in px]

    codes = bytearray(w * h)

    def add(x: int, y: int, dr: float, dg: float, db: float, k: float) -> None:
        if 0 <= x < w and 0 <= y < h:
            o = y * w + x
            work[o][0] += dr * k
            work[o][1] += dg * k
            work[o][2] += db * k

    for y in range(h):
        for x in range(w):
            i = y * w + x
            r, g, b = (min(255.0, max(0.0, v)) for v in work[i])
            best, best_d = PALETTE[0], float("inf")
            for pr, pg, pb, code in PALETTE:
                dr, dg, db = r - pr, g - pg, b - pb
                d = dr * dr + dg * dg + db * db
                if d < best_d:
                    best_d, best = d, (pr, pg, pb, code)
            codes[i] = best[3]
            er, eg, eb = r - best[0], g - best[1], b - best[2]
            add(x + 1, y, er, eg, eb, 7 / 16)
            add(x - 1, y + 1, er, eg, eb, 3 / 16)
            add(x, y + 1, er, eg, eb, 5 / 16)
            add(x + 1, y + 1, er, eg, eb, 1 / 16)

    out = bytearray((w * h + 1) // 2)
    for i, code in enumerate(codes):
        if i % 2 == 0:
            out[i // 2] = (code & 0x0F) << 4
        else:
            out[i // 2] |= code & 0x0F
    return bytes(out)


def _get(buf: bytes | bytearray, i: int) -> int:
    byte = buf[i // 2]
    return byte >> 4 if i % 2 == 0 else byte & 0x0F


def _set(buf: bytearray, i: int, code: int) -> None:
    if i % 2 == 0:
        buf[i // 2] = (buf[i // 2] & 0x0F) | ((code & 0x0F) << 4)
    else:
        buf[i // 2] = (buf[i // 2] & 0xF0) | (code & 0x0F)


def _flip_h(buf: bytes, w: int = CARD_W, h: int = CARD_H) -> bytes:
    out = bytearray(len(buf))
    for y in range(h):
        for x in range(w):
            _set(out, y * w + (w - 1 - x), _get(buf, y * w + x))
    return bytes(out)


def _rot180(buf: bytes, n: int = CARD_W * CARD_H) -> bytes:
    out = bytearray(len(buf))
    for i in range(n):
        _set(out, n - 1 - i, _get(buf, i))
    return bytes(out)


def _rotate_cw_mirrored(buf: bytes, w: int = CARD_W, h: int = CARD_H) -> bytes:
    out = bytearray(len(buf))
    for di in range(w * h):
        dx, dy = di % h, di // h
        sx, sy = w - 1 - dy, h - 1 - dx
        _set(out, di, _get(buf, sy * w + sx))
    return bytes(out)


def _legacy_qlz(raw: bytes, block: int = 64) -> bytes:
    out = bytearray(b"\x00\x00\x00\x00")
    for off in range(0, len(raw), block):
        out += b"\x74\x43\x40" + raw[off:off + block]
    return bytes(out)


def build(image_path: Path, out_prefix: Path) -> dict:
    """生成 .bin / .controller.bin / .protocol.legacy.qlz，返回三个路径。"""
    img = ImageOps.exif_transpose(Image.open(image_path)).convert("RGB")
    img = _cover(img)
    bitmap = _quantize(img)
    bitmap = _flip_h(_rot180(bitmap))          # T3 面板标定
    controller = _rotate_cw_mirrored(bitmap)
    legacy = _legacy_qlz(controller)

    prefix = Path(out_prefix)
    prefix.parent.mkdir(parents=True, exist_ok=True)
    out = {
        "bin": Path(f"{prefix}.bin"),
        "controller": Path(f"{prefix}.controller.bin"),
        "legacy": Path(f"{prefix}.protocol.legacy.qlz"),
    }
    out["bin"].write_bytes(bitmap)
    out["controller"].write_bytes(controller)
    out["legacy"].write_bytes(legacy)
    return out


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="PNG → 六色墨水屏 payload")
    ap.add_argument("--input", required=True, type=Path)
    ap.add_argument("--output-prefix", required=True, type=Path)
    a = ap.parse_args()
    for k, v in build(a.input, a.output_prefix).items():
        print(f"{k}: {v} ({v.stat().st_size} bytes)")
