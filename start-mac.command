#!/bin/bash
cd "$(dirname "$0")" || exit 1
echo "=========================================="
echo "  土豆片卡片工坊 - 首次运行会自动装依赖"
echo "=========================================="
echo

PY=""
for c in python3.12 python3.11 python3.10 python3; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
if [ -z "$PY" ]; then
  echo "[X] 没有找到 Python 3。"
  echo "    请先安装：https://www.python.org/downloads/macos/"
  open "https://www.python.org/downloads/macos/" 2>/dev/null
  read -n 1 -s -r -p "按任意键退出…"
  exit 1
fi

if [ ! -d .venv ]; then
  echo "[1/3] 创建独立运行环境（只做一次）…"
  "$PY" -m venv .venv || { echo "[X] 创建虚拟环境失败"; read -n 1 -s -r; exit 1; }
fi
. .venv/bin/activate

echo "[2/3] 检查依赖…"
if ! python -c "import aiohttp, bleak, PIL" >/dev/null 2>&1; then
  echo "      正在安装 aiohttp / bleak / Pillow / qrcode（需要联网，约 1 分钟）…"
  python -m pip install -q --upgrade pip
  python -m pip install -q -r requirements.txt || { echo "[X] 依赖安装失败"; read -n 1 -s -r; exit 1; }
fi

echo "[3/3] 启动服务，浏览器会自动打开…"
( sleep 2; open "http://127.0.0.1:8199/card.html" ) &
python server.py
