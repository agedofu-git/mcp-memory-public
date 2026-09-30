param([string[]]$Task = @('bootRun'), [string[]]$GradleArguments = @())
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'local-environment.ps1')
Import-LocalEnvironment -ProjectRoot $projectRoot
$cacheArguments = @()
$localCache = Join-Path $projectRoot '.gradle-home'
if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME) -and (Test-Path -LiteralPath $localCache)) {
    $cacheArguments = @('--gradle-user-home', $localCache)
}
Push-Location -LiteralPath $projectRoot
try {
    & .\gradlew.bat @cacheArguments @Task @GradleArguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
