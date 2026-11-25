#!/usr/bin/env bash
# Build a portable macOS release zip.
# Windows: use packaging/build.ps1
set -euo pipefail
cd "$(dirname "$0")/.."

VER="${1:-1.0.10}"
if [ -n "${PYTHON:-}" ]; then
  PY="${PYTHON}"
elif [ -x .venv/bin/python ]; then
  PY=".venv/bin/python"
else
  PY="python3"
fi
ARCH="$(uname -m)"
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
NAME="todoocard-eink-${VER}-${OS}-${ARCH}"
OUT="release"
DIST="dist-mac"
TOOLS="build-mac-tools"
SKILLS_REV=81f61e10c60ff0e7199749c67d090bb74252fd66

test "$OS" = darwin

echo "==> clean"
rm -rf "${DIST}" build-mac "${TOOLS}" "${OUT}/${NAME}" "${OUT}/${NAME}.zip"
mkdir -p "${OUT}" "${TOOLS}"

echo "==> build TodooCard Swift tools"
TOOLS_DIR="$(cd "${TOOLS}" && pwd)"
git init -q "${TOOLS}/upstream"
git -C "${TOOLS}/upstream" fetch --depth=1 https://github.com/Sunbelife/TodooCard_Skills.git "${SKILLS_REV}"
git -C "${TOOLS}/upstream" checkout --quiet --detach FETCH_HEAD
SCRIPTS="${TOOLS_DIR}/upstream/todoocard-skills/scripts"
bash "${SCRIPTS}/build_sender.sh" "${TOOLS_DIR}/mac-image-transfer"
bash "${SCRIPTS}/build_converter.sh" "${TOOLS_DIR}/image-to-payload"

echo "==> pyinstaller (${OS}/${ARCH})"
"${PY}" -m PyInstaller --clean --noconfirm --distpath "${DIST}" --workpath build-mac packaging/todoocard-macos.spec

echo "==> assemble ${NAME}"
mkdir -p "${OUT}/${NAME}"
cp -R "${DIST}/TodooCardEink" "${OUT}/${NAME}/"
cat > "${OUT}/${NAME}/使用说明.txt" <<'EOF'
解压后双击 start-mac.command。浏览器会自动打开本机工坊。
从旧版升级前请退出旧版，再把旧版 TodooCardEink/data 复制到新版同名目录，以保留原有配置。
首次配对请在卡片快闪时进入「设备与配对」，并确认 macOS 的蓝牙提示。
蓝牙工具来自 Sunbelife/TodooCard_Skills。
https://github.com/Sunbelife/TodooCard_Skills
EOF

launcher='#!/bin/bash
cd "$(dirname "$0")" || exit 1
( sleep 1; open "http://127.0.0.1:8199/card.html" ) >/dev/null 2>&1 &
exec ./TodooCardEink/TodooCardEink'
printf '%s\n' "${launcher}" > "${OUT}/${NAME}/start-mac.command"
chmod +x "${OUT}/${NAME}/start-mac.command"
cp "${OUT}/${NAME}/start-mac.command" "${OUT}/${NAME}/启动（macOS）.command"
chmod +x "${OUT}/${NAME}/启动（macOS）.command"

echo "==> zip"
( cd "${OUT}" && zip -qry "${NAME}.zip" "${NAME}" -x '*.DS_Store' )

ls -lh "${OUT}/${NAME}.zip"
echo "done: ${OUT}/${NAME}.zip"
