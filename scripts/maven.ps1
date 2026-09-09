param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$mavenVersion = '3.9.16'
$mavenPath = Join-Path $projectRoot ".tools/apache-maven-$mavenVersion/bin/mvn.cmd"
if (!(Test-Path $mavenPath)) {
    $archive = Join-Path $projectRoot ".tools/apache-maven-$mavenVersion-bin.zip"
    New-Item -ItemType Directory -Force (Split-Path $archive) | Out-Null
    $url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
    Invoke-WebRequest $url -OutFile $archive
    $expectedHash = (Invoke-WebRequest "$url.sha512").Content.Trim().Split(' ')[0]
    if ((Get-FileHash $archive -Algorithm SHA512).Hash -ne $expectedHash) { throw 'Maven archive checksum mismatch' }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $projectRoot '.tools') -Force
}
& $mavenPath -B -ntp -f (Join-Path $projectRoot 'server/pom.xml') "-Dmaven.repo.local=$projectRoot/.tools/m2" @MavenArgs
exit $LASTEXITCODE
