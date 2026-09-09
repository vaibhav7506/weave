$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$secretPath = Join-Path $projectRoot '.tools/token-secret'
if (!(Test-Path $secretPath)) {
    New-Item -ItemType Directory -Force (Split-Path $secretPath) | Out-Null
    [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48)) | Set-Content $secretPath
}
if (!$env:WEAVE_TOKEN_SECRET) { $env:WEAVE_TOKEN_SECRET = (Get-Content $secretPath -Raw).Trim() }
& java -jar (Join-Path $projectRoot 'server/target/weave-server-0.2.0.jar')
exit $LASTEXITCODE
