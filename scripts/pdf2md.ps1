# PDF/扫描件 → Markdown（Docling 离线转换）
#
# 为什么放在开发机而不是服务器：
#   Docling 依赖 torch，模型 + 依赖 ~2.5GB，3C4G 上跑不动（也没有 GPU）。
#   正确姿势是本地转成 Markdown 再上传：
#     .\scripts\pdf2md.ps1 .\docs\接口规范.pdf
#     curl -X POST localhost:8080/v1/documents/text -H "X-API-Key: $ADMIN_KEY" \
#          -H "Content-Type: application/json" \
#          -d "{""docKey"":""ltc/design/api-spec"",""title"":""接口规范"",""content"":""$(Get-Content .\docs\接口规范.md -Raw)""}"
#
# 首次运行会从 HuggingFace 下载版面/表格/OCR 模型（约 500MB），之后离线可用。

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Path,

    [string]$OutDir = '',

    # Docling 安装位置（scripts\setup-docling.ps1 会装到这里）
    [string]$Docling = 'D:\ltc-ocr\venv\Scripts\docling.exe',

    # 中文用 iso:zh；tesseract/onnx 后端那种旧写法是 ch_sim
    [string]$OcrLang = 'iso:zh',

    # 有文本层的 PDF 不需要 OCR，加 -NoOcr 更快
    [switch]$NoOcr,

    # 表格结构识别比较慢，纯文字文档可以加 -Fast 关掉
    [switch]$Fast,

    # 传了 ApiKey 就顺便入库，省一次手工上传
    [string]$ApiKey = '',
    [string]$DocKey = '',
    [string]$Title = '',
    [string]$Namespace = '',
    [string]$BaseUrl = 'http://127.0.0.1:8081'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Docling)) {
    throw "找不到 Docling：$Docling，先跑 scripts\setup-docling.ps1"
}
if (-not (Test-Path $Path)) {
    throw "输入文件不存在：$Path"
}

$item = Get-Item $Path
if (-not $OutDir) {
    $OutDir = Join-Path $item.DirectoryName 'md'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# 国内直连 huggingface.co 经常超时，模型下载走镜像
if (-not $env:HF_ENDPOINT) { $env:HF_ENDPOINT = 'https://hf-mirror.com' }

$cliArgs = @('convert', $item.FullName, '--to', 'md', '--output', $OutDir, '--device', 'cpu')
if (-not $NoOcr) { $cliArgs += @('--ocr', '--ocr-lang', $OcrLang) }
if ($Fast) { $cliArgs += @('--table-mode', 'none') }

Write-Host "转换中（首次会下载模型，请耐心等）…" -ForegroundColor Cyan
Write-Host ("  " + $Docling + " " + ($cliArgs -join ' ')) -ForegroundColor DarkGray
& $Docling @cliArgs
if ($LASTEXITCODE -ne 0) { throw "Docling 转换失败，退出码 $LASTEXITCODE" }

$md = Join-Path $OutDir ($item.BaseName + '.md')
if (Test-Path $md) {
    # 注意：PS 5.1 里 Get-Content -Raw 的结果直接塞进 ConvertTo-Json 会变成 {"value": "..."}，
    # 必须显式转成 [string]，否则后端收到的 content 是个对象而不是字符串
    $text = [string](Get-Content $md -Raw -Encoding UTF8)
    Write-Host ""
    Write-Host "转换完成：$md（$($text.Length) 字符）" -ForegroundColor Green

    if ($ApiKey) {
        $key = if ($DocKey) { $DocKey } else { "ltc/" + $item.BaseName }
        $payload = @{
            docKey    = $key
            title     = if ($Title) { $Title } else { $item.BaseName }
            content   = $text
        }
        if ($Namespace) { $payload.namespace = $Namespace }
        $json = $payload | ConvertTo-Json -Depth 5
        $tmp = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($tmp, $json, [Text.UTF8Encoding]::new($false))
        Write-Host "上传到 $BaseUrl …" -ForegroundColor Cyan
        $resp = curl.exe -s -m 300 -X POST "$BaseUrl/v1/documents/text" `
            -H "X-API-Key: $ApiKey" -H "Content-Type: application/json; charset=utf-8" `
            --data-binary "@$tmp"
        Remove-Item $tmp -ErrorAction SilentlyContinue
        Write-Host $resp -ForegroundColor DarkGray
    } else {
        Write-Host "接下来可以直接在网页「文档库」拖进去，或用 /v1/documents/text 提交。" -ForegroundColor DarkGray
    }
    Write-Output $md
} else {
    Write-Host "没找到预期的输出文件，看看 $OutDir 目录里生成了什么" -ForegroundColor Yellow
}

exit 0
