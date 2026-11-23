# 一键部署到 VPS（Docker Compose）
#
# 用法：
#   powershell -File .\scripts\deploy-remote.ps1 -Server 198.44.36.175
#   powershell -File .\scripts\deploy-remote.ps1 -Server 198.44.36.175 -InstallDocker   # 服务器还没装 Docker 时
#   powershell -File .\scripts\deploy-remote.ps1 -Server 198.44.36.175 -Rebuild         # 强制本地重新打包 jar
#   powershell -File .\scripts\deploy-remote.ps1 -Server 198.44.36.175 -SourceBuild     # 在服务器上从源码构建（吃内存，1~2G 机器别用）
#
# 前提：
#   1. 已能把本机公钥免密登录到服务器（~/.ssh/id_rsa）；没配好的话脚本会立刻报错，不会卡住等密码
#   2. deploy\.env.server 已按 deploy\.env.prod.example 填好（含密钥，已在 .gitignore 里）
#
# 做的事：
#   1. 本地确保有 target\ltc-rag.jar（没有就构建）
#   2. 打成一个 tar.gz 传上去（compose / Dockerfile.runtime / sql / jar / .env）
#   3. 远端 docker compose up -d --build，等 /v1/health 变 UP
#   4. 打印访问地址与首次生成的 ADMIN Key

param(
    [Parameter(Mandatory = $true)][string]$Server,
    [string]$User = 'root',
    [int]$SshPort = 22,
    [string]$RemoteDir = '/opt/ltc-rag',
    [string]$AppPort = '8080',
    [string]$EnvFile = 'deploy/.env.server',
    [switch]$Rebuild,
    [switch]$InstallDocker,
    [switch]$SourceBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$target = "$User@$Server"

# 远程命令一律 BatchMode：没配免密时立刻失败，而不是挂在密码提示上
$sshOpt = @('-p', "$SshPort", '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=10')
$scpOpt = @('-P', "$SshPort", '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=10')

function Invoke-Remote([string]$Command) {
    $out = & ssh @sshOpt $target $Command 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "远程命令失败（exit $LASTEXITCODE）：$Command`n$($out -join "`n")"
    }
    return ($out | Out-String).Trim()
}

function Invoke-RemoteQuiet([string]$Command) {
    $out = & ssh @sshOpt $target $Command 2>&1
    return @{ Code = $LASTEXITCODE; Output = ($out | Out-String).Trim() }
}

# ---- 0. 连通性：先把"没配免密"挡在最前面，给出可执行的修复提示 ----
Write-Host "[0/5] 检查免密登录 $target ..." -ForegroundColor Cyan
$probe = Invoke-RemoteQuiet 'echo READY'
if ($probe.Code -ne 0) {
    throw @"
连不上 $target（$($probe.Output)）。
如果没有配免密，请在服务器上执行一次（把本机公钥写进 authorized_keys）：

  mkdir -p ~/.ssh && chmod 700 ~/.ssh
  cat >> ~/.ssh/authorized_keys <<'EOF'
$(Get-Content "$env:USERPROFILE\.ssh\id_rsa.pub" -Raw)
EOF
  chmod 600 ~/.ssh/authorized_keys
"@
}
$sysinfo = Invoke-Remote 'uname -srm; nproc; free -m | awk ''/Mem:/{print "mem_mb="$2}''; df -h / | awk ''NR==2{print "disk_root="$4}'''
Write-Host "     $($sysinfo -replace "`n", ' | ')" -ForegroundColor DarkGray

# ---- 1. 本地 jar ----
$jar = Join-Path $root 'target\ltc-rag-deploy.jar'
if ($SourceBuild) {
    Write-Host "[1/5] 跳过本地 jar（-SourceBuild：服务器上构建）" -ForegroundColor DarkGray
} else {
    if ($Rebuild -or -not (Test-Path $jar)) {
        Write-Host "[1/5] 本地构建 jar ..." -ForegroundColor Cyan
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'local-build.ps1') -SkipTests
        if ($LASTEXITCODE -ne 0) { throw "本地构建失败" }
    } else {
        Write-Host "[1/5] 复用已有 jar：$jar（$(Get-Item $jar | ForEach-Object { $_.LastWriteTime })）" -ForegroundColor DarkGray
    }
}

# ---- 2. 打包 ----
$envPath = Join-Path $root $EnvFile
if (-not (Test-Path $envPath)) {
    throw "缺少 $envPath。先复制模板再填：Copy-Item deploy\.env.prod.example deploy\.env.server"
}

Write-Host "[2/5] 打包上传 ..." -ForegroundColor Cyan
$bundle = Join-Path $env:TEMP 'ltc-rag-deploy.tgz'
if (Test-Path $bundle) { Remove-Item $bundle -Force }

$items = @('docker-compose.yml', 'Dockerfile.runtime', 'Dockerfile.ocr', '.dockerignore', 'sql', 'scripts/embed_server.py', 'scripts/ltc_ocr.py')
if (-not $SourceBuild) { $items += 'target/ltc-rag-deploy.jar' }
& tar -czf $bundle -C $root @items
if ($LASTEXITCODE -ne 0) { throw "tar 打包失败（需要 Windows 自带的 tar.exe）" }
$bundleMb = [math]::Round((Get-Item $bundle).Length / 1MB, 1)

Invoke-Remote "mkdir -p $RemoteDir" | Out-Null
& scp @scpOpt $bundle "${target}:$RemoteDir/bundle.tgz"
if ($LASTEXITCODE -ne 0) { throw "scp 上传失败" }
& scp @scpOpt $envPath "${target}:$RemoteDir/.env"
if ($LASTEXITCODE -ne 0) { throw "scp 上传 .env 失败" }

Invoke-Remote "cd $RemoteDir && tar -xzf bundle.tgz && rm -f bundle.tgz && chmod 600 .env && ls -1" | Out-Null
Write-Host "     已上传 $bundleMb MB → ${target}:$RemoteDir" -ForegroundColor DarkGray

# ---- 3. Docker ----
Write-Host "[3/5] 检查 Docker ..." -ForegroundColor Cyan
$docker = Invoke-RemoteQuiet 'command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1 && echo HAVE_DOCKER || echo NO_DOCKER'
if ($docker.Output -notmatch 'HAVE_DOCKER') {
    if (-not $InstallDocker) {
        throw "服务器没有 docker 或 compose 插件。加 -InstallDocker 让我用官方脚本装，或手动装好后重跑。"
    }
    Write-Host "     安装 Docker（get.docker.com，约 1~2 分钟）..." -ForegroundColor Yellow
    Invoke-Remote 'curl -fsSL https://get.docker.com | sh' | Out-Null
    Invoke-Remote 'systemctl enable --now docker' | Out-Null
}

# ---- 4. 起栈 ----
Write-Host "[4/5] docker compose up -d --build ..." -ForegroundColor Cyan
$compose = Invoke-RemoteQuiet "cd $RemoteDir && docker compose up -d --build --remove-orphans 2>&1 | tail -20"
if ($compose.Code -ne 0) {
    throw "compose 启动失败：`n$($compose.Output)"
}
Write-Host ($compose.Output -split "`n" | Select-Object -Last 6 | ForEach-Object { "     $_" }) -ForegroundColor DarkGray

# ---- 5. 健康检查 ----
Write-Host "[5/5] 等待 /v1/health 变 UP ..." -ForegroundColor Cyan
$healthy = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    $h = Invoke-RemoteQuiet "curl -fsS --max-time 3 http://127.0.0.1:$AppPort/v1/health"
    if ($h.Code -eq 0 -and $h.Output -match '"status"\s*:\s*"UP"') {
        $healthy = $true
        $health = $h.Output
        break
    }
}
if (-not $healthy) {
    $logs = Invoke-RemoteQuiet "cd $RemoteDir && docker compose logs --tail=40 app"
    throw "健康检查超时。应用日志最后 40 行：`n$($logs.Output)"
}

$keyLine = Invoke-RemoteQuiet "cd $RemoteDir && docker compose logs app 2>&1 | grep -oE 'ltc_[A-Za-z0-9]{20,}' | head -1"

# 健康检查只证明进程活着；检索是否真的走了向量，要看 vectorStore / mode
$healthObj = $null
try { $healthObj = $health | ConvertFrom-Json } catch { }

# 公网可达性：服务器本地回环能通 ≠ 外面能访问（安全组/ufw 经常是最后一道坑）
$publicOk = $false
try {
    $pub = Invoke-RestMethod -Uri "http://${Server}:$AppPort/v1/health" -TimeoutSec 8
    $publicOk = ($pub.status -eq 'UP')
} catch { }

Write-Host ""
Write-Host "部署完成" -ForegroundColor Green
Write-Host "  界面:      http://${Server}:$AppPort/" -ForegroundColor White
Write-Host "  健康检查:  http://${Server}:$AppPort/v1/health" -ForegroundColor White
Write-Host "  健康详情:  $($health -replace "`n", ' ')" -ForegroundColor DarkGray
if ($healthObj -and $healthObj.vectorStore -eq 'DOWN') {
    Write-Host "  警告：Qdrant 连不上（vectorStore=DOWN），实际已退化为纯 BM25。查 docker compose logs qdrant" -ForegroundColor Yellow
} elseif ($healthObj -and $healthObj.mode -ne 'hybrid') {
    Write-Host "  注意：当前模式 $($healthObj.mode)，说明 embedding 没配好或 Qdrant 未就绪（不影响全文检索）" -ForegroundColor Yellow
}
if ($publicOk) {
    Write-Host "  公网可达:  是" -ForegroundColor DarkGray
} else {
    Write-Host "  公网可达:  否 —— 本机打不通 http://${Server}:$AppPort/ ，去安全组/ufw/firewalld 放行 $AppPort 端口" -ForegroundColor Yellow
}
if ($keyLine.Code -eq 0 -and $keyLine.Output) {
    Write-Host "  ADMIN Key: $($keyLine.Output)（只显示这一次，立刻保存）" -ForegroundColor Yellow
} else {
    Write-Host "  ADMIN Key: 没在日志里找到（可能已生成过）：docker compose -f $RemoteDir/docker-compose.yml logs app | grep ltc_" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "常用运维：" -ForegroundColor DarkGray
Write-Host "  ssh $target 'cd $RemoteDir && docker compose ps'" -ForegroundColor DarkGray
Write-Host "  ssh $target 'cd $RemoteDir && docker compose logs -f app'" -ForegroundColor DarkGray
Write-Host "  ssh $target 'cd $RemoteDir && docker compose down'" -ForegroundColor DarkGray
