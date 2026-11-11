# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置（Windows 单文件 exe）。

用起来：在项目根目录执行
    .venv\\Scripts\\python.exe -m PyInstaller --noconfirm --clean packaging\\todoocard.spec

产物：dist/TodooCardEink.exe  （再重命名成 土豆片卡片工坊.exe 放进安装包）
只读资源（web/、data/cities.json …）打在 exe 里，运行时解包到 sys._MEIPASS；
可写数据放 %LOCALAPPDATA%\\TodooCardEink（便携模式见 server.py 的 _data_dir）。
"""
from pathlib import Path

from PyInstaller.utils.hooks import collect_submodules

ROOT = Path(SPECPATH).resolve().parent  # noqa: F821  (SPECPATH 由 PyInstaller 注入)

datas = [(str(ROOT / "web"), "web")]
# poem_notes.json 是运行时缓存（不进仓库），有就一起带上，没有也不影响
for _seed in ("cities.json", "poem_notes.json"):
    _f = ROOT / "data" / _seed
    if _f.exists():
        datas.append((str(_f), "data"))

hiddenimports = [
    "tkinter",
    "tkinter.filedialog",
    "tkinter.ttk",
    "qrcode.image.pil",
    "qrcode.image.svg",
    "PIL._tkinter_finder",
    "sqlite3",
]
# bleak 的后端是按平台动态导入的，winrt 那套必须显式收进来，否则打包版扫不到蓝牙
hiddenimports += collect_submodules("bleak")
hiddenimports += collect_submodules("winrt")

a = Analysis(  # noqa: F821
    [str(ROOT / "server.py")],
    pathex=[str(ROOT)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["numpy", "pandas", "matplotlib", "scipy", "IPython", "pytest"],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)  # noqa: F821

exe = EXE(  # noqa: F821
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="TodooCardEink",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(ROOT / "packaging" / "app.ico"),
    version=str(ROOT / "packaging" / "version_info.txt"),
)
