$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $workspace '.env'
if (Test-Path -LiteralPath $envFile) { Write-Output 'Existing .env preserved.'; exit 0 }
function New-WeaveSecret {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}
$settings = "POSTGRES_PASSWORD=$(New-WeaveSecret)`nWEAVE_TOKEN_SECRET=$(New-WeaveSecret)`nWEAVE_HTTP_PORT=5188`nWEAVE_ORIGINS=http://localhost:5188,http://127.0.0.1:5188`n"
[System.IO.File]::WriteAllText($envFile, $settings)
Write-Output 'Created local Compose secrets in .env. Keep this file private and retain it across restarts.'
