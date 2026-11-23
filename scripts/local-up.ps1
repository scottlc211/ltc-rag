# 本地一键启动（无 Docker 环境下的轻量模式）
#
# 用法：powershell -ExecutionPolicy Bypass -File .\scripts\local-up.ps1
#       powershell -ExecutionPolicy Bypass -File .\scripts\local-up.ps1 -Port 8082
#
# 做的事：
#   1. 起一个独立的 MySQL 实例（端口 3307，数据在 .localdb\data，不碰已有的 MySQL80）
#   2. 起应用（配置从 .env 读，缺省为轻量模式：不连 Qdrant、不调 embedding）
#   3. 等健康检查通过，打印访问地址与 ADMIN Key
#
# 说明：两个常驻进程都用 WMI 启动，因此
#   - 不继承当前终端的 stdout（管道调用也不会卡住）
#   - 关掉终端 / 结束本次命令，服务继续运行
#
# 前提：JDK 17+，且已有 target\ltc-rag.jar（scripts\local-build.ps1 构建）。

param(
    [int]$Port = 8081,
    [int]$DbPort = 3307,
    [string]$MysqlHome = 'D:\MySQL\Server8.0',
    [string]$QdrantHome = 'D:\qdrant'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$local = Join-Path $root '.localdb'
$data = Join-Path $local 'data'
$jar = Join-Path $root 'target\ltc-rag.jar'
$log = Join-Path $local 'app.out.log'
$errLog = Join-Path $local 'app.err.log'

if (-not (Test-Path $jar)) {
    throw "找不到 $jar，请先构建：powershell -File .\scripts\local-build.ps1"
}
if (-not (Test-Path (Join-Path $data 'mysql'))) {
    throw "本地数据目录未初始化：$data（先执行 mysqld --initialize-insecure，见 README「本地运行」）"
}

function Test-Port([int]$p) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue)
}

# 通过 WMI 启动：完全脱离当前控制台，避免句柄继承导致调用方卡死
function Start-Detached([string]$cmdFile) {
    Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine      = "cmd.exe /c `"$cmdFile`""
        CurrentDirectory = (Split-Path -Parent $cmdFile)
    } | Out-Null
}

# 给常驻进程一个独立控制台（start /min），当前终端的 Ctrl+C 波及不到它
function Start-InOwnConsole([string]$cmdFile) {
    Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine      = "cmd.exe /c start `"`" /min `"$cmdFile`""
        CurrentDirectory = (Split-Path -Parent $cmdFile)
    } | Out-Null
}

# ---- 0. 读取 .env（已存在的环境变量优先，方便临时覆盖） ----
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile -Encoding UTF8) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $k = $t.Substring(0, $i).Trim()
        $v = $t.Substring($i + 1).Trim().Trim('"')
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($k))) {
            Set-Item -Path "env:$k" -Value $v
        }
    }
}
if (-not $env:MYSQL_HOST) { $env:MYSQL_HOST = '127.0.0.1' }
if (-not $env:MYSQL_PORT) { $env:MYSQL_PORT = "$DbPort" }
if (-not $env:MYSQL_DB) { $env:MYSQL_DB = 'ltc_rag' }
if (-not $env:MYSQL_USER) { $env:MYSQL_USER = 'ltc' }
if (-not $env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD = 'ltc-local' }

# ---- 1. MySQL ----
if (Test-Port $DbPort) {
    Write-Host "[1/3] MySQL 已在 $DbPort 端口运行，跳过" -ForegroundColor DarkGray
} else {
    Write-Host "[1/3] 启动本地 MySQL（$DbPort）..." -ForegroundColor Cyan
    $dbCmdFile = Join-Path $local 'run-db.cmd'
    $dbCmd = @"
@echo off
"$MysqlHome\bin\mysqld.exe" --basedir="$MysqlHome" --datadir="$data" --port=$DbPort --bind-address=127.0.0.1 --mysqlx=OFF --console >> "$local\mysqld.out.log" 2>&1
"@
    Set-Content -Path $dbCmdFile -Value $dbCmd -Encoding Ascii
    Start-Detached $dbCmdFile
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Port $DbPort) { break }
    }
    if (-not (Test-Port $DbPort)) { throw "MySQL 启动失败，看 $local\mysqld.out.log" }
}

# ---- 1.5 Qdrant（只有开了向量模式才需要）----
if ($env:QDRANT_ENABLED -eq 'true') {
    if (Test-Port 6333) {
        Write-Host "[1.5/3] Qdrant 已在 6333 端口运行，跳过" -ForegroundColor DarkGray
    } elseif (Test-Path (Join-Path $QdrantHome 'qdrant.exe')) {
        Write-Host "[1.5/3] 启动 Qdrant（6333）..." -ForegroundColor Cyan
        New-Item -ItemType Directory -Force -Path (Join-Path $QdrantHome 'storage') | Out-Null
        $qCmdFile = Join-Path $local 'run-qdrant.cmd'
        $qCmd = @"
@echo off
cd /d "$QdrantHome"
set QDRANT__STORAGE__STORAGE_PATH=$QdrantHome\storage
set QDRANT__SERVICE__HTTP_PORT=6333
set QDRANT__SERVICE__GRPC_PORT=6334
qdrant.exe >> "$local\qdrant.log" 2>&1
"@
        Set-Content -Path $qCmdFile -Value $qCmd -Encoding Ascii
        Start-InOwnConsole $qCmdFile
        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Milliseconds 500
            if (Test-Port 6333) { break }
        }
        if (-not (Test-Port 6333)) { Write-Host "  Qdrant 没起来，看 $local\qdrant.log；不影响纯 BM25 使用" -ForegroundColor Yellow }
    } else {
        Write-Host "[1.5/3] 没找到 $QdrantHome\qdrant.exe，跳过（检索退回纯 BM25）" -ForegroundColor Yellow
    }
}

# ---- 1.6 本地 embedding 服务（EMBEDDING_BASE_URL 指向本机时才需要）----
if ($env:EMBEDDING_ENABLED -eq 'true' -and $env:EMBEDDING_BASE_URL -match '127\.0\.0\.1|localhost') {
    $embedUp = Join-Path $PSScriptRoot 'embed-up.ps1'
    if (Test-Path $embedUp) {
        Write-Host "[1.6/3] 检查本地 embedding 服务（8091）..." -ForegroundColor Cyan
        & powershell -NoProfile -ExecutionPolicy Bypass -File $embedUp | Select-Object -Last 6
    }
}

# ---- 2. 应用 ----
if (Test-Port $Port) {
    Write-Host "[2/3] 端口 $Port 已被占用（可能已启动过），直接复用" -ForegroundColor Yellow
} else {
    Write-Host "[2/3] 启动应用（:$Port）..." -ForegroundColor Cyan
    $appCmdFile = Join-Path $local 'run-app.cmd'
    # 用 javaw.exe（无控制台）：终端的 Ctrl+C / 关闭窗口不会波及它
    $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $javaExe) { throw "PATH 里找不到 java，请先装 JDK 17+" }
    $javaw = Join-Path (Split-Path $javaExe) 'javaw.exe'
    if (-not (Test-Path $javaw)) { $javaw = $javaExe }
    # 环境变量在这里展开成 set 行，WMI 启动的新进程不继承当前会话的环境。
    # 按前缀整体转发，而不是逐个写死：以后 .env 里新增配置就不用再改脚本了
    $prefixes = @('MYSQL_', 'CHAT_', 'EMBEDDING_', 'QDRANT_', 'RERANK_', 'OCR_', 'AUTH_')
    $envLines = (Get-ChildItem Env: |
        Where-Object { $n = $_.Name; $prefixes | Where-Object { $n.StartsWith($_) } } |
        Sort-Object Name |
        ForEach-Object { 'set "' + $_.Name + '=' + $_.Value + '"' }) -join "`r`n"
    $appCmd = @"
@echo off
cd /d "$root"
$envLines
"$javaw" -jar "target\ltc-rag.jar" --server.port=$Port > "$log" 2> "$errLog"
"@
    Set-Content -Path $appCmdFile -Value $appCmd -Encoding Ascii
    Start-Detached $appCmdFile

    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/health" -TimeoutSec 3
            if ($h.status -eq 'UP') { break }
        } catch { }
    }
}

# ---- 3. 结果 ----
$h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/health" -TimeoutSec 5
Write-Host "[3/3] 已就绪" -ForegroundColor Green
Write-Host ""
Write-Host "  界面:      http://127.0.0.1:$Port/" -ForegroundColor White
Write-Host "  健康检查:  http://127.0.0.1:$Port/v1/health" -ForegroundColor White
Write-Host "  模式:      $($h.mode)（vectorStore=$($h.vectorStore), chatConfigured=$($h.chatConfigured)）" -ForegroundColor DarkGray
Write-Host ""
$keyLine = Select-String -Path $log -Pattern 'ltc_[A-Za-z0-9]{20,}' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($keyLine) {
    $key = [regex]::Match($keyLine.Line, 'ltc_[A-Za-z0-9]+').Value
    Write-Host "  ADMIN Key: $key" -ForegroundColor Yellow
} else {
    Write-Host "  ADMIN Key: 已存在（首次生成的那串，日志里没有就查 kb_api_key 表）" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "停止服务：scripts\local-down.ps1" -ForegroundColor DarkGray
