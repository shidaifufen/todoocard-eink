"""土豆片（TodooCard / T3 六色墨水屏）—— 最小 Web 服务。

渲染、发送队列、定时计划都在 card.py，这里只做 HTTP 壳。
"""
from __future__ import annotations

import asyncio
import logging
import os
import sys
from pathlib import Path

from aiohttp import web

import card

ROOT = Path(__file__).resolve().parent
WEB_DIR = ROOT / "web"
DATA_DIR = ROOT / "data"
PORT = int(os.environ.get("PORT", "8199"))

_LOGGER = logging.getLogger("todoocard")


async def page(request):
    path = request.match_info["name"]
    f = WEB_DIR / path
    if not f.exists():
        raise web.HTTPNotFound()
    return web.FileResponse(f)


async def index(request):
    return web.FileResponse(WEB_DIR / "card.html")


def build_app(svc) -> web.Application:
    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_get("/card.html", index)
    app.router.add_get("/{name:scenes\.html}", page)
    for method, path, handler in card.make_routes():
        app.router.add_route(method, path, handler)
    app["svc"] = svc
    return app


async def main():
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    DATA_DIR.mkdir(exist_ok=True)
    svc = card.init_service(ROOT, DATA_DIR)
    svc.start()
    app = build_app(svc)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", PORT)
    await site.start()
    _LOGGER.info("土豆片服务已启动： http://127.0.0.1:%s/card.html", PORT)
    try:
        await asyncio.Event().wait()
    finally:
        await svc.stop()
        await runner.cleanup()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
