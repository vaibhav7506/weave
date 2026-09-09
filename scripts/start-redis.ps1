$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$redisBin = 'C:\Program Files\Redis'
$redisReply = & "$redisBin/redis-cli.exe" -h 127.0.0.1 -p 56379 ping 2>$null
if ($redisReply -eq 'PONG') { Write-Output 'Redis already available on 127.0.0.1:56379'; exit 0 }
New-Item -ItemType Directory -Force (Join-Path $projectRoot '.tools') | Out-Null
$configPath = Join-Path $projectRoot '.tools/redis.conf'
@('bind 127.0.0.1','port 56379','save ""','appendonly no') | Set-Content $configPath
$redisProcess = Start-Process -FilePath "$redisBin/redis-server.exe" -ArgumentList @($configPath) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot '.tools/redis.log') -RedirectStandardError (Join-Path $projectRoot '.tools/redis-error.log')
$redisProcess.Id | Set-Content (Join-Path $projectRoot '.tools/redis.pid')
for ($attempt=0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Milliseconds 200
    $redisReply = & "$redisBin/redis-cli.exe" -h 127.0.0.1 -p 56379 ping 2>$null
    if ($redisReply -eq 'PONG') { Write-Output 'Isolated Redis started on 127.0.0.1:56379'; exit 0 }
}
throw 'Redis did not start; inspect .tools/redis-error.log'
