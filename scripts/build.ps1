param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
Push-Location $workspace
try {
    & npm run build --prefix client
    if ($LASTEXITCODE -ne 0) { throw 'Client build failed' }
    $mavenArguments = @('package', '-Pfrontend')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    & "$PSScriptRoot/maven.ps1" -MavenArgs $mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Server package failed' }
} finally { Pop-Location }
