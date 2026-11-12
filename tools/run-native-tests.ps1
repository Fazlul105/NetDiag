param([string]$Serial)
$ErrorActionPreference = 'Stop'
$localEnv = Join-Path $PSScriptRoot 'local-env.ps1'
if (Test-Path -LiteralPath $localEnv) { . $localEnv }
$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root 'native/build/android-arm64'
$toolchain = Join-Path $env:ANDROID_HOME 'ndk/27.2.12479018/build/cmake/android.toolchain.cmake'
& cmake -S (Join-Path $root 'native') -B $build -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$toolchain" '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-26' '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON' '-DCMAKE_BUILD_TYPE=Debug'
if ($LASTEXITCODE -ne 0) { throw 'Native test configuration failed' }
& cmake --build $build
if ($LASTEXITCODE -ne 0) { throw 'Native test build failed' }
if (!$Serial) { throw 'Native test binaries built. Supply -Serial to run on an authorized ARM64 Android device.' }
& adb -s $Serial push (Join-Path $build 'netdiag_tests') /data/local/tmp/netdiag_tests
if ($LASTEXITCODE -ne 0) { throw 'Could not copy native tests to phone' }
& adb -s $Serial shell chmod 755 /data/local/tmp/netdiag_tests
& adb -s $Serial shell /data/local/tmp/netdiag_tests
if ($LASTEXITCODE -ne 0) { throw 'Native tests failed' }
