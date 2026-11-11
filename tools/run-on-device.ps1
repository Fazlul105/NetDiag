param([string]$Serial)
$ErrorActionPreference = 'Stop'
$localEnv = Join-Path $PSScriptRoot 'local-env.ps1'
if (Test-Path -LiteralPath $localEnv) { . $localEnv }
$project = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
if (!(Test-Path -LiteralPath $apk)) { throw 'Build the debug APK first.' }
$output = & adb devices
if ($LASTEXITCODE -ne 0) { throw 'Could not query Android devices.' }
$devices = @($output | ForEach-Object {
    if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
if ($Serial) {
    if ($Serial -notin $devices) { throw 'The selected device is not connected and authorized.' }
} elseif ($devices.Count -eq 1) {
    $Serial = $devices[0]
} else {
    throw 'Connect and authorize one phone, or supply -Serial for one authorized device.'
}
& adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& adb -s $Serial shell am start -n dev.netdiag/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
