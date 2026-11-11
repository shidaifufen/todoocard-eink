@echo off
chcp 65001 >nul
title 土豆片卡片工坊
cd /d %~dp0

echo ==========================================
echo   土豆片卡片工坊 - 首次运行会自动装依赖
echo ==========================================
echo.

REM 1) 项目里已经有 .venv 就直接用，不依赖系统 Python
set "PY="
if exist ".venv\Scripts\python.exe" (
  set "PY=.venv\Scripts\python.exe"
  goto :deps
)

REM 2) 找一个真的能跑的 Python（Windows 商店的 python.exe 是 0 字节占位符，必须排除）
where py >nul 2>nul && set "PY=py -3"
if defined PY (
  %PY% -c "import sys" >nul 2>nul || set "PY="
)
if not defined PY (
  python -c "import sys" >nul 2>nul && set "PY=python"
)
if not defined PY (
  echo [X] 没有找到可用的 Python。
  echo     请先安装 Python 3.10 以上版本，安装时务必勾选 "Add Python to PATH"。
  echo     也可以在命令行执行：winget install -e --id Python.Python.3.12
  start https://www.python.org/downloads/
  pause
  exit /b 1
)

echo [1/3] 创建独立运行环境（只做一次）...
%PY% -m venv .venv || goto :fail
set "PY=.venv\Scripts\python.exe"

:deps
echo [2/3] 检查依赖...
%PY% -c "import aiohttp, bleak, PIL" >nul 2>nul
if errorlevel 1 (
  echo      正在安装 aiohttp / bleak / Pillow / qrcode（需要联网，约 1 分钟）...
  %PY% -m pip install -q --upgrade pip
  %PY% -m pip install -q -r requirements.txt || goto :fail
)

echo [3/3] 启动服务，浏览器会自动打开...
start "" http://127.0.0.1:8199/card.html
%PY% server.py
goto :eof

:fail
echo.
echo [X] 启动失败。把上面的报错截图发到 GitHub Issues。
pause