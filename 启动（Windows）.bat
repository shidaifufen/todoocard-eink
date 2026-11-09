@echo off
chcp 65001 >nul
title 土豆片卡片工坊
cd /d %~dp0

echo ==========================================
echo   土豆片卡片工坊 - 首次运行会自动装依赖
echo ==========================================
echo.

REM 找一个能用的 Python
set PY=
where py >nul 2>nul && set PY=py -3
if not defined PY ( where python >nul 2>nul && set PY=python )
if not defined PY (
  echo [X] 没有找到 Python。
  echo     请先安装 Python 3.10 以上版本：https://www.python.org/downloads/
  echo     安装时务必勾选 "Add Python to PATH"，装完再双击本文件。
  start https://www.python.org/downloads/
  pause
  exit /b 1
)

if not exist .venv (
  echo [1/3] 创建独立运行环境（只做一次）...
  %PY% -m venv .venv || goto :fail
)
call .venv\Scripts\activate.bat

echo [2/3] 检查依赖...
python -c "import aiohttp, bleak, PIL" >nul 2>nul
if errorlevel 1 (
  echo      正在安装 aiohttp / bleak / Pillow / qrcode（需要联网，约 1 分钟）...
  python -m pip install -q --upgrade pip
  python -m pip install -q -r requirements.txt || goto :fail
)

echo [3/3] 启动服务，浏览器会自动打开...
start "" http://127.0.0.1:8199/card.html
python server.py
goto :eof

:fail
echo.
echo [X] 启动失败。把上面的报错截图发到 GitHub Issues。
pause
