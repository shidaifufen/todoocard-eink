<#
.SYNOPSIS
    打包「土豆片卡片工坊」便携版 zip（默认只出这一个，发布最快）。

.DESCRIPTION
    默认产物（release\ 下）：
      土豆片卡片工坊-便携版-<版本>.zip    解压双击即可用，数据存程序旁的 data\

    可选：
      -WithSingleFile   多出一个单文件 exe（启动慢 4~6 秒，一般不发）
      -WithInstaller    多出一个 Inno Setup 安装包（要装 Inno Setup 6）

      默认只出 zip：PyInstaller 目录版 ~30s + 压缩 ~10s，一分钟内能发一版。

.EXAMPLE
    pwsh -File packaging\build.ps1                     # 只出便携 zip（默认）
    pwsh -File packaging\build.ps1 -Version 1.0.1
    pwsh -File packaging\build.ps1 -WithInstaller      # 顺带出安装包
    pwsh -File packaging\build.ps1 -SkipDeps           # 依赖已装好，只重打
#>
[CmdletBinding()]
param(
    [string]$Version = "1.0.10",
    [switch]$WithSingleFile,
    [switch]$WithInstaller,
    [switch]$SkipDeps
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot          # 项目根目录
Set-Location $Root

$AppName = "土豆片卡片工坊"
$ReleaseDir = Join-Path $Root "release"
$Python = Join-Path $Root ".venv\Scripts\python.exe"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "    $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    $msg" -ForegroundColor Yellow }

# ── 0. 运行环境 ─────────────────────────────────────────────────────
if (-not (Test-Path $Python)) {
    Step "创建 .venv（只用一次）"
    $sys = $null
    if (Get-Command py -ErrorAction SilentlyContinue) { $sys = @("py", "-3") }
    elseif (Get-Command python -ErrorAction SilentlyContinue) { $sys = @("python") }
    if (-not $sys) { throw "找不到 Python，请先安装 Python 3.10+ 并勾选 Add Python to PATH" }
    & $sys[0] $sys[1..($sys.Length - 1)] -m venv (Join-Path $Root ".venv")
    Ok "已创建 .venv"
}

if (-not $SkipDeps) {
    Step "安装依赖"
    & $Python -m pip install --disable-pip-version-check -q -r (Join-Path $Root "requirements.txt")
    if ($LASTEXITCODE -ne 0) { throw "requirements.txt 安装失败" }
    & $Python -m pip install --disable-pip-version-check -q pyinstaller
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller 安装失败" }
    Ok "依赖就绪"
}

New-Item -ItemType Directory -Path $ReleaseDir -Force | Out-Null

# ── 1. 图标 + 版本资源 ──────────────────────────────────────────────
Step "生成图标和版本信息（$Version）"
& $Python (Join-Path $Root "packaging\make_icon.py") (Join-Path $Root "packaging\app.ico")
if ($LASTEXITCODE -ne 0) { throw "图标生成失败" }

$parts = @($Version.Split('.'))
while ($parts.Count -lt 4) { $parts += "0" }
$vi = Get-Content (Join-Path $Root "packaging\version_info.template.txt") -Raw -Encoding UTF8
$vi = $vi.Replace("@V1@", $parts[0]).Replace("@V2@", $parts[1]).Replace("@V3@", $parts[2]).
    Replace("@V4@", $parts[3]).Replace("@VERSION@", $Version)
Set-Content -Path (Join-Path $Root "packaging\version_info.txt") -Value $vi -Encoding UTF8
Ok "version_info.txt -> $Version"

# ── 2. 目录版（便携 zip 的本体，启动 1~2 秒）──────────────────────────
Step "PyInstaller 打包（约 30 秒）"
& $Python -m PyInstaller --noconfirm --clean (Join-Path $Root "packaging\todoocard-dir.spec")
if ($LASTEXITCODE -ne 0) { throw "PyInstaller（目录版）失败" }
$DirApp = Join-Path $Root "dist\$AppName"
$DirExe = Join-Path $DirApp "$AppName.exe"
if (-not (Test-Path $DirExe)) { throw "没找到 $DirExe" }
$Mb = [math]::Round(((Get-ChildItem $DirApp -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB, 1)
Ok "dist\$AppName\ ($Mb MB)"

# ── 3. 便携 zip（唯一的默认发布物）───────────────────────────────────
Step "打便携 zip"
$PortableRoot = Join-Path $Root "build\portable"
if (Test-Path $PortableRoot) { Remove-Item $PortableRoot -Recurse -Force }
$PortableDir = Join-Path $PortableRoot $AppName
New-Item -ItemType Directory -Path $PortableDir -Force | Out-Null
Copy-Item (Join-Path $Root "dist\$AppName\*") $PortableDir -Recurse -Force
Copy-Item (Join-Path $Root "packaging\使用说明.txt") (Join-Path $PortableDir "使用说明.txt") -Force
# 便携标记：数据存在程序旁边的 data\ 里，不写 %LOCALAPPDATA%
Set-Content -Path (Join-Path $PortableDir "portable.txt") `
    -Value "本文件存在 = 便携模式：配置、卡片历史、缓存都放在这个文件夹的 data\ 里，拷走整个文件夹数据就跟着走。" -Encoding UTF8
$ZipPath = Join-Path $ReleaseDir "$AppName-便携版-$Version.zip"
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path (Join-Path $PortableRoot "\$AppName") -DestinationPath $ZipPath -CompressionLevel Optimal
Ok "release\$AppName-便携版-$Version.zip ($([math]::Round((Get-Item $ZipPath).Length / 1MB, 1)) MB)"

# ── 4. 可选：单文件 exe ─────────────────────────────────────────────
if ($WithSingleFile) {
    Step "打包单文件版（约 1 分钟）"
    & $Python -m PyInstaller --noconfirm --clean (Join-Path $Root "packaging\todoocard.spec")
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller（单文件）失败" }
    $SingleOut = Join-Path $ReleaseDir "$AppName-单文件-$Version.exe"
    Copy-Item (Join-Path $Root "dist\TodooCardEink.exe") $SingleOut -Force
    Ok "release\$AppName-单文件-$Version.exe ($([math]::Round((Get-Item $SingleOut).Length / 1MB, 1)) MB)"
}

# ── 5. 可选：安装包 ─────────────────────────────────────────────────
if ($WithInstaller) {
    Step "编译安装包"
    $iscc = @(
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles}\Inno Setup 6\ISCC.exe",
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
        "$env:LOCALAPPDATA\Programs\Inno Setup\ISCC.exe"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $iscc) {
        $cmd = Get-Command ISCC.exe -ErrorAction SilentlyContinue
        if ($cmd) { $iscc = $cmd.Source }
    }
    if ($iscc) {
        & $iscc "/DMyVersion=$Version" (Join-Path $Root "packaging\installer.iss")
        if ($LASTEXITCODE -ne 0) { throw "Inno Setup 编译失败（退出码 $LASTEXITCODE）" }
        $SetupPath = Join-Path $ReleaseDir "$AppName-Setup-$Version.exe"
        if (Test-Path $SetupPath) {
            Ok "release\$AppName-Setup-$Version.exe ($([math]::Round((Get-Item $SetupPath).Length / 1MB, 1)) MB)"
        }
    }
    else {
        Warn "没找到 Inno Setup（ISCC.exe），跳过安装包。装一个再来：winget install -e --id JRSoftware.InnoSetup"
    }
}

# ── 6. 收尾 ─────────────────────────────────────────────────────────
Step "完成"
Get-ChildItem $ReleaseDir -File | Sort-Object Name | ForEach-Object {
    Write-Host ("    {0,-46} {1,8:N1} MB" -f $_.Name, ($_.Length / 1MB))
}
Write-Host "`n发出去的文件在 release\ 下面：" -ForegroundColor Gray
Write-Host "   上传到 GitHub Release 的就是这个 zip，用户解压双击即用。" -ForegroundColor Gray
