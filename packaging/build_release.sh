#!/usr/bin/env bash
# Build a "extract and run" release zip for macOS / Linux.
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

echo "==> clean"
rm -rf "${DIST}" build-mac "${OUT}/${NAME}" "${OUT}/${NAME}.zip"
mkdir -p "${OUT}"

echo "==> pyinstaller (${OS}/${ARCH})"
"${PY}" -m PyInstaller --clean --noconfirm --distpath "${DIST}" --workpath build-mac packaging/todoocard-macos.spec

echo "==> assemble ${NAME}"
mkdir -p "${OUT}/${NAME}"
cp -R "${DIST}/TodooCardEink" "${OUT}/${NAME}/"
if [ -f packaging/使用说明.txt ]; then cp packaging/使用说明.txt "${OUT}/${NAME}/使用说明.txt"; fi

launcher='#!/bin/bash
cd "$(dirname "$0")" || exit 1
( sleep 1; open "http://127.0.0.1:8199/card.html" ) >/dev/null 2>&1 &
exec ./TodooCardEink/TodooCardEink'
printf '%s\n' "${launcher}" > "${OUT}/${NAME}/start-mac.command"
chmod +x "${OUT}/${NAME}/start-mac.command"
cp "${OUT}/${NAME}/start-mac.command" "${OUT}/${NAME}/启动（macOS）.command"
chmod +x "${OUT}/${NAME}/启动（macOS）.command"

echo "==> zip"
( cd "${OUT}" && zip -qr "${NAME}.zip" "${NAME}" -x '*.DS_Store' )

ls -lh "${OUT}/${NAME}.zip"
echo "done: ${OUT}/${NAME}.zip"
