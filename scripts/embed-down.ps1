# 停止本地 embedding 服务（默认 8091）
param([int]$Port = 8091)

$ErrorActionPreference = 'Continue'
$conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $conn) {
    Write-Host "端口 $Port 没有监听进程" -ForegroundColor DarkGray
    exit 0
}
$proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
if ($proc) {
    Write-Host "停止 $($proc.ProcessName) (pid=$($proc.Id))" -ForegroundColor Cyan
    Stop-Process -Id $proc.Id -Force
}
Write-Host "已停止" -ForegroundColor Green
