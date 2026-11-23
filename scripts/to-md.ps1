# 任意 Office/PDF/图片 → Markdown（自动挑转换器）
#
# 为什么要有这个脚本：
#   直接上传 docx 失败，最常见的两个原因——
#     1) 文件其实是老式 .doc，扩展名被改成了 .docx；
#     2) docx 里正文只有一张图片，Word 里没有文字，POI 抽出来是空的。
#   这个脚本把这两种都兜住：先按内容判格式，再决定用 Docling 还是 RapidOCR。
#
# 用法：
#   .\scripts\to-md.ps1 .\数据库常用表.docx
#   .\scripts\to-md.ps1 .\需求.pdf -ApiKey $ADMIN_KEY -DocKey "ltc/db/tables"
#
# 转换策略：
#   图片 / docx(纯图片)      → RapidOCR 直接识别，并按坐标还原成表格
#   PDF / docx(有文字)      → Docling 解析版面与表格；若发现只吐出图片(base64)自动回退到 RapidOCR
#   老式 .doc               → 先用 LibreOffice 转 PDF，再按 PDF 处理

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Path,

    [string]$OutDir = '',

    [string]$Python = 'D:\ltc-ocr\venv\Scripts\python.exe',
    [string]$Docling = 'D:\ltc-ocr\venv\Scripts\docling.exe',
    [int]$Dpi = 300,

    # LibreOffice Kit（只在 .doc 老格式时用得上）
    [string]$SofficeNode = 'D:\AI_studio\dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe',
    [string]$SofficeCli = 'D:\AI_studio\dsh\resources\app.asar.unpacked\dsh\node_modules\@deepseek-ai\libreoffice-kit\lib\cli.js',

    # 传了 ApiKey 就顺便入库
    [string]$ApiKey = '',
    [string]$DocKey = '',
    [string]$Title = '',
    [string]$BaseUrl = 'http://127.0.0.1:8081'
)

$ErrorActionPreference = 'Stop'
$scriptsDir = $PSScriptRoot
$img2md = Join-Path $scriptsDir 'img2md.py'

$item = Get-Item $Path -ErrorAction SilentlyContinue
if (-not $item) { throw "文件不存在：$Path" }
if (-not (Test-Path $Python)) { throw "找不到 Python：$Python" }

$ext = $item.Extension.ToLower()
$work = $item.FullName
$tempPdf = $null

function Test-Ole2([string]$file) {
    $b = [IO.File]::ReadAllBytes($file)[0..3]
    return (($b[0] -eq 0xD0) -and ($b[1] -eq 0xCF) -and ($b[2] -eq 0x11) -and ($b[3] -eq 0xE0))
}

function Test-Zip([string]$file) {
    $b = [IO.File]::ReadAllBytes($file)[0..3]
    return (($b[0] -eq 0x50) -and ($b[1] -eq 0x4B))
}

# docling 和 rapidocr 都会往 stderr 打进度日志，在 Stop 模式下会被当成异常，
# 所以调外部程序时临时切回 Continue，只看退出码
function Invoke-Quiet([scriptblock]$block) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $block } finally { $ErrorActionPreference = $prev }
}

# ---- 1. 老式 .doc：先转 PDF ----
if (($ext -eq '.doc') -or (($ext -eq '.docx') -and (Test-Ole2 $work))) {
    if (-not (Test-Path $SofficeCli)) { throw "这是老式 .doc，需要 LibreOffice 转换，但没找到 $SofficeCli" }
    Write-Host "[1/3] 检测到老式 .doc 格式，先转 PDF…" -ForegroundColor Cyan
    # LibreOffice 按扩展名校验，扩展名不改会报 "not a supported OOXML archive"
    $tempDoc = Join-Path $env:TEMP ($item.BaseName + '-src.doc')
    Copy-Item $work $tempDoc -Force
    $tempPdf = Join-Path $env:TEMP ($item.BaseName + '.pdf')
    Invoke-Quiet { & $SofficeNode $SofficeCli convert --input $tempDoc --output $tempPdf 2>&1 | Out-Null }
    Remove-Item $tempDoc -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path $tempPdf)) { throw "LibreOffice 转换失败" }
    $work = $tempPdf
    $ext = '.pdf'
}

if (-not $OutDir) { $OutDir = Join-Path $item.DirectoryName 'md' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$outMd = Join-Path $OutDir ($item.BaseName + '.md')

# ---- 2. 选转换器 ----
$useImg2md = $false
if ($ext -in @('.png', '.jpg', '.jpeg', '.bmp', '.webp', '.tif', '.tiff')) {
    $useImg2md = $true
} elseif ($ext -eq '.docx') {
    # docx 里没有 <w:t> 文本节点 → 纯图片，直接抠图 OCR
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($work)
    try {
        $docEntry = $zip.Entries | Where-Object { $_.FullName -eq 'word/document.xml' }
        $textCount = 0
        if ($docEntry) {
            $reader = New-Object IO.StreamReader($docEntry.Open())
            $xml = $reader.ReadToEnd()
            $reader.Close()
            $textCount = ([regex]::Matches($xml, '<w:t[ >]')).Count
        }
        if ($textCount -lt 3) { $useImg2md = $true }
    } finally { $zip.Dispose() }
}

$env:HF_ENDPOINT = if ($env:HF_ENDPOINT) { $env:HF_ENDPOINT } else { 'https://hf-mirror.com' }

if ($useImg2md) {
    Write-Host "[2/3] 纯图片内容，用 RapidOCR 识别…" -ForegroundColor Cyan
    Invoke-Quiet { & $Python $img2md $work -o $outMd --dpi $Dpi 2>&1 | Out-Null }
    $ocrStatus = $LASTEXITCODE
    if ($ocrStatus -ne 0) { throw "OCR 失败（退出码 $ocrStatus）" }
} else {
    Write-Host "[2/3] 有文字内容，用 Docling 解析版面…" -ForegroundColor Cyan
    Invoke-Quiet { & $Docling convert $work --to md --output $OutDir --device cpu 2>&1 | Out-Null }
    $docStatus = $LASTEXITCODE
    if ($docStatus -ne 0) { throw "Docling 解析失败（退出码 $docStatus）" }

    # Docling 遇到"整页就是一张图"会把图导成 base64，这种结果要回退
    if (Test-Path $outMd) {
        $probe = [string](Get-Content $outMd -Raw -Encoding UTF8)
        $cjk = ([regex]::Matches($probe, '[\u4e00-\u9fa5]')).Count
        if ($probe -match 'base64,' -or $cjk -lt 20) {
            Write-Host "       Docling 只吐出了图片（没有文字层），回退到 RapidOCR" -ForegroundColor Yellow
            Invoke-Quiet { & $Python $img2md $work -o $outMd --dpi $Dpi 2>&1 | Out-Null }
            $ocrStatus = $LASTEXITCODE
            if ($ocrStatus -ne 0) { throw "OCR 失败（退出码 $ocrStatus）" }
        }
    } else {
        throw "Docling 没有生成 $outMd"
    }
}

if ($tempPdf -and (Test-Path $tempPdf)) { Remove-Item $tempPdf -Force -ErrorAction SilentlyContinue }

# ---- 3. 结果 ----
$text = [string](Get-Content $outMd -Raw -Encoding UTF8)
Write-Host "[3/3] 完成：$outMd（$($text.Length) 字符）" -ForegroundColor Green
$preview = ($text -split "`n" | Select-Object -First 6) -join "`n"
Write-Host $preview -ForegroundColor DarkGray

if ($ApiKey) {
    $key = if ($DocKey) { $DocKey } else { "ltc/" + $item.BaseName }
    $payload = @{
        docKey  = $key
        title   = if ($Title) { $Title } else { $item.BaseName }
        content = $text
    }
    $tmp = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($tmp, ($payload | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    Write-Host "上传到 $BaseUrl …" -ForegroundColor Cyan
    $resp = curl.exe -s -m 300 -X POST "$BaseUrl/v1/documents/text" `
        -H "X-API-Key: $ApiKey" -H "Content-Type: application/json; charset=utf-8" `
        --data-binary "@$tmp"
    Remove-Item $tmp -ErrorAction SilentlyContinue
    Write-Host $resp -ForegroundColor DarkGray
}

Write-Output $outMd
exit 0
