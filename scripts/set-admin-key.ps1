# 修改本地 ADMIN 的登录 Key（网页登录框里填的那串）
#
# 用法：
#   .\scripts\set-admin-key.ps1 -Key "lc123456"
#
# 原理：库里只存 SHA256(明文)，校验时把请求头里的 key 现算哈希再比对，
#       所以直接改 key_hash 即可，应用不用重启，改完立刻生效。
#
# 注意：这串相当于管理员密码，越短越好猜。本机自用图省事可以，别带进生产。

param(
    [Parameter(Mandatory = $true)]
    [string]$Key,

    [string]$Name = 'local-admin',

    # 只改这个 id 的 ADMIN；默认 1（首次启动 bootstrap 的那条）
    [int]$KeyId = 1,

    [string]$MysqlExe = 'D:\MySQL\Server8.0\bin\mysql.exe',
    [int]$DbPort = 3307,
    [string]$DbUser = 'ltc',
    [string]$DbPassword = 'ltc-local',
    [string]$DbName = 'ltc_rag',

    # 改完顺便验一下（留空则不验）
    [string]$BaseUrl = 'http://127.0.0.1:8081'
)

$ErrorActionPreference = 'Stop'

$Key = $Key.Trim()
if ([string]::IsNullOrWhiteSpace($Key)) { throw "Key 不能为空" }
if ($Key -match '\s') { throw "Key 里不能有空白字符" }
if (-not (Test-Path $MysqlExe)) { throw "找不到 mysql 客户端：$MysqlExe" }

if ($Key.Length -lt 12) {
    Write-Host "提示：当前 Key 只有 $($Key.Length) 位，容易被猜到，本机自用可以接受。" -ForegroundColor Yellow
}

# SHA256 小写十六进制，与应用里 Digests.sha256Hex 保持一致
$bytes = [Text.Encoding]::UTF8.GetBytes($Key)
$hash = -join ((New-Object Security.Cryptography.SHA256Managed).ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') })

# prefix 只用于展示，取前 12 位
$prefix = if ($Key.Length -gt 12) { $Key.Substring(0, 12) } else { $Key }
$safePrefix = $prefix.Replace("'", "''")
$safeName = $Name.Replace("'", "''")

$sql = @"
UPDATE $DbName.kb_api_key
   SET key_prefix = '$safePrefix',
       key_hash   = '$hash',
       name       = '$safeName',
       status     = 'ACTIVE'
 WHERE id = $KeyId AND role = 'ADMIN';
SELECT id, name, key_prefix, role, status FROM $DbName.kb_api_key;
"@

$args = @('--protocol=tcp', "--host=127.0.0.1", "--port=$DbPort", "--user=$DbUser",
          "--password=$DbPassword", '--default-character-set=utf8mb4', '-e', $sql)
# mysql 会往 stderr 打一句 "Using a password ... can be insecure"，
# 在 Stop 模式下会被当成异常，所以这里临时切回 Continue，只看退出码
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$out = & $MysqlExe @args 2>&1
$code = $LASTEXITCODE
$ErrorActionPreference = $prevEap

if ($code -ne 0) {
    throw "更新失败（MySQL 是不是没启动？先跑 scripts\local-up.ps1）：$out"
}
$out = ($out | Where-Object { $_ -notmatch 'Using a password' })
Write-Host ($out -join "`n") -ForegroundColor DarkGray

if ($BaseUrl) {
    try {
        $me = Invoke-RestMethod -Uri "$BaseUrl/v1/me" -Headers @{ 'X-API-Key' = $Key } -TimeoutSec 5
        Write-Host ""
        Write-Host "验证通过：$($me.name) / $($me.role)" -ForegroundColor Green
        Write-Host "登录地址：$BaseUrl/#k=$Key" -ForegroundColor White
    } catch {
        Write-Host ""
        Write-Host "库里已更新，但验证请求失败（应用可能没启动）：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "浏览器里如果还存着旧 Key，点左下角「清除本机 Key」重新登录，或直接用上面的 #k= 链接。" -ForegroundColor DarkGray
