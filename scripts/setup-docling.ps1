# 一次性安装 Docling（PDF/扫描件 → Markdown）
#
# 用法：powershell -ExecutionPolicy Bypass -File .\scripts\setup-docling.ps1
#
# 装完后的用法见 scripts\pdf2md.ps1 顶部注释。
# 依赖大概 2.5GB（torch + 版面/表格/OCR 模型），放在开发机或构建机上，别放 3C4G 服务器。

param(
    [string]$Root = 'D:\ltc-ocr',
    [string]$PythonLauncher = 'py',
    [string]$PythonVersion = '-3.13',
    [string]$IndexUrl = 'https://pypi.tuna.tsinghua.edu.cn/simple'
)

$ErrorActionPreference = 'Stop'

Write-Host "[1/3] 创建虚拟环境：$Root\venv" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $Root | Out-Null
if (-not (Test-Path "$Root\venv\Scripts\python.exe")) {
    & $PythonLauncher $PythonVersion -m venv "$Root\venv"
}
$py = "$Root\venv\Scripts\python.exe"
& $py --version

Write-Host "[2/3] 安装 docling（约 1.5GB，慢的话去泡杯茶）" -ForegroundColor Cyan
& $py -m pip install -U pip -i $IndexUrl --quiet
& $py -m pip install docling -i $IndexUrl

Write-Host "[3/3] 自检" -ForegroundColor Cyan
$docling = "$Root\venv\Scripts\docling.exe"
if (-not (Test-Path $docling)) { throw "安装后没找到 docling.exe" }
& $docling --help | Select-Object -First 3

Write-Host ""
Write-Host "安装完成。下一步：" -ForegroundColor Green
Write-Host "  1) 首次转换会自动下载版面/表格/OCR 模型（约 500MB），走 HF 镜像：" -ForegroundColor DarkGray
Write-Host "     `$env:HF_ENDPOINT='https://hf-mirror.com'" -ForegroundColor DarkGray
Write-Host "  2) 转换：.\scripts\pdf2md.ps1 .\某文档.pdf" -ForegroundColor DarkGray
Write-Host ""
Write-Host "注意 OCR 语言代码：rapidocr(torch) 后端用 iso:zh，tesseract/onnx 后端用 ch_sim。" -ForegroundColor Yellow
Write-Host "纯英文文档用 iso:en，能快不少。" -ForegroundColor DarkGray
