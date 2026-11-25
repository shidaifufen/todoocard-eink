"""Artwork controls and previews using the existing six-color quantizer."""

from PIL import Image

from payload import PALETTE, _quantize

LIMITS = {"brightness": (65, 145), "contrast": (65, 160), "saturation": (0, 180),
          "shadows": (0, 100), "snap": (0, 100)}
NEUTRAL = {"brightness": 100, "contrast": 100, "saturation": 100, "shadows": 0, "snap": 0}
VIVID = {**NEUTRAL, "brightness": 107, "contrast": 120, "saturation": 130, "snap": 40}
INKS = [row[:3] for row in PALETTE]


def settings(value):
    result = NEUTRAL.copy()
    if isinstance(value, dict):
        for key, (low, high) in LIMITS.items():
            number = value.get(key)
            if isinstance(number, int):
                result[key] = max(low, min(high, number))
    return result


def tune(image, options):
    if options == NEUTRAL:
        return image
    gain = options["contrast"] / 100
    saturation = options["saturation"] / 100
    offset = 128 * (1 - gain) + (options["brightness"] - 100) * 2.55
    weights = (.299, .587, .114)
    matrix = tuple(coefficient for channel in range(3) for coefficient in (
        *(gain * (weight * (1 - saturation) + (saturation if channel == index else 0))
          for index, weight in enumerate(weights)), offset
    ))
    result = image.convert("RGB", matrix)
    snap = options["snap"]
    shadows = options.get("shadows", 0)
    if not snap and not shadows:
        return result
    limit = 3 * (snap * 1.1) ** 2

    def finish_pixel(pixel):
        r, g, b = pixel
        luma = .299 * r + .587 * g + .114 * b
        if shadows:
            luminance = luma / 255
            lift = shadows * 2.55 * luminance * (1 - luminance) ** 3
            pixel = r, g, b = tuple(min(255, round(channel + lift)) for channel in pixel)
            luma += lift
        if not snap:
            return pixel
        if max(pixel) - min(pixel) < snap * .45:
            if luma <= snap * .8:
                return INKS[0]
            if luma >= 255 - snap * .8:
                return INKS[1]
        best_distance = limit
        for ink in INKS:
            distance = (r - ink[0]) ** 2 + (g - ink[1]) ** 2 + (b - ink[2]) ** 2
            if distance < best_distance:
                pixel, best_distance = ink, distance
        return pixel

    result.putdata([finish_pixel(pixel) for pixel in result.getdata()])
    return result


def panel_preview(image):
    packed = _quantize(image.convert("RGB"))
    lookup = {code: (r, g, b) for r, g, b, code in PALETTE}
    preview = Image.new("RGB", image.size)
    preview.putdata([lookup[code] for byte in packed for code in (byte >> 4, byte & 15)][:image.width * image.height])
    return preview
