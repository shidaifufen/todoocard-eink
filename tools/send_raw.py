#!/usr/bin/env python3
"""把一张图直接推到卡片（不抖动、只吸附六色）。校准图用。"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import card  # noqa: E402

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python tools/send_raw.py <图片>")
        raise SystemExit(1)
    svc = card.init_service(Path("."), Path("data"))
    print(svc.send_raw_image(Path(sys.argv[1])))
