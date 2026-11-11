"""土豆片（TodooCard / T3 528×792 六色墨水屏）—— 服务端渲染 + 定时推送。

融合了两份上游：
- XDS9314/todoocard（网页工坊）：模板/配色的产品思路，这里用 Pillow 在服务端重画，
  这样 Mac 可以定时生成，不需要有人打开浏览器点"发送"。
- Sunbelife/TodooCard_Skills（macOS Skill）：唯一与固件逐字节对齐的传输实现
  （v157+ QuickLZ level-3、32 包落盘确认、断点续传、面板方向校准）。这里直接调用
  它编译出的二进制，不自己重写 BLE 协议。

六色只有黑/白/黄/红/蓝/绿 6 个值：文字全部用**精确调色板色**绘制，经过上游的
Floyd–Steinberg 时误差为 0，不会被抖动糊掉；照片才需要真正的抖动。
"""
from __future__ import annotations

import asyncio
import json
import logging
import random
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

import card_render as R
from ble_lock import BLE_GUARD
from datetime import datetime, timedelta
from pathlib import Path

from aiohttp import web

_LOGGER = logging.getLogger("todoocard")

# 字号是 card_render 的模块级全局量；发送在 worker 线程渲染、预览在事件循环渲染，
# 两边同时跑会互相覆盖字号。加锁保证一次只有一个渲染。
_RENDER_LOCK = threading.Lock()

CARD_W, CARD_H = 528, 792
BLACK = (0, 0, 0)
WHITE = (255, 255, 255)
YELLOW = (255, 255, 0)
RED = (255, 0, 0)
BLUE = (0, 0, 255)
GREEN = (0, 255, 0)

PHOTO_EXT = {".jpg", ".jpeg", ".png", ".heic", ".webp", ".tif", ".tiff", ".bmp"}
WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

FONT_CANDIDATES = {
    "bold": ["/System/Library/Fonts/STHeiti Medium.ttc",
             "/System/Library/Fonts/Hiragino Sans GB.ttc",
             "/System/Library/Fonts/Supplemental/Songti.ttc"],
    "light": ["/System/Library/Fonts/STHeiti Light.ttc",
              "/System/Library/Fonts/Hiragino Sans GB.ttc",
              "/System/Library/Fonts/Supplemental/Songti.ttc"],
    # 数字/单位单独用拉丁字体：中文字体的 ° 是占满一个字宽的，和 C 拼起来会散成 "8° C"
    "num": ["/System/Library/Fonts/HelveticaNeue.ttc",
            "/System/Library/Fonts/Avenir Next.ttc",
            "/System/Library/Fonts/Helvetica.ttc",
            "/System/Library/Fonts/SFNS.ttf"],
}


def _font(kind: str, size: int):
    """统一走 card_render 的字体表（含 Windows 的 msyh/msyhbd），别再维护第二份。"""
    return R.font(kind, size)


def _load_font():
    from PIL import ImageFont
    return ImageFont


def _fmt_int(v):
    return f"{int(round(v))}"


class CardService:
    """渲染、轮播、调度、发送。"""

    def __init__(self, base_dir: Path, data_dir: Path, sse=None):
        self.base = Path(base_dir).resolve()   # .../app/ble_server（必须绝对，否则 skill 路径会相对 cwd 漂移）
        self.data_dir = Path(data_dir).resolve()
        self.cards_dir = self.data_dir / "cards"
        self.cards_dir.mkdir(parents=True, exist_ok=True)
        self.cfg_path = self.data_dir / "card.json"
        self.hist_path = self.data_dir / "card_history.json"
        self.skill_dir = Path(base_dir).parent / "todoocard" / "scripts"
        self.bin_dir = self.data_dir / "bin"
        self.bin_dir.mkdir(parents=True, exist_ok=True)
        self.sse = sse
        self._task = None
        self._pair_task = None
        self._pair_lines: list[str] = []
        self._last_tick = 0.0
        self._last_tick_text = ""
        self._pair_state = "idle"   # idle | waiting | pairing | success | fail
        self._stop = asyncio.Event()
        self._lock = asyncio.Lock()
        self._busy = False
        self._weather_cache = {"ts": 0, "text": ""}
        self._wx_cache = {"ts": 0, "data": {}}
        self._quote_cache = {}
        self._poem_cache = {}
        try:
            self._note_cache = json.loads((self.data_dir / "poem_notes.json").read_text(encoding="utf-8"))
        except Exception:
            self._note_cache = {}
        try:
            self._cities = json.loads((self.data_dir / "cities.json").read_text(encoding="utf-8"))
        except Exception:
            self._cities = []
        self.cfg = self._load_cfg()
        self.history = self._load_history()
        # 上次 AI 生成的图（存在配置里，重启后还能预览/直接推送）
        _last = (self.cfg.get("ai") or {}).get("last")
        self._ai_last = _last if isinstance(_last, dict) and _last.get("path") else {}

    # ── 配置/历史 ──
    def _load_cfg(self) -> dict:
        cfg = {
            "enabled": True,
            "device_id": "",
            "device_name": "",
            "folders": [],
            "cooldown_days": 7,
            "recursive": True,          # 是否穿透子文件夹
            "scale": 1.1,               # 字号缩放（1.0~1.35）
            "fill": True,               # 内容整体放大填满画布
            "theme": "light",           # light / wo
            "retry_max": 3,
            "retry_delay_min": 5,
            "catch_up_min": 30,
            "poem_note": "",            # 自定义赏析；留空则自动生成
            "poem_ai": {"enabled": True, "endpoint": "https://api.deepseek.com/v1",
                        "api_key": "", "model": "deepseek-chat"},
            "standby": False,           # 待机：暂停扫描/补发/定时，把蓝牙让给网页版
            "line_gap": 1.1,            # 行距系数（默认 1.1）
            "custom": {"title": "我的卡片", "accent": "red",
                       "blocks": [{"type": "bigdate"}, {"type": "todos"},
                                  {"type": "quote"}, {"type": "dots"}]},
            "photo_mode": "shuffle",   # shuffle=洗牌不重复 / random=纯随机 / sequential=按文件名顺序
            "photo_overlay": True,     # 照片底部压一条信息带（日期 + 室内温湿度）
            "schedule": [
                {"time": "07:30", "kind": "summary", "enabled": True},
                {"time": "21:00", "kind": "photo", "enabled": True},
            ],
            "todos": [],
            "countdowns": [],
            "weather": {"enabled": True, "name": "北京", "lat": 39.9042, "lon": 116.4074},
            # Bing 每日壁纸：不开文件夹也能每天换一张"摄影级"照片
            "bing_enabled": True,
            # AI 生图：留空就用内置提示词 + 本地海报；填了 OpenAI 兼容接口就真出图
            "ai": {"endpoint": "", "api_key": "", "model": "gpt-image-1", "size": "1024x1024"},
            "ai_prompt": "",
            "auto_pair": True,
            # 卡片被按键唤醒（开始广播）时，自动按当前轮播方式推一张照片
            "button_photo": False,
            "button_cooldown": 300,
            "wait_card_sec": 180,
            "deck": {"used": []},
            # 手动发送失败（卡片睡着/被手机占用）时进这里排队，等它广播时自动补发
            "outbox": [],
        }
        try:
            if self.cfg_path.exists():
                cfg.update(json.loads(self.cfg_path.read_text("utf-8")) or {})
        except Exception as e:
            _LOGGER.warning("card.json 读取失败: %s", e)
        return cfg

    def save_cfg(self, patch: dict) -> dict:
        cur = dict(self.cfg)
        for key in ("enabled", "device_id", "device_name", "folders", "schedule",
                    "todos", "weather", "cooldown_days", "photo_mode", "photo_overlay",
                    "bing_enabled", "ai", "ai_prompt", "button_photo", "countdowns",
                    "recursive", "scale", "custom", "fill", "theme", "poem_note",
                    "poem_ai", "standby", "line_gap"):
            if key in patch:
                cur[key] = patch[key]
        if "schedule" in patch:
            _old = [x for x in (self.cfg.get("schedule") or []) if x.get("time")]
            _new = patch.get("schedule") or []
            if _old and len(_new) == 0:
                # 明确允许清空，但把旧的存起来，界面上可以一键恢复
                cur["schedule_backup"] = _old
            clean = []
            for it in patch.get("schedule") or []:
                try:
                    hh, mm = str(it.get("time", "")).split(":")
                    t = f"{int(hh):02d}:{int(mm):02d}"
                except Exception:
                    continue
                # 只认 TEMPLATES 里真实存在的模板；以前写死 ("summary","photo")，
                # 选天气/待办/进度会被静默改回"每日总结"——看起来就像"保存后变回去了"
                _k = it.get("kind")
                kind = _k if _k in TEMPLATES else "summary"
                clean.append({"time": t, "kind": kind, "enabled": bool(it.get("enabled", True))})
            cur["schedule"] = sorted(clean, key=lambda x: x["time"])
            # 新存的计划不应该"追溯执行"：今天已经过点的那几条直接标记为已处理，
            # 否则一保存，调度器看到"到点了但没跑过"，立刻推一张卡上去。
            runs = cur.setdefault("last_runs", {})
            _now = datetime.now()
            for it in cur["schedule"]:
                hh, mm = (int(x) for x in it["time"].split(":"))
                if _now.replace(hour=hh, minute=mm, second=0, microsecond=0) <= _now:
                    runs.setdefault(f"{it['time']}_{it['kind']}", int(_now.timestamp()))
        if "folders" in patch:
            cur["folders"] = [str(f).strip() for f in (patch.get("folders") or []) if str(f).strip()]
        if "countdowns" in patch:
            clean = []
            for it in patch.get("countdowns") or []:
                if isinstance(it, dict) and it.get("date"):
                    clean.append({"date": str(it["date"])[:10],
                                  "name": str(it.get("name") or "").strip() or "纪念日"})
            cur["countdowns"] = clean[:8]
        if "todos" in patch:
            cur["todos"] = [str(t).strip() for t in (patch.get("todos") or []) if str(t).strip()][:8]
        self.cfg = cur
        self._save_cfg()
        return cur

    def _save_cfg(self):
        self.cfg_path.write_text(json.dumps(self.cfg, ensure_ascii=False, indent=2), "utf-8")

    def _load_history(self) -> list:
        try:
            if self.hist_path.exists():
                return json.loads(self.hist_path.read_text("utf-8"))[-200:]
        except Exception:
            pass
        return []

    def _add_history(self, entry: dict):
        self.history.append(entry)
        self.history = self.history[-200:]
        try:
            self.hist_path.write_text(json.dumps(self.history, ensure_ascii=False, indent=1), "utf-8")
        except Exception as e:
            _LOGGER.warning("写卡片历史失败: %s", e)

    # ── 自动配对：用户只需长按卡片按钮，其余交给后台 ──
    def _scan_card(self) -> dict | None:
        """扫 6 秒，找目标卡片，返回 {"addr":..., "pairing": bool}。"""
        from bleak import BleakScanner

        target = (self.cfg.get("device_id") or "").upper()

        async def _scan():
            devs = await BleakScanner.discover(timeout=6, return_adv=True)
            for addr, (dev, adv) in devs.items():
                mfr = (adv.manufacturer_data or {})
                # 注意：bleak 的 manufacturer_data **已经剥掉了 2 字节公司 ID**，
                # 而 Swift 那边拿到的 CBAdvertisementDataManufacturerData 是带 ID 的。
                # 所以这里的下标要整体左移 2：screen=data[0]|data[4]<<8、flags=data[1]。
                data = mfr.get(0x5053)
                if not data or len(data) < 5:
                    continue
                flags = data[1]
                screen = data[0] | (data[4] << 8)
                if screen != 0x134C:
                    continue
                name = (dev.name or adv.local_name or "")
                if target and str(addr).upper() != target and "todoo" not in name.lower():
                    continue
                return {"addr": str(addr), "pairing": bool(flags & 0x02)}
            return None

        try:
            return asyncio.run(_scan())
        except Exception as e:
            _LOGGER.debug("卡片扫描失败: %s", e)
            return None

    async def _pair_watch_loop(self):
        """每 15 秒看一眼：卡片进入配对窗口就立刻连上去完成系统配对。

        这样用户只要"长按按钮 5 秒"，不需要再跑脚本 —— 配对是 60 秒窗口，
        手动去点脚本经常就错过了。
        """
        _LOGGER.info("自动配对监听已启动（长按卡片背面按钮 5 秒即可）")
        while not self._stop.is_set():
            try:
                if self.cfg.get("standby"):
                    await asyncio.sleep(15)
                    continue
                need_scan = bool(self.cfg.get("outbox")) or self.cfg.get("button_photo")
                if self.cfg.get("device_id") and need_scan and not self._busy:
                    found = await asyncio.get_running_loop().run_in_executor(
                        None, self._scan_card)
                    if found:
                        if found["pairing"]:
                            _LOGGER.info("检测到配对窗口（%s），开始配对…", found["addr"])
                            await self._run_pair(found["addr"])
                        elif (self.cfg.get("outbox") and not self._busy):
                            # 卡片一被唤醒就先把排队的卡推掉，不用再等 15 分钟的重试周期
                            _LOGGER.info("卡片出现在广播里，立刻补发队列")
                            await self._drain_outbox()
                        elif self.cfg.get("button_photo") and not self._busy:
                            # 按键把卡片唤醒时它才开始广播：这时推一张，等于"按键换图"
                            cool = int(self.cfg.get("button_cooldown", 300))
                            last = float(self.cfg.get("button_photo_ts", 0))
                            if time.time() - last > cool:
                                self.cfg["button_photo_ts"] = int(time.time())
                                self._save_cfg()
                                _LOGGER.info("卡片被唤醒，按轮播推一张照片")
                                await self.send("photo", requeue=False)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                _LOGGER.debug("自动配对监听到异常: %s", e)
            self._last_tick = time.time()
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=20)
            except asyncio.TimeoutError:
                pass

    async def pair_session(self) -> dict:
        """交互式配对：把 CLI 的输出逐行喂给网页，用户能看到"现在该干嘛"。"""
        dev = (self.cfg.get("device_id") or "").strip()
        if not dev:
            return {"ok": False, "error": "还没有绑定设备：先点「扫描设备」选一台"}
        if self._pair_state in ("waiting", "pairing"):
            return {"ok": True, "state": self._pair_state, "log": self._pair_lines[-50:]}
        self._pair_lines = []
        self._pair_state = "waiting"
        self._emit()

        async def _run():
            try:
                if sys.platform != "darwin":
                    ok = await self._pair_bleak(dev)
                    self._pair_state = "success" if ok else "fail"
                    if ok:
                        await self._drain_outbox()
                    return
                sender, _ = await asyncio.get_running_loop().run_in_executor(None, self._ensure_bins)
                async with BLE_GUARD:
                    proc = await asyncio.create_subprocess_exec(
                        str(sender), "--pair", "--id", dev,
                        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
                    assert proc.stdout is not None
                    async for raw in proc.stdout:
                        line = raw.decode("utf-8", "replace").rstrip()
                        if not line:
                            continue
                        self._pair_lines.append(line)
                        self._pair_lines = self._pair_lines[-200:]
                        low = line.lower()
                        if "pairing window detected" in low or "encrypted system pairing" in low:
                            self._pair_state = "pairing"
                        if "pairing completed" in low:
                            self._pair_state = "success"
                            self.cfg["paired_ts"] = int(time.time())
                            self._save_cfg()
                        await proc.wait()
                    if self._pair_state == "success":
                        # 配对窗口里卡片是醒着的，这时候顺手把排队的卡推掉
                        await self._drain_outbox()
                    else:
                        self._pair_state = "fail"
            except Exception as e:
                self._pair_lines.append(f"[error] {e}")
                self._pair_state = "fail"
            finally:
                self._emit()

        self._pair_task = asyncio.create_task(_run(), name="card-pair-session")
        return {"ok": True, "state": self._pair_state}

    async def _pair_bleak(self, device_id: str) -> bool:
        """Windows / Linux：用系统 BLE 栈配对，不依赖 macOS 的 Swift 工具。

        日志按 pair_session 的约定写进 _pair_lines，网页的「配对」弹窗照样能看。
        """
        from bleak import BleakClient, BleakScanner

        def _log(msg: str):
            _LOGGER.info("%s", msg)
            self._pair_lines.append(msg)
            self._pair_lines = self._pair_lines[-200:]
            self._emit()

        try:
            async with BLE_GUARD:
                _log(f"[ble] 正在找 {device_id} …（长按卡片背面按钮 5 秒可让它进入配对窗口）")
                # bleak 3.x 只有 find_device_by_address（Windows 上就是 MAC 地址）
                device = await BleakScanner.find_device_by_address(device_id, timeout=25)
                if device is None:
                    _log("[ble] 没扫到这台卡片：确认它没被 TodooCam 等 App 连着，再长按按钮 5 秒")
                    return False
                _log(f"[ble] 找到 {device.name or device_id}，连接中…")
                client = BleakClient(device, timeout=30)
                await client.connect()
                try:
                    if sys.platform == "win32":
                        await client.pair()   # 走 Windows 系统蓝牙配对；其它平台没有这个 API
                    _log("[ble] 配对完成")
                finally:
                    await client.disconnect()
            self.cfg["paired_ts"] = int(time.time())
            self._save_cfg()
            self._emit()
            return True
        except Exception as e:
            _log(f"[ble] 配对失败: {e}")
            return False

    async def _run_pair(self, device_id: str) -> bool:
        if sys.platform != "darwin":
            ok = await self._pair_bleak(device_id)
            if ok:
                await self._drain_outbox()
            return ok
        try:
            sender, _ = await asyncio.get_running_loop().run_in_executor(None, self._ensure_bins)
            r = await asyncio.get_running_loop().run_in_executor(
                None, lambda: subprocess.run([str(sender), "--pair", "--id", device_id],
                                             capture_output=True, text=True, timeout=120))
            out = ((r.stdout or "") + (r.stderr or ""))[-1500:]
            ok = "Pairing completed" in out or "pairing succeeded" in out.lower()
            self.cfg["paired_ts"] = int(time.time()) if ok else 0
            self.cfg["pair_log"] = out[-500:]
            self._save_cfg()
            self._emit()
            _LOGGER.info("自动配对%s", "成功" if ok else "失败：" + out[-200:])
            if ok:
                await self._drain_outbox()
            return ok
        except Exception as e:
            _LOGGER.warning("自动配对异常: %s", e)
            return False

    # ── 重试队列（outbox） ──
    def _enqueue(self, kind: str):
        box = self.cfg.setdefault("outbox", [])
        # 同类型 30 分钟内只排一条，避免连点几下攒出十几条
        now = int(time.time())
        for item in box:
            if item.get("kind") == kind and now - item.get("created", 0) < 1800:
                return
        if len(box) >= 4:
            return
        box.append({"kind": kind, "created": now, "next": now + 15 * 60, "n": 1})
        self._save_cfg()
        _LOGGER.info("已把 %s 放进重试队列，等卡片可连时自动补发", kind)

    async def _drain_outbox(self):
        box = self.cfg.setdefault("outbox", [])
        if not box or self._busy:
            return
        now = time.time()
        for item in list(box):
            if now < item.get("next", 0):
                continue
            if item["kind"] == "photo" and not self._scan_photos():
                box.remove(item)
                self._save_cfg()
                continue
            res = await self.send(item["kind"], requeue=False, wait_sec=30)
            if res.get("ok"):
                box.remove(item)
            else:
                item["n"] = int(item.get("n", 1)) + 1
                item["next"] = int(now) + 15 * 60
                if item["n"] > 24:
                    box.remove(item)
                    _LOGGER.warning("队列里的 %s 重试 %d 次仍失败，放弃", item["kind"], item["n"])
            self._save_cfg()
            if res.get("ok"):
                break

    # ── 系统文件夹选择器 ──
    def pick_folders(self) -> list[str]:
        if sys.platform != "darwin":
            return self._pick_folders_tk()
        return self._pick_folders_macos()

    def _pick_folders_tk(self) -> list[str]:
        """Windows / Linux：tkinter 只能一次选一个，多选就多弹几次。"""
        try:
            import tkinter as tk
            from tkinter import filedialog
            root = tk.Tk()
            root.withdraw()
            picked = filedialog.askdirectory(title="选择照片文件夹（可多次选择）")
            root.destroy()
            return [picked] if picked else []
        except Exception as e:
            _LOGGER.warning("选文件夹失败: %s", e)
            return []

    def _pick_folders_macos(self) -> list[str]:
        """弹 macOS 原生「选择文件夹」对话框，返回选中的绝对路径。

        用 osascript 调 NSOpenPanel（choose folder），支持一次选多个；
        服务是本机用户进程，弹窗会出现在当前桌面。
        """
        script = ('set chosen to choose folder with prompt "选择土豆片的照片文件夹（可多选）" '
                  'with multiple selections allowed\n'
                  'set out to ""\n'
                  'repeat with f in chosen\n'
                  'set out to out & (POSIX path of f) & "\n"\n'
                  'end repeat\n'
                  'return out')
        try:
            r = subprocess.run(["/usr/bin/osascript", "-e", script],
                               capture_output=True, text=True, timeout=180)
        except subprocess.TimeoutExpired:
            return []
        if r.returncode != 0:
            _LOGGER.info("文件夹选择取消或失败: %s", (r.stderr or "").strip()[:120])
            return []
        paths = [ln.strip().rstrip("/") for ln in (r.stdout or "").splitlines() if ln.strip()]
        if paths:
            folders = list(self.cfg.get("folders") or [])
            for p_ in paths:
                if p_ not in folders:
                    folders.append(p_)
            self.cfg["folders"] = folders
            self._save_cfg()
            _LOGGER.info("新增照片文件夹: %s", paths)
        return paths

    # ── 照片池 ──
    def _scan_photos(self) -> list[str]:
        # 6 万张照片每次 preview 都重扫太慢，缓存 10 分钟
        cached = getattr(self, "_photos_cache", None)
        if cached and time.time() - cached[0] < 600:
            return cached[1]
        out = []
        for folder in self.cfg.get("folders") or []:
            p = Path(folder).expanduser()
            if not p.is_dir():
                continue
            try:
                walker = p.rglob("*") if self.cfg.get("recursive", True) else p.glob("*")
                for f in walker:
                    if not f.is_file() or f.suffix.lower() not in PHOTO_EXT:
                        continue
                    # 跳过隐藏文件与隐藏目录里的文件（macOS 的 .thumbnails、.Trashes 等）
                    if any(part.startswith(".") for part in f.relative_to(p).parts):
                        continue
                    out.append(str(f))
            except Exception as e:
                _LOGGER.warning("扫描 %s 失败: %s", folder, e)
        self._photos_cache = (time.time(), out)
        return out

    def pick_photo(self, consume: bool = True) -> str | None:
        """按配置的轮播方式选一张照片。

        - shuffle（默认）：洗牌轮播，一轮内不重复，轮空后重新洗牌；
          并避开最近用过的若干张（cooldown_days），比纯随机更不容易连着两天同一张。
        - random：纯随机（用户明确要"随机换"时用）。
        - sequential：按路径排序依次推进，适合"一天一张按顺序放"的相册。
        """
        photos = sorted(self._scan_photos())
        if not photos:
            return None
        mode = (self.cfg.get("photo_mode") or "shuffle").lower()
        deck = self.cfg.setdefault("deck", {})

        if mode == "random":
            pick = random.choice(photos)
            if consume:
                deck["used"] = (deck.get("used") or [])[-20:] + [pick]
                self._save_cfg()
            return pick

        if mode == "sequential":
            idx = int(deck.get("seq", 0)) % len(photos)
            pick = photos[idx]
            if consume:
                deck["seq"] = idx + 1
                self._save_cfg()
            return pick

        used = [u for u in (deck.get("used") or []) if u in photos]
        left = [p for p in photos if p not in used]
        if not left:
            used = []
            left = list(photos)
        random.shuffle(left)
        pick = left[0]
        if not consume:
            return pick
        used.append(pick)
        keep = max(4, int(self.cfg.get("cooldown_days", 7)) * 3)
        deck["used"] = used[-keep:]
        self._save_cfg()
        return pick

    # ── 天气（可选，Open-Meteo） ──
    def _weather_text(self) -> str:
        w = self.cfg.get("weather") or {}
        if not w.get("enabled"):
            return ""
        if time.time() - self._weather_cache["ts"] < 1800:
            return self._weather_cache["text"]
        try:
            url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                   "&current=temperature_2m,relative_humidity_2m,weather_code"
                   "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
                   "&timezone=auto&forecast_days=2"
                   % (w.get("lat", 39.9), w.get("lon", 116.4)))
            with urllib.request.urlopen(url, timeout=6) as r:
                d = json.loads(r.read().decode())
            cur = d.get("current") or {}
            daily = (d.get("daily") or {})
            t = cur.get("temperature_2m")
            lo = (daily.get("temperature_2m_min") or [None])[0]
            hi = (daily.get("temperature_2m_max") or [None])[0]
            code = cur.get("weather_code")
            text = f"{w.get('name', '')} {_fmt_int(t)}°C  {_fmt_int(lo)} ~ {_fmt_int(hi)}°C  {_wx(code)}"
        except Exception:
            text = ""
        self._weather_cache = {"ts": time.time(), "text": text}
        return text


    def nearest_city(self, lat: float, lon: float, max_km: float = 160) -> str:
        """离线城市库：取直线距离最近的城市，超过 max_km 就不硬猜。"""
        import math
        best, best_d = "", 1e9
        for c in self._cities:
            try:
                dlat = math.radians(c["lat"] - lat)
                dlon = math.radians(c["lon"] - lon)
                a = (math.sin(dlat / 2) ** 2
                     + math.cos(math.radians(lat)) * math.cos(math.radians(c["lat"]))
                     * math.sin(dlon / 2) ** 2)
                km = 2 * 6371 * math.asin(min(1, math.sqrt(a)))
            except Exception:
                continue
            if km < best_d:
                best, best_d = c["name"], km
        return best if best_d <= max_km else ""

    def resolve_city(self, name: str) -> dict:
        """城市名 → 经纬度。先联网查 Open-Meteo geocoding，失败退回内置 206 城。

        网页版（todoocard-web）里点「拉取」做的是同一件事：输入"深圳"就把坐标换掉，
        不然预览会一直显示默认的北京。
        """
        name = (name or "").strip()
        if not name:
            return {"ok": False, "error": "先填一个城市名"}
        # 1) 在线 geocoding（准，带重名消歧）
        try:
            import urllib.parse
            url = ("https://geocoding-api.open-meteo.com/v1/search?name=%s"
                   "&count=1&language=zh&format=json" % urllib.parse.quote(name))
            with urllib.request.urlopen(url, timeout=6) as r:
                d = json.loads(r.read().decode())
            results = d.get("results") or []
            if results:
                g = results[0]
                detail = " · ".join([x for x in (g.get("admin1"), g.get("country")) if x])
                return {"ok": True, "name": g.get("name") or name,
                        "lat": round(float(g["latitude"]), 4),
                        "lon": round(float(g["longitude"]), 4),
                        "source": "open-meteo", "detail": detail}
        except Exception as e:
            _LOGGER.debug("在线城市解析失败，改用离线城市库: %s", e)
        # 2) 离线城市库（内置 206 城，先精确后包含）
        cities = self._cities or []
        hit = next((c for c in cities if c.get("name") == name), None)
        if not hit:
            hit = next((c for c in cities
                        if name in (c.get("name") or "") or (c.get("name") or "") in name), None)
        if hit:
            return {"ok": True, "name": hit["name"], "lat": hit["lat"], "lon": hit["lon"],
                    "source": "offline", "detail": "离线城市库"}
        return {"ok": False,
                "error": f"没找到「{name}」：可以换个写法，或直接手填经纬度"}

    def _photo_exif(self, path: Path) -> dict:
        """读 EXIF：拍摄时间、设备、镜头、焦段/光圈/快门/ISO、GPS。读不到就返回空。"""
        out = {}
        try:
            from PIL import Image
            from PIL.ExifTags import TAGS
            im = Image.open(path)
            exif = im.getexif()
            if not exif:
                return out
            ifd = exif.get_ifd(0x8769) or {}
            gps = exif.get_ifd(0x8825) or {}

            def _tag(t):
                return ifd.get(t) or exif.get(t)

            # 设备
            make = (exif.get(271) or "").strip()
            model = (exif.get(272) or "").strip()
            if model:
                out["camera"] = model if make in model else f"{make} {model}".strip()
            if exif.get(42036):
                out["lens"] = str(exif.get(42036)).strip()

            # 拍摄时间
            dt = _tag(36867) or exif.get(306)
            if dt:
                try:
                    out["taken"] = datetime.strptime(str(dt), "%Y:%m:%d %H:%M:%S")
                except Exception:
                    pass

            # 参数
            fno = _tag(33437)
            if fno:
                try:
                    out["aperture"] = f"f/{float(fno):g}"
                except Exception:
                    pass
            et = _tag(33434)
            if et:
                try:
                    v = float(et)
                    if v and v < 1:
                        inv = 1 / v
                        inv = 8000 if inv >= 8000 else int(round(inv / 10) * 10) if inv >= 100 else int(round(inv))
                        out["shutter"] = f"1/{inv}s"
                    else:
                        out["shutter"] = f"{v:g}s"
                except Exception:
                    pass
            iso = _tag(34855)
            if iso:
                out["iso"] = f"ISO {iso}"
            fl = _tag(37386)
            if fl:
                try:
                    out["focal"] = f"{float(fl):.0f}mm"
                except Exception:
                    pass

            # GPS → 十进制坐标
            if gps:
                def _dms(v):
                    try:
                        d, m, sec = [float(x) for x in v]
                        return d + m / 60 + sec / 3600
                    except Exception:
                        return None
                lat, lon = _dms(gps.get(2)), _dms(gps.get(4))
                if lat is not None and lon is not None:
                    if str(gps.get(1, "N")).upper().startswith("S"):
                        lat = -lat
                    if str(gps.get(3, "E")).upper().startswith("W"):
                        lon = -lon
                    out["gps"] = (lat, lon)
        except Exception as e:
            _LOGGER.debug("EXIF 读取失败: %s", e)
        return out

    # ── 照片卡：等比裁切铺满 + 底部信息带（可选） ──
    def compose_photo(self, photo_path: Path) -> Path:
        """把照片放进 528×792 画布。开了 overlay 就在底部压一条白色信息带。

        裁切用 cover（等比放大到铺满，再居中裁），比 contain 留黑边更像"相框"。
        """
        from PIL import Image, ImageDraw, ImageOps

        W, H = CARD_W, CARD_H
        if not self.cfg.get("photo_overlay", True):
            # 不需要信息带时也统一到卡片尺寸，免得交给上游自己猜
            img = Image.open(photo_path).convert("RGB")
            img = _cover(img, W, H)
            out = self.cards_dir / "photo.png"
            img.save(out)
            return out

        meta = self._photo_exif(photo_path)

        # 一点拍摄信息都没有：不留信息带，照片铺满整张卡（这条是用户定的规则，勿删）
        _has_meta = bool(meta.get("taken") or meta.get("camera") or meta.get("lens")
                         or meta.get("gps") or meta.get("iso") or meta.get("focal")
                         or meta.get("aperture") or meta.get("shutter"))
        if not _has_meta:
            canvas = Image.new("RGB", (W, H), WHITE)
            src = Image.open(photo_path)
            src = ImageOps.exif_transpose(src).convert("RGB")
            canvas.paste(_cover(src, W, H), (0, 0))
            out = self.cards_dir / "photo.png"
            canvas.save(out)
            return out

        # 先决定信息带要有几行：没有的行就不画，整条带子跟着缩，不拿占位文字凑数
        gps = meta.get("gps")
        place = self.nearest_city(gps[0], gps[1]) if gps else ""
        if not place and gps:
            place = f"{gps[0]:.2f}, {gps[1]:.2f}"

        line2_parts = [x for x in (place, meta.get("camera"), meta.get("lens")) if x]
        params = [meta.get(k) for k in ("focal", "aperture", "shutter", "iso")]
        params = [x for x in params if x]

        extra = []
        if line2_parts:
            extra.append(" · ".join(line2_parts))
        if params:
            extra.append("  ".join(params))

        # 天气放在信息带右侧，补上时间那一行的空白
        wx_txt = ""
        try:
            wd = self.weather_data() or {}
            if wd.get("temp") is not None:
                wx_txt = f"{wd['temp']:.0f}° {wd.get('text','')}".strip()
        except Exception:
            wx_txt = ""


        # 信息带：左右边距、字号、行距跟 card_render 的版式规范走（M / 行距 24）
        MX = R.M
        date_f = R.font("bold", 22)
        meta_f = R.font("light", 16)
        time_f = R.font("num", 22)
        _heights = [22] + [16] * len(extra)
        _gap = 6
        _content = sum(_heights) + _gap * (len(_heights) - 1)
        _pad = 14
        band = _content + _pad * 2
        photo_h = H - band
        canvas = Image.new("RGB", (W, H), WHITE)
        src = Image.open(photo_path)
        src = ImageOps.exif_transpose(src).convert("RGB")
        canvas.paste(_cover(src, W, photo_h), (0, 0))

        d = ImageDraw.Draw(canvas)
        d.rectangle([0, photo_h, W, H], fill=WHITE)
        d.line([0, photo_h, W, photo_h], fill=BLACK, width=3)

        try:
            _fallback_ts = datetime.fromtimestamp(photo_path.stat().st_mtime)
        except Exception:
            _fallback_ts = datetime.now()
        taken = meta.get("taken") or _fallback_ts
        _date_txt = (f"{taken.year}年{taken.month}月{taken.day}日"
                     if taken.year != datetime.now().year
                     else f"{taken.month}月{taken.day}日")

        y0 = photo_h + _pad
        d.text((MX, y0), f"{_date_txt} {WEEKDAYS[taken.weekday()]}", font=date_f, fill=BLACK)
        tm = taken.strftime("%H:%M")
        d.text((W - MX - d.textlength(tm, font=time_f), y0), tm, font=time_f, fill=BLACK)

        yy = y0 + _heights[0] + _gap
        for i, line in enumerate(extra):
            if line:
                d.text((MX, yy), line, font=meta_f, fill=BLACK)
            if i == 0 and wx_txt and extra:
                d.text((W - MX - d.textlength(wx_txt, font=meta_f), yy),
                       wx_txt, font=meta_f, fill=BLACK)
            yy += _heights[i + 1] + _gap

        # 照片区继续抖动，信息带吸附成 6 色（否则白底上会飘彩色噪点）
        canvas = R.snap_palette(canvas, keep=[(0, 0, W, photo_h)])
        out = self.cards_dir / "photo.png"
        canvas.save(out)
        return out

    # ── 充电统计（读现有 port_history.db，只做只读查询） ──
    def charging_stats(self, days_ago: int = 1) -> list[dict]:
        db = self.data_dir / "port_history.db"
        if not db.exists():
            return []
        start = (datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
                 - timedelta(days=days_ago))
        end = start + timedelta(days=1)
        try:
            conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
            rows = conn.execute(
                "SELECT port, SUM(total_wh), MAX(peak_power_w), COUNT(*)"
                " FROM charge_sessions WHERE end_time IS NOT NULL"
                " AND end_time >= ? AND end_time < ? GROUP BY port ORDER BY port",
                (start.timestamp(), end.timestamp())).fetchall()
            conn.close()
        except Exception as e:
            _LOGGER.warning("读充电统计失败: %s", e)
            return []
        labels = {1: "C1", 2: "C2", 3: "C3", 4: "USB-A"}
        out = []
        for port, wh, peak, cnt in rows:
            if not wh:
                continue
            out.append({"port": labels.get(port, f"P{port}"), "wh": round(wh or 0, 1),
                        "peak": round(peak or 0), "count": cnt})
        return out

    # ── 渲染 ──
    def render_summary(self) -> Path:
        """海报版每日总结：交给 card_render 统一排版。"""
        _sc = float(self.cfg.get("scale", 1.1) or 1.1)
        R.set_scale(_sc * (1.1 if self.cfg.get("theme") == "wo" else 1.0))
        ctx = {
            "now": datetime.now(),
            "todos": self.cfg.get("todos") or [],
            "quote": self.daily_quote(),
            "weather": self.weather_data(),
        }
        img = R.render_summary(ctx)
        img = R.snap_palette(img)
        if self.cfg.get("theme") == "wo":
            img = R.to_dark_hud(img)
        # 日历/自定义这类"表格型"版面本来就有固定节奏，均摊间距反而会把
        # 周条和页脚挤到一起，所以不对它们做纵向均摊。
        if self.cfg.get("fill", True):
            img = R.distribute_vertical(img, max_gap=int(64 * float(self.cfg.get("line_gap", 1.1) or 1.1)))
        out = self.cards_dir / "summary.png"
        img.save(out)
        return out

    def render_placeholder(self, text: str) -> Path:
        """没有照片时的兜底卡，避免定时任务静默失败。走 card_render 的版式规范。"""
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (CARD_W, CARD_H), WHITE)
        d = ImageDraw.Draw(img)
        d.rectangle([0, 0, CARD_W, R.Y_RULE], fill=BLACK)
        d.text((R.M, R.Y_HEAD), "土豆片", font=_font("bold", R.FS["title"]), fill=WHITE)
        yy = R.Y_BODY
        for line in str(text).split("\n"):
            if not line.strip():
                continue
            d.text((R.M, yy), line.strip(), font=_font("bold", 26), fill=BLACK)
            yy += 44
        img = R.snap_palette(img)
        out = self.cards_dir / "placeholder.png"
        img.save(out)
        return out

    # ── 数据源：天气 / 每日一句 / Bing 壁纸 / AI 生图 ──
    def weather_data(self) -> dict:
        """Open-Meteo：当前 + 未来 4 小时 + 空气质量。缓存 15 分钟。"""
        w = self.cfg.get("weather") or {}
        if not w.get("enabled", True):
            return {}
        now = time.time()
        if now - self._wx_cache.get("ts", 0) < 900 and self._wx_cache.get("data"):
            return self._wx_cache["data"]
        lat, lon = w.get("lat", 39.9042), w.get("lon", 116.4074)
        data = {"city": w.get("name") or "", "clock": datetime.now().strftime("%H:%M")}
        try:
            url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                   "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                   "weather_code,wind_speed_10m,wind_direction_10m"
                   "&hourly=temperature_2m,weather_code"
                   "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,"
                   "weather_code,precipitation_probability_max"
                   "&timezone=auto&forecast_days=4"
                   % (lat, lon))
            with urllib.request.urlopen(url, timeout=8) as r:
                d = json.loads(r.read().decode())
            cur = d.get("current") or {}
            daily = d.get("daily") or {}
            data.update(
                temp=cur.get("temperature_2m"),
                hum=cur.get("relative_humidity_2m"),
                feels=cur.get("apparent_temperature"),
                wind=cur.get("wind_speed_10m"),
                code=cur.get("weather_code"),
                text=_wx_text(cur.get("weather_code")),
                hi=(daily.get("temperature_2m_max") or [None])[0],
                lo=(daily.get("temperature_2m_min") or [None])[0],
                sunrise=str((daily.get("sunrise") or [""])[0])[11:16],
                sunset=str((daily.get("sunset") or [""])[0])[11:16],
                precip=(daily.get("precipitation_probability_max") or [None])[0],
                wind_text=_wind_text(cur.get("wind_direction_10m"), cur.get("wind_speed_10m")),
            )
            # 未来 3 天（今天/明天/后天）：色块天气卡要用
            days = daily.get("time") or []
            his = daily.get("temperature_2m_max") or []
            los = daily.get("temperature_2m_min") or []
            codes = daily.get("weather_code") or []
            labels = ("今天", "明天", "后天")
            daily3 = []
            for i in range(min(3, len(days))):
                try:
                    dt = datetime.strptime(str(days[i])[:10], "%Y-%m-%d")
                except Exception:
                    dt = None
                daily3.append({
                    "label": labels[i],
                    "date": f"{dt.month}.{dt.day}" if dt else "",
                    "hi": round(his[i]) if i < len(his) and his[i] is not None else None,
                    "lo": round(los[i]) if i < len(los) and los[i] is not None else None,
                    "text": _wx_text(codes[i]) if i < len(codes) else "",
                })
            data["daily3"] = daily3
            # 未来 4 个整点
            hours = d.get("hourly") or {}
            times = hours.get("time") or []
            temps = hours.get("temperature_2m") or []
            codes = hours.get("weather_code") or []
            cur_hour = datetime.now().strftime("%Y-%m-%dT%H")
            start = 0
            for i, t in enumerate(times):
                if str(t)[:13] >= cur_hour:
                    start = i + 1
                    break
            hourly = []
            for i in range(start, min(start + 4, len(times))):
                hourly.append({"t": str(times[i])[11:16],
                               "temp": round(temps[i]) if i < len(temps) and temps[i] is not None else None,
                               "text": _wx_text(codes[i]) if i < len(codes) else ""})
            data["hourly"] = hourly
        except Exception as e:
            _LOGGER.warning("天气获取失败: %s", e)
        try:
            aq = ("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%s&longitude=%s"
                  "&current=us_aqi&timezone=auto" % (lat, lon))
            with urllib.request.urlopen(aq, timeout=8) as r:
                d2 = json.loads(r.read().decode())
            v = (d2.get("current") or {}).get("us_aqi")
            if v is not None:
                data["aqi"] = int(round(v))
        except Exception as e:
            _LOGGER.debug("空气质量获取失败: %s", e)
        # 失败不缓存：否则一次网络抖动会让"天气获取失败"在页面上挂 15 分钟
        if data.get("temp") is not None:
            self._wx_cache = {"ts": now, "data": data}
        return data

    def daily_quote(self, force: bool = False) -> dict:
        """hitokoto 每日一句：当天缓存一次，失败用本地兜底句。"""
        today = datetime.now().strftime("%Y-%m-%d")
        if not force and self._quote_cache.get("date") == today and self._quote_cache.get("data"):
            return self._quote_cache["data"]
        data = None
        try:
            req = urllib.request.Request(
                "https://v1.hitokoto.cn/?encode=json&max_length=40",
                headers={"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                       "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"})
            with urllib.request.urlopen(req, timeout=6) as r:
                j = json.loads(r.read().decode())
            text = (j.get("hitokoto") or "").strip()
            src = (j.get("from") or "").strip()
            if text:
                data = {"text": text, "from": src, "date": today}
        except Exception as e:
            _LOGGER.debug("每日一句获取失败: %s", e)
        if not data:
            # 兜底句不写缓存：下一次调用会重新请求，网络恢复后自动变成真·每日一句
            return {"text": "把今天过好，就是对明天最好的准备。", "from": "本地兜底",
                    "date": today}
        self._quote_cache = {"date": today, "data": data}
        return data

    def daily_poem(self, force: bool = False) -> dict:
        """每日诗词（今日诗词 API），当天缓存一次。"""
        today = datetime.now().strftime("%Y-%m-%d")
        if not force and self._poem_cache.get("date") == today and self._poem_cache.get("data"):
            return self._poem_cache["data"]
        data = {"title": "今日诗词", "dynasty": "", "author": "",
                "lines": ["今天也要好好生活。"]}
        try:
            req = urllib.request.Request(
                "https://v2.jinrishici.com/one.json",
                headers={"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"})
            with urllib.request.urlopen(req, timeout=8) as r:
                j = json.loads(r.read().decode())
            d = j.get("data") or {}
            origin = d.get("origin") or {}
            lines = origin.get("content") or []
            if not lines and d.get("content"):
                lines = [x.strip() for x in str(d["content"]).replace("！", "！\n").split("\n") if x.strip()]
            data = {"title": origin.get("title") or "今日诗词",
                    "dynasty": origin.get("dynasty") or "",
                    "author": origin.get("author") or "",
                    "lines": lines[:4]}
        except Exception as e:
            _LOGGER.debug("诗词获取失败: %s", e)
        else:
            self._poem_cache = {"date": today, "data": data}
        return data

    def _ai_text(self, prompt: str, timeout: int = 25) -> str:
        """免费文本生成（Pollinations 文本接口），失败返回空串。"""
        import urllib.parse
        try:
            url = "https://text.pollinations.ai/" + urllib.parse.quote(prompt[:800])
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                txt = r.read().decode("utf-8", "ignore").strip()
            # 去掉可能出现的 markdown 修饰
            return txt.replace("**", "").replace("“", "").replace("”", "").strip()
        except Exception as e:
            _LOGGER.debug("文本生成失败: %s", e)
            return ""

    def _note_via_api(self, prompt: str) -> str:
        """用 OpenAI 兼容的 chat/completions 生成赏析；没配 key 就返回空。"""
        cfg = self.cfg.get("poem_ai") or {}
        endpoint = (cfg.get("endpoint") or "").strip() or "https://api.deepseek.com/v1"
        key = (cfg.get("api_key") or "").strip()
        if not (cfg.get("enabled", True) and key):
            return ""
        url = endpoint if endpoint.endswith("/chat/completions") else endpoint.rstrip("/") + "/chat/completions"
        body = json.dumps({
            "model": cfg.get("model") or "deepseek-chat",
            "messages": [
                {"role": "system", "content": (
                    "你是一名中学语文教材的诗词注释编写者。要求：\n"
                    "1. 语言平实、准确，像课本注释，不抒情、不排比、不用感叹句；\n"
                    "2. 按「写的什么景/事 → 用了什么手法 → 表达什么情感」三层说清；\n"
                    "3. 70~110 字，不分点、不加小标题、不用引号；\n"
                    "4. 不评价诗人成就，不写赏析套话（如“读来令人…”）。")},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.3,
            "max_tokens": 220,
        }).encode()
        try:
            req = urllib.request.Request(url, data=body, headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {key}",
            })
            with urllib.request.urlopen(req, timeout=25) as r:
                j = json.loads(r.read().decode())
            txt = (j["choices"][0]["message"]["content"] or "").strip()
            return txt[:400]
        except Exception as e:
            _LOGGER.debug("赏析 API 失败: %s", e)
            return ""

    def poem_note(self, poem: dict) -> str:
        """给诗词配一段白话赏析：按标题缓存，AI 生成失败则用模板兜底。"""
        title = (poem.get("title") or "").strip()
        if not title:
            return ""
        _ai = self.cfg.get("poem_ai") or {}
        _api_ready = bool(_ai.get("api_key"))     # endpoint 有默认值，key 才是开关
        _ck = f"v2:{title}"
        cached = self._note_cache.get(_ck)
        # 模板兜底的句子以「《」开头：配好 API 之后不能拿它当缓存命中，
        # 否则永远看不到 AI 写的内容（这正是"填了 key 还是老文案"的原因）。
        if cached and not (_api_ready and cached.startswith("《")):
            return cached

        first = (poem.get("lines") or [""])[0]
        prompt = (f"为中学课本写一段赏析，70~110 字，平实准确，不抒情、不分点。\n"
                  f"《{title}》{poem.get('dynasty','')}{poem.get('author','')}\n"
                  f"原文：{'／'.join((poem.get('lines') or [])[:4]) or first}")
        note = self._note_via_api(prompt) or self._ai_text(prompt)
        if len(note) < 20:
            note = ""

        if not note:
            # 兜底：不假装文学评论，只给可靠的背景信息
            n = len(poem.get("lines") or [])
            note = (f"《{title}》{poem.get('dynasty','')}{poem.get('author','')}作，"
                    f"共 {n} 句。以“{first[:12]}”起笔，写景入情，"
                    f"读时不妨慢一点，让画面自己浮出来。")
        if not note.startswith("《"):        # 只缓存真内容，模板兜底不落盘
            self._note_cache[_ck] = note
            try:
                (self.data_dir / "poem_notes.json").write_text(
                    json.dumps(self._note_cache, ensure_ascii=False, indent=2), encoding="utf-8")
            except Exception:
                pass
        return note

    def bing_wallpaper(self, idx: int | None = None) -> Path | None:
        """Bing 每日壁纸：抓 8 张候选，缓存到 data/bing/。"""
        if not self.cfg.get("bing_enabled", True):
            return None
        cache_dir = self.data_dir / "bing"
        cache_dir.mkdir(parents=True, exist_ok=True)
        day = datetime.now().strftime("%Y%m%d")
        if idx is None:
            idx = int(self.cfg.get("bing_idx", 0)) % 8
        target = cache_dir / f"bing_{day}_{idx}.jpg"
        if target.exists() and target.stat().st_size > 10000:
            return target
        try:
            api = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=%d&n=1&mkt=zh-CN" % idx
            with urllib.request.urlopen(api, timeout=8) as r:
                j = json.loads(r.read().decode())
            img = (j.get("images") or [{}])[0]
            base = img.get("urlbase")
            if not base:
                return None
            url = "https://www.bing.com" + base + "_1920x1080.jpg"
            with urllib.request.urlopen(url, timeout=15) as r:
                blob = r.read()
            if len(blob) < 10000:
                return None
            target.write_bytes(blob)
            self.cfg["bing_copyright"] = img.get("copyright", "")
            self._save_cfg()
            _LOGGER.info("Bing 壁纸已缓存: %s", target.name)
            return target
        except Exception as e:
            _LOGGER.warning("Bing 壁纸获取失败: %s", e)
            return None

    def ai_prompt(self, custom: str = "") -> str:
        p = (custom or self.cfg.get("ai_prompt") or "").strip()
        return p or random.choice(AI_PROMPTS)

    def ai_generate(self, prompt: str):
        """调 OpenAI 兼容的 /images/generations，返回 (图片路径, 错误)。

        没有配置接口时返回 (None, None)：调用方据此走本地海报，而不是假装生成。
        兼容两种返回：b64_json（OpenAI gpt-image-1）和 url（大多数国产兼容服务）。
        """
        cfg = self.cfg.get("ai") or {}
        endpoint = (cfg.get("endpoint") or "").strip() or "https://api.deepseek.com/v1".rstrip("/")
        key = (cfg.get("api_key") or "").strip()
        if not endpoint or not key:
            if not (cfg.get("free", True)):
                return None, None
            return self._ai_free(prompt)
        if not prompt.strip():
            return None, "提示词是空的"
        body = {"model": cfg.get("model") or "gpt-image-1",
                "prompt": prompt, "n": 1,
                "size": cfg.get("size") or "1024x1024"}
        data = json.dumps(body).encode()
        req = urllib.request.Request(endpoint + "/images/generations", data=data,
                                     headers={"Content-Type": "application/json",
                                              "Authorization": f"Bearer {key}",
                                              "User-Agent": "todoocard/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                payload = r.read()
            j = json.loads(payload.decode())
        except urllib.error.HTTPError as e:
            detail = ""
            try:
                detail = e.read().decode()[:400]
            except Exception:
                pass
            _LOGGER.warning("AI 生图 HTTP %s: %s", e.code, detail)
            return None, f"接口返回 HTTP {e.code}：{detail or e.reason}"
        except Exception as e:
            _LOGGER.warning("AI 生图失败: %s", e)
            return None, f"请求失败：{e}"

        item = (j.get("data") or [{}])[0] if isinstance(j, dict) else {}
        out = self.data_dir / "ai"
        out.mkdir(parents=True, exist_ok=True)
        target = out / f"ai_{int(time.time())}.png"
        try:
            if item.get("b64_json"):
                import base64
                target.write_bytes(base64.b64decode(item["b64_json"]))
            elif item.get("url"):
                with urllib.request.urlopen(item["url"], timeout=60) as r2:
                    target.write_bytes(r2.read())
            else:
                return None, f"接口没返回图片：{str(j)[:200]}"
        except Exception as e:
            return None, f"图片保存失败：{e}"

        self._ai_last = {"ts": int(time.time()), "path": str(target), "prompt": prompt}
        self._save_cfg()
        _LOGGER.info("AI 生图完成: %s", target.name)
        return target, None

    def _ai_free(self, prompt: str):
        """没填 Key 时的免费出图：Pollinations（Flux/Sana 模型，无需注册）。

        这是真的模型出图，不是本地海报；失败才回退海报，并把原因带回去。
        """
        import urllib.parse
        url = ("https://image.pollinations.ai/prompt/"
               + urllib.parse.quote(prompt[:400])
               + "?width=768&height=1024&nologo=true&model=flux&seed=%d" % random.randint(1, 999999))
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"})
            with urllib.request.urlopen(req, timeout=150) as r:
                blob = r.read()
            if len(blob) < 5000:
                return None, "免费出图返回内容太小，可能被限流"
            out = self.data_dir / "ai"
            out.mkdir(parents=True, exist_ok=True)
            target = out / f"ai_{int(time.time())}.jpg"
            target.write_bytes(blob)
            self._ai_last = {"ts": int(time.time()), "path": str(target),
                             "prompt": prompt, "mode": "free"}
            self.cfg.setdefault("ai", {})["last"] = self._ai_last
            self._save_cfg()
            _LOGGER.info("免费 AI 出图完成: %s", target.name)
            return target, None
        except Exception as e:
            _LOGGER.warning("免费出图失败: %s", e)
            return None, f"免费出图失败：{e}"

    # ── 统一出图 ──
    def build_card(self, kind: str, consume: bool = True, fresh: bool = False):
        """按模板出图，返回 (路径, 展示名)。"""
        _sc = float(self.cfg.get("scale", 1.1) or 1.1)
        # 深色 HUD 下抗锯齿会被二值化吃掉一圈，字号整体再放大 10% 才看得清
        R.set_scale(_sc * (1.1 if self.cfg.get("theme") == "wo" else 1.0))
        if kind == "photo":
            # 预览和发送必须是同一张：钉住，成功后再换
            pin = getattr(self, "_photo_pin", None)
            if fresh or not pin or not Path(pin).exists():
                pin = self.pick_photo(consume=False)
                self._photo_pin = pin
            photo = pin
            if not photo:
                raise RuntimeError("照片文件夹是空的（在网页里添加目录）")
            return self.compose_photo(Path(photo)), Path(photo).name
        if kind == "bing":
            wp = self.bing_wallpaper(random.randint(0, 7) if fresh else None)
            if not wp:
                raise RuntimeError("Bing 壁纸还没取到（检查网络或稍后再试）")
            return self.compose_photo(wp), "Bing 壁纸"
        if kind == "summary":
            return self.render_summary(), "每日总结"

        ctx = {
            "now": datetime.now(),
            "todos": self.cfg.get("todos") or [],
            "quote": self.daily_quote(force=fresh),
            "energy": self.charging_stats(1),
            "energy_today": self.charging_stats(0),
            "weather": self.weather_data(),
        }
        if kind == "weather":
            img = R.render_weather(ctx)
        elif kind == "weather2":
            img = R.render_weather_blocks(ctx)
        elif kind == "progress":
            img = R.render_progress(ctx)
        elif kind == "energy":
            img = R.render_energy(ctx)
        elif kind == "todo":
            img = R.render_todo(ctx)
        elif kind == "quote":
            img = R.render_quote(ctx)
        elif kind == "calendar":
            img = R.render_calendar(ctx)
        elif kind == "countdown":
            ctx["countdowns"] = self.cfg.get("countdowns") or []
            img = R.render_countdown(ctx)
        elif kind == "poem":
            ctx["poem"] = self.daily_poem(force=fresh)
            ctx["note"] = (self.cfg.get("poem_note") or "").strip() or self.poem_note(ctx["poem"])
            img = R.render_poem(ctx)
        elif kind == "custom":
            ccfg = self.cfg.get("custom") or {}
            ctx["custom"] = ccfg
            if any(b.get("type") == "image" for b in (ccfg.get("blocks") or [])):
                photo = self.pick_photo(consume=False)
                ctx["image_path"] = photo or str(self.bing_wallpaper() or "") or None
            img = R.render_custom(ctx)
        elif kind == "ai":
            prompt = self.ai_prompt()
            cached = self._ai_last or {}
            reuse = (cached.get("path") and Path(cached["path"]).exists()
                     and time.time() - cached.get("ts", 0) < 900)
            if fresh:
                image, err = self.ai_generate(prompt)
            elif reuse:
                image, err = Path(cached["path"]), None
                prompt = cached.get("prompt") or prompt
            else:
                # 没生成过就只画本地海报，绝不因为"点开预览"就偷偷烧一次生成
                image, err = None, None
            ctx["ai"] = {"prompt": prompt, "image": image}
            img = R.render_ai(ctx)
        else:
            raise RuntimeError(f"未知模板: {kind}")
        # 吸附调色板：去掉抗锯齿灰边，避免抖动后白底出现彩色噪点（版式不变）
        if kind not in ("photo", "bing", "ai"):
            img = R.snap_palette(img)
        if self.cfg.get("theme") == "wo" and kind not in ("photo", "bing", "ai"):
            img = R.to_dark_hud(img)
        # 日历/自定义这类"表格型"版面本来就有固定节奏，均摊间距反而会把
        # 周条和页脚挤到一起，所以不对它们做纵向均摊。
        if self.cfg.get("fill", True) and kind not in ("calendar", "custom"):
            img = R.distribute_vertical(img, max_gap=int(64 * float(self.cfg.get("line_gap", 1.1) or 1.1)))
        out = self.cards_dir / f"{kind}.png"
        img.save(out)
        return out, TEMPLATES.get(kind, kind)

    # ── 工具链 ──
    def _ensure_bins(self):
        """按源码 mtime 编译一次 Swift 工具，之后直接复用（每次重编译要 1~2 分钟）。"""
        sender = self.bin_dir / "mac-image-transfer"
        conv = self.bin_dir / "image-to-payload"
        src_sender = self.skill_dir / "mac_image_transfer.swift"
        src_conv = self.skill_dir / "image_to_payload.swift"
        if not sender.exists() or sender.stat().st_mtime < src_sender.stat().st_mtime:
            subprocess.run(["bash", str(self.skill_dir / "build_sender.sh"), str(sender)],
                           check=True, capture_output=True, timeout=300)
        if not conv.exists() or conv.stat().st_mtime < src_conv.stat().st_mtime:
            subprocess.run(["bash", str(self.skill_dir / "build_converter.sh"), str(conv)],
                           check=True, capture_output=True, timeout=300)
        return sender, conv

    def _build_payload(self, image_path: Path) -> dict:
        if sys.platform != "darwin":
            return self._build_payload_python(image_path)
        sender, conv = self._ensure_bins()
        work = self.cards_dir / "work"
        if work.exists():
            shutil.rmtree(work)
        work.mkdir(parents=True)
        norm = work / "norm.png"
        subprocess.run(["/usr/bin/sips", "-s", "format", "png", str(image_path), "--out", str(norm)],
                       check=True, capture_output=True, timeout=120)
        prefix = str(work / "img")
        r = subprocess.run([str(conv), "--input", str(norm), "--target", "t3",
                            "--orientation", "todoocard-correct", "--output-prefix", prefix],
                           capture_output=True, text=True, timeout=180)
        if r.returncode != 0:
            raise RuntimeError(f"图片转换失败: {r.stderr[-400:] or r.stdout[-400:]}")
        qlz = f"{prefix}.protocol.qlz"
        r = subprocess.run([sys.executable, str(self.skill_dir / "quicklz_payload.py"),
                            "--input", f"{prefix}.controller.bin", "--output", qlz],
                           capture_output=True, text=True, timeout=120)
        if r.returncode != 0:
            raise RuntimeError(f"QuickLZ 打包失败: {r.stderr[-400:]}")
        subprocess.run([sys.executable, str(self.skill_dir / "validate_payload.py"),
                        "--expected-raw", f"{prefix}.controller.bin",
                        f"{prefix}.controller.bin", qlz, f"{prefix}.protocol.legacy.qlz"],
                       check=True, capture_output=True, timeout=120)
        return {"payload": qlz, "legacy": f"{prefix}.protocol.legacy.qlz",
                "raw": f"{prefix}.controller.bin", "sender": str(sender)}

    def _build_payload_python(self, image_path: Path) -> dict:
        """Windows / Linux：payload.py 生成的产物与 Swift 工具逐字节一致（已 md5 对齐）。"""
        import payload as P
        work = self.cards_dir / "work"
        if work.exists():
            shutil.rmtree(work)
        work.mkdir(parents=True)
        arts = P.build(Path(image_path), work / "img")
        qlz = work / "img.protocol.qlz"
        import quicklz_payload
        raw = arts["controller"].read_bytes()
        qlz.write_bytes(quicklz_payload.build_level3_payload(raw))
        return {"payload": qlz, "legacy": arts["legacy"], "raw": arts["controller"],
                "sender": None}

    def _wait_for_card(self, timeout: float) -> bool:
        """先自己扫，扫到再交给发送器——卡片平时不广播，直接发必然扑空。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self._scan_card():
                return True
        return False

    def _send_blocking(self, image_path: Path, wait_sec: float | None = None) -> dict:
        if sys.platform != "darwin":
            return self._send_python(image_path, wait_sec)
        return self._send_macos(image_path, wait_sec)

    def _send_python(self, image_path: Path, wait_sec: float | None = None) -> dict:
        """Windows / Linux：payload.py + sender.py（bleak）。

        返回字段必须和 _send_macos 一致（ok / output / seconds），
        否则 send() 记录历史时会 KeyError('output')，即使卡片已经刷成功也会显示"失败"。
        """
        import asyncio as _aio
        import sender as S
        t0 = time.time()
        lines: list[str] = []

        def _log(msg: str) -> None:
            lines.append(str(msg))
            _LOGGER.info("%s", msg)

        try:
            art = self._build_payload_python(image_path)
            # 等卡片广播的总时长（卡片自己会广播，不要求用户按键）
            wait_s = float(wait_sec or self.cfg.get("wait_card_sec", 180) or 180)
            res = _aio.run(S.send(art["payload"], self.cfg.get("device_id") or None,
                                  log=_log, timeout=wait_s))
            out = "\n".join(lines)
            return {"ok": bool(res.get("ok")), "acked": res.get("acked", True),
                    "output": out or str(res.get("message") or ""),
                    "seconds": round(time.time() - t0, 1),
                    "message": str(res.get("message") or "")}
        except Exception as e:
            msg = str(e)
            out = "\n".join([*lines, msg]).strip()
            _LOGGER.warning("发送失败: %s", msg)
            return {"ok": False, "output": out, "seconds": round(time.time() - t0, 1),
                    "message": msg}

    def _send_macos(self, image_path: Path, wait_sec: float | None = None) -> dict:
        started = time.time()
        dev = (self.cfg.get("device_id") or "").strip()
        if not dev:
            raise RuntimeError("还没指定土豆片的设备 UUID，先在网页里点「扫描设备」")
        if wait_sec is None:
            wait_sec = float(self.cfg.get("wait_card_sec", 120))
        # 不用自己的扫描判断"在不在"：直接让上游发送器去扫（它和固件对齐），
        # 扫不到就等几秒重来，直到超时——不依赖我这边对广播包的解析。
        deadline = time.time() + wait_sec
        # 先 probe 一次：实测"直接连上去写"偶尔会卡在
        # "Timed out preparing the verified TodooCard GATT connection"，
        # 而 probe 先把加密链路和服务缓存热起来，紧接着再传就稳了。
        # 地址可能轮换：发送前先按名字/地址重新发现一次，拿到当前 UUID
        try:
            found = self._scan_card()
            if found and found.get("addr"):
                dev = found["addr"]
        except Exception:
            pass
        art = self._build_payload(image_path)
        try:
            subprocess.run([art["sender"], "--probe", "--id", dev],
                           capture_output=True, text=True, timeout=90)
        except Exception:
            pass
        cmd = [art["sender"], "--payload", art["payload"], "--legacy-payload", art["legacy"],
               "--raw-payload", art["raw"], "--compressed", "--id", dev]
        out = ""
        attempts = 0
        while True:
            attempts += 1
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=420)
            out += (r.stdout or "") + (r.stderr or "")
            unconfirmed = "required 0x05/0x08 refresh acknowledgement" in out
            ok = r.returncode == 0 or unconfirmed
            if ok:
                break
            # 只对"扫不到卡片"重试：配对/固件错误重试也没用
            if "timed out scanning" not in out.lower() or time.time() >= deadline - 40:
                break
            time.sleep(5)
        return {"ok": ok, "output": out[-1600:], "seconds": round(time.time() - started, 1),
                "attempts": attempts}

    # ── 对外动作 ──
    async def send(self, kind: str = "summary", requeue: bool = True,
                   wait_sec: float | None = None) -> dict:
        # 发送可能超过 30s 的 HTTP 请求上限（编译/传输/落盘确认），所以 API 侧是
        # "点了就返回、进度走轮询"，这里用 busy 标志挡住重复点击。
        async with self._lock:
            self._busy = True
            self._emit()
            try:
                image, label = self.build_card(kind, consume=True)
                async with BLE_GUARD:
                    res = await asyncio.get_running_loop().run_in_executor(
                        None, self._send_blocking, image, wait_sec)
                # 两个平台的发送器返回字段可能不完全一样，这里统一按"缺了也不炸"处理
                ok = bool(res.get("ok"))
                out_text = str(res.get("output") or res.get("message") or "")
                if ok:
                    msg = ("成功" if res.get("acked", True) is not False
                           else "已写入（没等到显影确认，画面可能已刷新）")
                else:
                    msg = _friendly_error(out_text)
                entry = {"ts": int(time.time()), "kind": kind, "file": label,
                         "ok": ok, "seconds": res.get("seconds", 0), "message": msg}
                if not ok and requeue and _retryable(out_text):
                    self._enqueue(kind)
                self._add_history(entry)
                self._emit()
                _LOGGER.info("土豆片推送 %s: %s (%.1fs)", kind, "成功" if ok else "失败",
                             res.get("seconds", 0))
                return {"ok": ok, **entry, "output": out_text}
            except Exception as e:
                entry = {"ts": int(time.time()), "kind": kind, "file": "",
                         "ok": False, "seconds": 0, "message": str(e)}
                self._add_history(entry)
                if requeue and _retryable(str(e)):
                    self._enqueue(kind)
                self._emit()
                _LOGGER.warning("土豆片推送失败: %s", e)
                return {"ok": False, **entry}
            finally:
                self._busy = False
                self._emit()

    async def preview(self, kind: str = "summary", fresh: bool = False) -> Path:
        try:
            return self.build_card(kind, consume=False, fresh=fresh)[0]
        except Exception:
            if kind in ("photo", "bing"):
                return self.render_placeholder("还没有可用的照片\n在网页里添加文件夹，或开启 Bing 壁纸")
            return self.render_placeholder(f"「{TEMPLATES.get(kind, kind)}」暂时生成失败")

    # ── 调度 ──
    async def _loop(self):
        """调度：到点跑一次；失败自动退避重试（墨水屏可能睡着，喊一次不一定醒）。

        重试策略：失败后 15 分钟再来，到 6 小时补跑窗口结束才放弃（≈24 次）。
        墨水屏平时可能不广播（省电），只在按键/上电后一段时间内可连——所以一次性
        推送必然经常扑空，必须允许整段时间里反复尝试。成功才写 last_runs，
        否则一次连接失败会把当天的任务永久跳过。
        """
        _LOGGER.info("土豆片调度器启动")
        self._last_tick = time.time()
        self._last_tick_text = "started"
        while not self._stop.is_set():
            try:
                if self.cfg.get("enabled") and not self.cfg.get("standby"):
                    await self._drain_outbox()
                    now = datetime.now()
                    runs = self.cfg.setdefault("last_runs", {})
                    retry = self.cfg.setdefault("retry", {})
                    for idx, item in enumerate(self.cfg.get("schedule") or []):
                        if not item.get("enabled", True):
                            continue
                        hh, mm = (int(x) for x in item["time"].split(":"))
                        due = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
                        if now < due:
                            continue
                        key = f"{item['time']}_{item['kind']}"
                        if int(runs.get(key, 0)) >= int(due.timestamp()):
                            continue                     # 今天已经成功过
                        # 补跑窗口 6 小时：Mac 睡眠唤醒后仍会补上，超过就当今天错过了
                        _window = max(1, int(self.cfg.get("catch_up_min", 30) or 30))
                        if now > due + timedelta(minutes=_window):
                            continue

                        rt = retry.get(key) or {}
                        if rt.get("next_ts", 0) > now.timestamp():
                            continue                     # 还在退避期内

                        if item["kind"] == "photo" and not self._scan_photos():
                            runs[key] = int(now.timestamp())
                            self._save_cfg()
                            _LOGGER.info("跳过照片任务：还没有配置照片文件夹")
                            continue

                        # 单次等待压到 45 秒：调度循环还在跑，失败交给重试队列，
                        # 不会因为一张卡连不上把后面几个计划全堵死。
                        res = await self.send(item["kind"], requeue=True, wait_sec=45)
                        attempts = int(rt.get("n", 0)) + 1
                        if res.get("ok"):
                            runs[key] = int(now.timestamp())
                            retry.pop(key, None)
                        elif attempts >= int(self.cfg.get("retry_max", 3) or 0):
                            runs[key] = int(now.timestamp())
                            retry.pop(key, None)
                            _LOGGER.warning("任务 %s 连续 %d 次失败（已过 6 小时补跑窗口），今天不再重试",
                                            key, attempts)
                        else:
                            retry[key] = {"n": attempts,
                                          "next_ts": int(now.timestamp())
                                          + max(1, int(self.cfg.get("retry_delay_min", 5) or 5)) * 60}
                            _LOGGER.info("任务 %s 第 %d 次失败，15 分钟后重试", key, attempts)
                        self._save_cfg()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                _LOGGER.warning("土豆片调度异常: %s", e)
            self._last_tick = time.time()
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=20)
            except asyncio.TimeoutError:
                pass

    def _purge_stale_retry(self):
        retry = self.cfg.get("retry") or {}
        keys = {f"{it['time']}_{it['kind']}" for it in (self.cfg.get("schedule") or [])}
        now = time.time()
        changed = False
        for k in list(retry):
            if k not in keys or int((retry.get(k) or {}).get("next_ts", 0)) < now - 3600:
                retry.pop(k, None); changed = True
        if changed:
            self.cfg["retry"] = retry
            self._save_cfg()

    def start(self):
        if self._task is None or self._task.done():
            self._stop.clear()
            self._purge_stale_retry()
        self._task = asyncio.create_task(self._loop(), name="card-scheduler")
        if self._pair_task is None or self._pair_task.done():
            self._pair_task = asyncio.create_task(self._pair_watch_loop(), name="card-pair-watch")

    async def stop(self):
        self._stop.set()
        for t in (self._task, self._pair_task):
            if t:
                t.cancel()
                try:
                    await t
                except (asyncio.CancelledError, Exception):
                    # CancelledError 继承自 BaseException，光写 except Exception 接不住，
                    # 退出时会甩一脸 traceback（正常停服不该报错）
                    pass

    def _emit(self):
        if not self.sse:
            return
        try:
            self.sse.emit("card", {"card": self.status()})
        except Exception:
            pass

    def status(self) -> dict:
        photos = self._scan_photos()
        return {
            "busy": self._busy,
            "enabled": bool(self.cfg.get("enabled")),
            "device_id": self.cfg.get("device_id"),
            "device_name": self.cfg.get("device_name"),
            "folders": self.cfg.get("folders") or [],
            "photo_mode": self.cfg.get("photo_mode", "shuffle"),
            "recursive": bool(self.cfg.get("recursive", True)),
            "scale": float(self.cfg.get("scale", 1.1) or 1.1),
            "fill": bool(self.cfg.get("fill", True)),
            "theme": self.cfg.get("theme", "light"),
            "poem_note": self.cfg.get("poem_note", ""),
            "poem_ai": {**{"enabled": True, "endpoint": "https://api.deepseek.com/v1",
                           "api_key": "", "model": "deepseek-chat"}, **(self.cfg.get("poem_ai") or {})},
            "standby": bool(self.cfg.get("standby")),
            "line_gap": float(self.cfg.get("line_gap", 1.1) or 1.1),
            "custom": self.cfg.get("custom") or {},
            "photo_overlay": bool(self.cfg.get("photo_overlay", True)),
            "photo_count": len(photos),
            "photos": photos[:200],
            "schedule": self.cfg.get("schedule") or [],
            "schedule_backup": self.cfg.get("schedule_backup") or [],
            "last_tick": int(self._last_tick or 0),
            "last_tick_text": self._last_tick_text,
            "todos": self.cfg.get("todos") or [],
            "weather": self.cfg.get("weather") or {},
            "history": self.history[-20:],
            "outbox": self.cfg.get("outbox") or [],
            "auto_pair": bool(self.cfg.get("auto_pair", True)),
            "bing_enabled": bool(self.cfg.get("bing_enabled", True)),
            "button_photo": bool(self.cfg.get("button_photo", False)),
            "button_cooldown": int(self.cfg.get("button_cooldown", 300)),
            "bing_copyright": self.cfg.get("bing_copyright", ""),
            "ai": {"endpoint": (self.cfg.get("ai") or {}).get("endpoint", ""),
                   "model": (self.cfg.get("ai") or {}).get("model", ""),
                   "api_key_set": bool((self.cfg.get("ai") or {}).get("api_key")),
                   "free": bool((self.cfg.get("ai") or {}).get("free", True)),
                   "mode": "api" if ((self.cfg.get("ai") or {}).get("endpoint")
                                     and (self.cfg.get("ai") or {}).get("api_key")) else "free"},
            "ai_prompt": self.cfg.get("ai_prompt", ""),
            "ai_last": self._ai_last or {},
            "templates": TEMPLATES,
            "countdowns": self.cfg.get("countdowns") or [],
            "quote": self.daily_quote(),
            "paired_ts": int(self.cfg.get("paired_ts", 0) or 0),
            "pair_log": self.cfg.get("pair_log", ""),
            "last": self.history[-1] if self.history else None,
        }


#: 内置提示词：点"随机"时从这里抽，也可以自己写
AI_PROMPTS = [
    "a quiet desk at night, warm lamp, mechanical keyboard, cinematic, soft shadows",
    "misty mountains at sunrise, ink-wash painting style, minimal, lots of negative space",
    "a single cup of coffee on a wooden table, morning light, film grain, top view",
    "cyberpunk alley in the rain, neon reflections, moody, cinematic wide shot",
    "a cat sleeping on a stack of books, warm afternoon light, cozy, shallow depth of field",
    "vintage travel poster of a coastal town, flat colors, bold typography space",
    "a bonsai tree on a windowsill, minimalist Japanese aesthetic, soft daylight",
    "abstract geometric composition, bauhaus, primary colors, clean lines",
    "a bicycle leaning on a brick wall, golden hour, 35mm photo, muted colors",
    "outer space above clouds, earth curvature, calm, gradient sky",
    "an open notebook with a fountain pen, warm desk lamp, macro, soft focus",
    "a small cabin in a snowy forest, twilight, warm window light, wide landscape",
    "line art of a mountain range, single continuous stroke, white background",
    "a bowl of ramen, steam, overhead shot, vivid colors, food photography",
]

TEMPLATES = {
    "summary": "每日总结",
    "todo": "今日待办",
    "weather": "天气看板",
    "weather2": "色块天气",
    "progress": "时间进度",
    "energy": "充电统计",
    "quote": "每日一句",
    "calendar": "月历",
    "countdown": "倒计时",
    "poem": "每日诗词",
    "photo": "照片轮播",
    "bing": "Bing 壁纸",
    "ai": "AI 生图",
    "custom": "自定义卡片",
}


def _retryable(text: str) -> bool:
    """连接类失败值得排队重试；"照片目录是空的"这种配置错误重试也没用。"""
    low = (text or "").lower()
    if any(k in low for k in ("peer removed pairing", "timed out connecting",
                              "not found", "no matching", "connection", "busy")):
        return True
    return False


def _friendly_error(out: str) -> str:
    """把发送器的报错翻译成能直接照做的中文提示。"""
    text = out or ""
    low = text.lower()
    # ── Windows / Linux（sender.py 用 bleak，抛的是中文）──
    if "没看到卡片广播" in text:
        return "没等到卡片广播：确认蓝牙开着、卡片有电、没被手机 App 连着；再点一次会继续等"
    if "没扫到卡片" in text:
        return "没扫到卡片：长按卡片背面按钮唤醒它，离电脑近一点，别被手机 App 连着"
    if "unreachable" in low or "disconnected" in low or "not connected" in low:
        return "发送途中链路断了（已连接但卡片不可达）：多半是卡片电量低或休眠了，靠近并充电后再发"
    if "could not start notify" in low or "start notify" in low:
        return "连上了但订阅通知失败（链路不稳）：把卡片靠近电脑、充电后重试"
    if "回执停滞" in text:
        return "传输中途卡片不回了（多半是缓存被灌满）：离近一点重试，连续失败把 data\\app.log 发出来"
    if "没找到控制点" in text or "没找到数据点" in text:
        return "卡片的蓝牙特征值和固件对不上（固件版本可能不同），把 data\\app.log 发出来"
    if "等不到 block size" in text:
        return "卡片没回应分块大小，重试一次；还不行把 data\\app.log 发出来"
    if "没收到显影确认" in text:
        return "数据写完但卡片没确认刷新，它可能中途休眠了；唤醒后再发一次"
    if "拒绝" in text:
        return "卡片拒绝了这次传输，重试一次；连续失败请把 data\\app.log 发出来"
    if "access is denied" in low or "拒绝访问" in text:
        return "蓝牙被系统拦了：确认卡片已在 Windows 蓝牙里配对，并关掉其它占用它的程序"
    if "winerror 2" in low or "找不到指定的文件" in text:
        return "缺文件（可能是解压不完整）：重新解压一次压缩包再试"
    if "bluetooth" in low and ("radio" in low or "adapter" in low or "is off" in low):
        return "系统蓝牙没开或适配器不可用：打开蓝牙再试"
    if "拒绝" in text or "错误" in text and "0x" in text:
        return text[-300:]
    # ── macOS / Swift 分支（英文）──
    if "peer removed pairing" in low:
        return "配对失效：长按卡片背面按钮 5 秒，在网页里重新配对一次"
    if "timed out connecting" in low:
        return "连不上卡片：它可能睡着了或在被手机占用；长按背面按钮唤醒后重试"
    if "not found" in low or "no matching" in low:
        return "没扫到卡片：确认它在附近并已唤醒（长按背面按钮）"
    if "required 0x05/0x08" in low:
        return "数据写完但没收到显影确认，卡片可能已重启；稍等 30 秒再发一次"
    return text[-300:]


def _cover(img, w: int, h: int):
    """等比缩放到铺满 w×h 后居中裁切。"""
    from PIL import Image
    sw, sh = img.size
    scale = max(w / sw, h / sh)
    nw, nh = max(1, int(sw * scale + 0.5)), max(1, int(sh * scale + 0.5))
    img = img.resize((nw, nh), Image.LANCZOS)
    left, top = (nw - w) // 2, (nh - h) // 2
    return img.crop((left, top, left + w, top + h))


def _wx_text(code) -> str:
    """天气代码 → 中文（和天气看板共用一套口径）。"""
    return _wx(code) or ""


_WIND_DIRS = ("北", "东北", "东", "东南", "南", "西南", "西", "西北")


def _wind_text(deg, kmh) -> str:
    """风向角度 + 风速 → "东北风 2级"（色块天气卡用）。"""
    try:
        d = float(deg) % 360
        idx = int((d + 22.5) // 45) % 8
        name = _WIND_DIRS[idx] + "风"
    except (TypeError, ValueError):
        name = ""
    try:
        v = float(kmh)
        if v < 1:
            lvl = 0
        elif v < 6:
            lvl = 1
        elif v < 12:
            lvl = 2
        elif v < 20:
            lvl = 3
        elif v < 29:
            lvl = 4
        elif v < 39:
            lvl = 5
        elif v < 50:
            lvl = 6
        elif v < 62:
            lvl = 7
        else:
            lvl = 8
        return f"{name} {lvl}级".strip()
    except (TypeError, ValueError):
        return name


def _wx(code) -> str:
    try:
        c = int(code)
    except (TypeError, ValueError):
        return ""
    if c == 0:
        return "晴"
    if c in (1, 2):
        return "多云"
    if c == 3:
        return "阴"
    if c in (45, 48):
        return "雾"
    if 51 <= c <= 67:
        return "雨"
    if 71 <= c <= 77:
        return "雪"
    if 80 <= c <= 82:
        return "阵雨"
    if 95 <= c <= 99:
        return "雷雨"
    return ""


# ─────────────────────────────────────────────────────────────────────────────
_CARD: CardService | None = None


def init_service(base_dir, data_dir, sse=None) -> CardService:
    global _CARD
    _CARD = CardService(Path(base_dir), Path(data_dir), sse=sse)
    return _CARD


def current() -> CardService:
    if _CARD is None:
        raise RuntimeError("card service 未初始化")
    return _CARD


def make_routes():
    async def _ai_write(request):
        """根据提示词生成卡面文字（写入自定义模板）。"""
        try:
            body = await request.json()
        except Exception:
            body = {}
        prompt = (body or {}).get("prompt", "").strip()
        if not prompt:
            return web.json_response({"ok": False, "error": "提示词不能为空"}, status=400)
        svc = current()
        sys_prompt = ("请为一块 528x792 的电子墨水屏写卡面文字：\n"
                      f"用户要求：{prompt}\n"
                      "输出 3~5 行、每行不超过 14 个汉字，不要表情符号、不要编号、不要解释，只输出正文。")
        text = svc._note_via_api(sys_prompt) or ""
        if not text:
            return web.json_response({"ok": False, "error": "AI 未返回内容：先在「AI 生图」里填接口和 Key"}, status=400)
        lines = [ln.strip(" 　-·") for ln in text.splitlines() if ln.strip()][:5]
        return web.json_response({"ok": True, "lines": lines})

    async def _status(request):
        return web.json_response({"ok": True, "card": current().status()})

    async def _config(request):
        body = await request.json()
        cfg = current().save_cfg(body or {})
        return web.json_response({"ok": True, "card": current().status(), "config": cfg})

    async def _resolve_city(request):
        """城市名 → 经纬度（在线 geocoding，失败退离线城市库）。"""
        try:
            body = await request.json()
        except Exception:
            body = {}
        name = str((body or {}).get("name") or "").strip()
        svc = current()
        res = await asyncio.get_running_loop().run_in_executor(None, svc.resolve_city, name)
        if res.get("ok"):
            # 解析成功就顺手存进配置，并把旧的天气缓存清掉，预览立刻换城市
            weather = dict(svc.cfg.get("weather") or {})
            weather.update({"enabled": True, "name": res["name"],
                            "lat": res["lat"], "lon": res["lon"]})
            svc.save_cfg({"weather": weather})
            try:
                svc._wx_cache = {"ts": 0, "data": {}}
                svc._weather_cache = {"ts": 0, "text": ""}
            except Exception:
                pass
        return web.json_response({**res, "card": svc.status()},
                                 status=200 if res.get("ok") else 404)

    async def _preview(request):
        kind = request.query.get("kind", "summary")
        fresh = request.query.get("fresh") == "1"
        p = await current().preview(kind, fresh=fresh)
        if p.suffix.lower() == ".png":
            return web.FileResponse(p, headers={"Cache-Control": "no-store"})
        # 照片原图交给浏览器，前端只做预览
        return web.FileResponse(p, headers={"Cache-Control": "no-store"})

    async def _send(request):
        try:
            body = await request.json()
        except Exception:
            body = {}
        kind = (body or {}).get("kind", "summary")
        if kind not in TEMPLATES:
            return web.json_response({"ok": False, "error": f"未知模板: {kind}"}, status=400)
        svc = current()
        if svc._busy:
            return web.json_response({"ok": False, "error": "正在发送中，稍等"}, status=409)
        # 不 await：传输本身 1~3 分钟，超过 request_timeout 的 30s；前端轮询 /api/card
        asyncio.create_task(svc.send(kind))
        return web.json_response({"ok": True, "started": True, "kind": kind})

    async def _pair(request):
        svc = current()
        try:
            body = await request.json()
        except Exception:
            body = {}
        dev = (body or {}).get("device_id") or svc.cfg.get("device_id")
        if not dev:
            return web.json_response({"ok": False, "error": "缺少 device_id"}, status=400)
        try:
            if sys.platform != "darwin":
                ok = await svc._pair_bleak(str(dev))
                return web.json_response({"ok": ok, "code": 0 if ok else 1,
                                          "output": "\n".join(svc._pair_lines[-40:])})
            sender, _ = await asyncio.get_running_loop().run_in_executor(None, svc._ensure_bins)
            r = await asyncio.get_running_loop().run_in_executor(
                None, lambda: subprocess.run([str(sender), "--pair", "--id", dev],
                                             capture_output=True, text=True, timeout=120))
            out = ((r.stdout or "") + (r.stderr or ""))[-2000:]
            ok = "Pairing completed" in out or "paired" in out.lower()
            return web.json_response({"ok": ok, "output": out, "code": r.returncode})
        except Exception as e:
            return web.json_response({"ok": False, "error": str(e)}, status=500)

    async def _templates(request):
        return web.json_response({"ok": True, "templates": TEMPLATES})

    async def _quote(request):
        return web.json_response({"ok": True, "quote": current().daily_quote()})

    async def _bing(request):
        svc = current()
        try:
            idx = int(request.query.get("idx", 0))
        except ValueError:
            idx = 0
        wp = await asyncio.get_running_loop().run_in_executor(None, svc.bing_wallpaper, idx)
        if not wp:
            return web.json_response({"ok": False, "error": "Bing 壁纸获取失败"}, status=502)
        return web.FileResponse(wp, headers={"Cache-Control": "no-store"})

    async def _ai_generate(request):
        svc = current()
        try:
            body = await request.json()
        except Exception:
            body = {}
        prompt = (body or {}).get("prompt") or svc.ai_prompt()
        _ai = svc.cfg.get("ai") or {}
        if not ((_ai.get("endpoint") and _ai.get("api_key")) or _ai.get("free", True)):
            return web.json_response({"ok": False, "error": "还没填接口地址和 API Key"}, status=400)
        path, err = await asyncio.get_running_loop().run_in_executor(
            None, svc.ai_generate, prompt)
        if err:
            return web.json_response({"ok": False, "error": err}, status=502)
        return web.json_response({"ok": True, "prompt": prompt,
                                  "image": "/api/card/ai/image?t=%d" % int(time.time())})

    async def _ai_image(request):
        svc = current()
        p = (svc._ai_last or {}).get("path")
        if not p or not Path(p).exists():
            return web.json_response({"ok": False, "error": "还没有生成过"}, status=404)
        return web.FileResponse(p, headers={"Cache-Control": "no-store"})

    async def _ai_prompt(request):
        return web.json_response({"ok": True, "prompt": current().ai_prompt(),
                                  "builtin": AI_PROMPTS})

    async def _pair_start(request):
        res = await current().pair_session()
        return web.json_response(res, status=200 if res.get("ok") else 400)

    async def _pair_status(request):
        svc = current()
        return web.json_response({"ok": True, "state": svc._pair_state,
                                  "log": svc._pair_lines[-80:],
                                  "paired_ts": int(svc.cfg.get("paired_ts", 0) or 0)})

    async def _restore_schedule(request):
        svc = current()
        backup = svc.cfg.get("schedule_backup") or []
        if not backup:
            return web.json_response({"ok": False, "error": "没有可恢复的计划"}, status=400)
        svc.cfg["schedule"] = backup
        svc.cfg.pop("schedule_backup", None)
        svc._save_cfg()
        return web.json_response({"ok": True, "card": svc.status()})

    async def _pick_folder(request):
        svc = current()
        paths = await asyncio.get_running_loop().run_in_executor(None, svc.pick_folders)
        return web.json_response({"ok": True, "paths": paths,
                                  "folders": svc.cfg.get("folders") or []})

    async def _scan(request):
        svc = current()
        if sys.platform != "darwin":
            # Windows / Linux：没有 Swift 工具，直接用 bleak 扫广播。
            # 兼容卡的广播里 manufacturer=0x5053（公司 ID 已被 bleak 剥掉），
            # 名字多半是 TodooCard / TODoo…，所以两个条件任一命中就算候选。
            def _ble_scan():
                from bleak import BleakScanner

                async def _go():
                    devs = await BleakScanner.discover(timeout=10, return_adv=True)
                    out = []
                    for addr, (dev, adv) in devs.items():
                        mfr = adv.manufacturer_data or {}
                        data = mfr.get(0x5053)
                        name = dev.name or adv.local_name or ""
                        if data is None and "todoo" not in name.lower():
                            continue
                        pairing = bool(data and len(data) > 1 and (data[1] & 0x02))
                        out.append({"addr": str(addr), "name": name,
                                    "manufacturer": data.hex(" ") if data else "",
                                    "pairing": pairing, "rssi": adv.rssi})
                    return out

                return asyncio.run(_go())

            try:
                found = await asyncio.get_running_loop().run_in_executor(None, _ble_scan)
            except Exception as e:
                return web.json_response({"ok": False, "error": f"蓝牙扫描失败: {e}"}, status=500)
            lines = [
                f"{d['addr']}  name={d['name'] or '-'}  rssi={d['rssi']}"
                f"{'  manufacturer=0x5053 ' + d['manufacturer'] if d['manufacturer'] else ''}"
                f"{'  [配对窗口已开]' if d['pairing'] else ''}  id={d['addr']}"
                for d in found
            ]
            return web.json_response({"ok": True, "devices": lines, "found": found, "raw": ""})
        try:
            sender, _ = await asyncio.get_running_loop().run_in_executor(None, svc._ensure_bins)
        except Exception as e:
            return web.json_response({"ok": False, "error": f"编译发送工具失败: {e}"}, status=500)
        r = await asyncio.get_running_loop().run_in_executor(
            None, lambda: subprocess.run([str(sender), "--list"], capture_output=True,
                                         text=True, timeout=120))
        lines = [ln for ln in (r.stdout or "").splitlines() if "manufacturer=0x5053" in ln]
        return web.json_response({"ok": True, "devices": lines, "raw": (r.stdout or "")[-4000:]})

    return [
        ("GET", "/api/card", _status),
        ("POST", "/api/card/ai/write", _ai_write),
        ("GET", "/api/card/templates", _templates),
        ("GET", "/api/card/quote", _quote),
        ("GET", "/api/card/bing", _bing),
        ("GET", "/api/card/ai/prompt", _ai_prompt),
        ("POST", "/api/card/ai/generate", _ai_generate),
        ("GET", "/api/card/ai/image", _ai_image),
        ("POST", "/api/card/config", _config),
        ("POST", "/api/card/weather/resolve", _resolve_city),
        ("GET", "/api/card/preview", _preview),
        ("POST", "/api/card/send", _send),
        ("POST", "/api/card/scan", _scan),
        ("POST", "/api/card/pick-folder", _pick_folder),
        ("POST", "/api/card/schedule/restore", _restore_schedule),
        ("POST", "/api/card/pair", _pair),
        ("POST", "/api/card/pair/start", _pair_start),
        ("GET", "/api/card/pair/status", _pair_status),
    ]
