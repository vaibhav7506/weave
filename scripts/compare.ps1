$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$outputPath = Join-Path $projectRoot 'server/target/comparison-classes'
New-Item -ItemType Directory -Force $outputPath | Out-Null
$javaFiles = @(Get-ChildItem (Join-Path $projectRoot 'server/src/main/java/com/vaibhav/weave/crdt') -Filter '*.java' | ForEach-Object FullName)
$javaFiles += Join-Path $projectRoot 'server/src/main/java/com/vaibhav/weave/comparison/NaiveBoardSync.java'
$javaFiles += Join-Path $projectRoot 'server/src/main/java/com/vaibhav/weave/comparison/ComparisonDemo.java'
& javac --release 21 -d $outputPath @javaFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $outputPath com.vaibhav.weave.comparison.ComparisonDemo
exit $LASTEXITCODE
