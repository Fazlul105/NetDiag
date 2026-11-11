$ErrorActionPreference = 'Stop'
$localEnv = Join-Path $PSScriptRoot 'local-env.ps1'
if (Test-Path -LiteralPath $localEnv) { . $localEnv }
Write-Output "JAVA_HOME=$env:JAVA_HOME"
Write-Output "ANDROID_HOME=$env:ANDROID_HOME"
Write-Output "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
& java -version
& adb version
& cmake --version
& ninja --version
$hostFolder = if ($env:OS -eq 'Windows_NT') { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
$compiler = if ($env:OS -eq 'Windows_NT') { 'clang++.exe' } else { 'clang++' }
& (Join-Path $env:ANDROID_HOME "ndk/27.2.12479018/toolchains/llvm/prebuilt/$hostFolder/bin/$compiler") --version
& adb devices -l
