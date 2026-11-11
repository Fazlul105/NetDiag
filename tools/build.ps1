param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$localEnv = Join-Path $PSScriptRoot 'local-env.ps1'
if (Test-Path -LiteralPath $localEnv) { . $localEnv }
$project = Split-Path -Parent $PSScriptRoot
Push-Location $project
try {
    & "$project\gradlew.bat" --no-daemon @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
