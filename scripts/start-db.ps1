$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$postgresBin = 'C:\Program Files\PostgreSQL\18\bin'
$dataPath = Join-Path $projectRoot '.tools/postgres-data'
if (!(Test-Path (Join-Path $dataPath 'PG_VERSION'))) {
    & "$postgresBin/initdb.exe" -D $dataPath -U weave -A trust --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
}
& "$postgresBin/pg_ctl.exe" -D $dataPath status
if ($LASTEXITCODE -ne 0) {
    & "$postgresBin/pg_ctl.exe" -D $dataPath -l (Join-Path $projectRoot '.tools/postgres.log') -o '-p 55432 -h 127.0.0.1' -w start
    if ($LASTEXITCODE -ne 0) { throw 'Postgres startup failed' }
}
$exists = & "$postgresBin/psql.exe" -h 127.0.0.1 -p 55432 -U weave -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='weave'"
if ($exists -ne '1') { & "$postgresBin/createdb.exe" -h 127.0.0.1 -p 55432 -U weave weave }
if ($LASTEXITCODE -ne 0) { throw 'Database setup failed' }
Write-Output 'Isolated development database: 127.0.0.1:55432/weave (loopback trust authentication)'
