# 启动本地 embedding 服务（127.0.0.1:8091，OpenAI 兼容）
#
# 为什么需要它：
#   硅基流动等云服务的注册要手机实名，被风控就卡住了。
#   本地跑 bge-small-zh-v1.5（ONNXRuntime，约 95MB 模型、300MB 内存）完全免费、离线可用。
#
# 用法：powershell -ExecutionPolicy Bypass -File .\scripts\embed-up.ps1
# 停止：scripts\embed-down.ps1

param(
    [string]$Root = 'D:\ltc-ocr',
    [int]$Port = 8091,
    [string]$Model = 'BAAI/bge-small-zh-v1.5'
)

$ErrorActionPreference = 'Stop'
$scriptsDir = $PSScriptRoot
$py = Join-Path $Root 'venv\Scripts\python.exe'

if (-not (Test-Path $py)) {
    throw "找不到 Python 环境 $py，先跑 scripts\setup-docling.ps1（里面有 fastembed 的安装）"
}
if (-not (Test-Path (Join-Path $scriptsDir 'embed_server.py'))) {
    throw "找不到 embed_server.py"
}

function Test-Port([int]$p) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue)
}

if (Test-Port $Port) {
    Write-Host "embedding 服务已在 $Port 端口运行" -ForegroundColor DarkGray
} else {
    Write-Host "启动 embedding 服务（$Model → :$Port）…" -ForegroundColor Cyan
    # 国内直连 huggingface 经常超时，模型下载走镜像
    $cmdFile = Join-Path $Root 'run-embed.cmd'
    $cmd = @"
@echo off
cd /d "$scriptsDir"
set HF_ENDPOINT=https://hf-mirror.com
set EMBED_MODEL=$Model
"$py" -m uvicorn embed_server:app --host 127.0.0.1 --port $Port >> "$Root\embed.log" 2>&1
"@
    Set-Content -Path $cmdFile -Value $cmd -Encoding Ascii
    # 给个独立控制台，当前终端的 Ctrl+C 波及不到它
    Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine      = "cmd.exe /c start `"`" /min `"$cmdFile`""
        CurrentDirectory = $Root
    } | Out-Null

    $ok = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
            if ($h.status -eq 'UP') { $ok = $true; break }
        } catch { }
    }
    if (-not $ok) {
        Write-Host "启动失败，看 $Root\embed.log" -ForegroundColor Red
        Get-Content (Join-Path $Root 'embed.log') -Tail 15 -ErrorAction SilentlyContinue
        exit 1
    }
}

$h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 5
Write-Host ""
Write-Host "  就绪:    http://127.0.0.1:$Port/v1/embeddings" -ForegroundColor Green
Write-Host "  模型:    $($h.model)" -ForegroundColor White
Write-Host "  维度:    $($h.dimension)" -ForegroundColor White
Write-Host ""
Write-Host "  .env 里对应配置（维度要跟着模型改）:" -ForegroundColor DarkGray
Write-Host "    EMBEDDING_BASE_URL=http://127.0.0.1:$Port/v1" -ForegroundColor DarkGray
Write-Host "    EMBEDDING_API_KEY=local" -ForegroundColor DarkGray
Write-Host "    EMBEDDING_MODEL=$($h.model)" -ForegroundColor DarkGray
Write-Host "    EMBEDDING_DIMENSION=$($h.dimension)" -ForegroundColor DarkGray
