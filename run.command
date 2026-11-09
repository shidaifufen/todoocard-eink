#!/bin/bash
cd "$(dirname "$0")" || exit 1
[ -d .venv ] || python3 -m venv .venv
. .venv/bin/activate
python -m pip install -q -r requirements.txt
echo "打开 http://127.0.0.1:8199/card.html"
python server.py
