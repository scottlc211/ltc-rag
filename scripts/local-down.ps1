# 停止本地轻量模式（应用 + 独立的 MySQL 实例）
# 不会碰你系统里原有的 MySQL80 服务。

param(
    [int]$Port = 8081,
    [int]$DbPort = 3307
)

$ErrorActionPreference = 'Continue'

foreach ($p in @($Port, $DbPort, 6333)) {
    $conn = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) {
        Write-Host "端口 $p 没有监听进程，跳过" -ForegroundColor DarkGray
        continue
    }
    $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    if (-not $proc) { continue }
    Write-Host "停止 $($proc.ProcessName) (pid=$($proc.Id)) 占用端口 $p" -ForegroundColor Cyan
    Stop-Process -Id $proc.Id -Force
}

Write-Host "已停止。" -ForegroundColor Green
