# 构建（本机没有 Maven 时，自动用 ~/.m2/wrapper 里缓存的那个 Maven）
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$mvn = $null
$cached = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if ($cached) { $mvn = $cached.FullName }
if (-not $mvn) {
    $onPath = Get-Command mvn -ErrorAction SilentlyContinue
    if ($onPath) { $mvn = $onPath.Source }
}
if (-not $mvn) {
    throw "没找到 Maven：请安装（winget install Apache.Maven）或把 maven 解压到 ~/.m2/wrapper/dists"
}

Write-Host "使用 Maven: $mvn" -ForegroundColor DarkGray
$mvnArgs = @('-B', 'package', '-Djar.finalName=ltc-rag-deploy')
if ($SkipTests) { $mvnArgs += '-DskipTests' }

Push-Location $root
try {
    & $mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host "构建完成：$root\target\ltc-rag-deploy.jar" -ForegroundColor Green
Write-Host "启动：powershell -File .\scripts\local-up.ps1" -ForegroundColor DarkGray
