# -*- mode: python ; coding: utf-8 -*-
# macOS 目录版打包（解压即用；启动 1~2s）
from PyInstaller.utils.hooks import collect_submodules

block_cipher = None

a = Analysis(
    ['../server.py'],
    pathex=[],
    binaries=[],
    datas=[('../web', 'web'), ('../data/cities.json', 'data')],
    hiddenimports=collect_submodules('bleak') + ['aiosignal', 'attr'],
    hookspath=[],
    runtime_hooks=[],
    excludes=['tkinter', 'matplotlib', 'numpy', 'pandas'],
    cipher=block_cipher,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

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
coll = COLLECT(exe, a.binaries, a.zipfiles, a.datas, strip=False, upx=False, name='TodooCardEink')
