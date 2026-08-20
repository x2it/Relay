# LiteProxy build script
# Usage:
#   powershell -ExecutionPolicy Bypass -File build.ps1            # default: portable (onedir)
#   powershell -ExecutionPolicy Bypass -File build.ps1 portable   # portable (onedir) - copy folder to run
#   powershell -ExecutionPolicy Bypass -File build.ps1 installer  # single-file (onefile) - one exe to share
#   powershell -ExecutionPolicy Bypass -File build.ps1 all        # build both

param(
    [ValidateSet('portable','installer','all')]
    [string]$Target = 'portable'
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

Write-Host '==> Cleaning old output...' -ForegroundColor Cyan
Remove-Item -Recurse -Force build, dist -ErrorAction SilentlyContinue

function Invoke-PyInstaller {
    param([string[]]$PyArgs)
    & python -m PyInstaller @PyArgs
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed (exit $LASTEXITCODE)" }
}

if ($Target -in 'portable','all') {
    Write-Host "`n==> Building portable (onedir)..." -ForegroundColor Cyan
    Invoke-PyInstaller -PyArgs @(
        '--noconfirm','--clean','LiteProxy.spec'
    )
    $portable = Join-Path $here 'dist\LiteProxy'
    Write-Host "`n[OK] Portable build: $portable" -ForegroundColor Green
    Write-Host "     Copy the whole LiteProxy folder to any machine, run LiteProxy.exe."
}

if ($Target -in 'installer','all') {
    Write-Host "`n==> Building single-file (onefile)..." -ForegroundColor Cyan
    Invoke-PyInstaller -PyArgs @(
        '--noconfirm','--clean','--windowed','--onefile',
        '--name','LiteProxy',
        '--hidden-import','bs4',
        '--hidden-import','soupsieve',
        '--hidden-import','requests',
        '--hidden-import','urllib3',
        'main.py'
    )
    $onefile = Join-Path $here 'dist\LiteProxy.exe'
    Write-Host "`n[OK] Single-file build: $onefile" -ForegroundColor Green
    Write-Host "     One exe to share. First launch is slower (self-extracting)."
}

Write-Host "`n==> Done. Data folder is auto-created next to the exe on first run." -ForegroundColor Cyan
