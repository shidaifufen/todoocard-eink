# -*- mode: python ; coding: utf-8 -*-
# macOS 目录版打包（解压即用；启动 1~2s）
from pathlib import Path

from PyInstaller.utils.hooks import collect_submodules

root = Path(SPECPATH).resolve().parent
tools = root / "build-mac-tools"

a = Analysis(
    [str(root / "server.py")],
    pathex=[str(root)],
    binaries=[(str(tools / name), "mac-tools") for name in ("mac-image-transfer", "image-to-payload")],
    datas=[(str(root / "web"), "web"), (str(root / "data" / "cities.json"), "data")],
    hiddenimports=collect_submodules('bleak') + ['aiosignal', 'attr'],
    hookspath=[],
    runtime_hooks=[],
    excludes=['tkinter', 'matplotlib', 'numpy', 'pandas'],
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz, a.scripts, [],
    exclude_binaries=True,
    name='TodooCardEink',
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name='TodooCardEink')
