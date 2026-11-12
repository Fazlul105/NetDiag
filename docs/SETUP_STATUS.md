# Validation record

Local validation completed on 12 November 2026 (Asia/Dhaka).

## Toolchain

| Component | Tested version |
| --- | --- |
| JDK | Temurin 21.0.13+9 |
| Gradle wrapper | 8.13, distribution SHA-256 verified |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Compile / target SDK | 36 |
| Build tools | 36.0.0 |
| NDK | 27.2.12479018 |
| Physical device | Redmi Note 11, Android 13 / API 33 |

## Checks completed

- `assembleDebug`, `testDebugUnitTest`, and `lintDebug` succeeded locally.
- Session unit tests: 4 passed, 0 failures, 0 errors.
- Android lint: 0 errors and 5 dependency/Gradle update advisories. No lint
  warnings were suppressed; the tested dependency versions remain pinned.
- The installed debug APK's signature was verified with apksigner.
- Installation and first launch on the Redmi succeeded. Its screen displayed
  Wi-Fi transport and Internet validated status, with the app in the foreground.
- An app-process-filtered crash-buffer check after launch returned no entries.
- One manual Wi-Fi disconnect/reconnect recording and CSV export passed.
  Seven records had parseable UTC and strictly increasing monotonic timestamps.
  See the [device-test report](results/2026-11-12-redmi-wifi.md),
  [reviewed CSV](examples/redmi-wifi-session.csv), and [screenshots](images/).

The APK used for that device test was 10,780,953 bytes, with SHA-256:
`2764D32CCD72D8E622FB7EDB19067481C3850D52CE4F88919DE0563F18A04665`.
Debug APKs built on another workstation may differ because of signing keys and
other build inputs; this hash identifies the tested artifact.

A separate workstation smoke check cross-compiled a C++17 shared library for
Android ARM64 using NDK Clang 18.0.3 and CMake 3.22.1. This checked installation
only. It is outside the app and does not establish native networking or JNI
implementation in this repository.

## Known tool warning

The local SDK loader emits a non-fatal XML schema warning because the current
Android CLI writes newer package metadata than this pinned AGP understands.
The local build, unit tests, and lint commands completed successfully with it.

## Remaining device work

Repeatability, new-session reset, rotation, backgrounding, canceled export,
Wi-Fi without internet, and Wi-Fi/mobile transition checks remain pending in
[the device checklist](DEVICE_TESTS.md).

C++ TCP/UDP diagnostics and JNI were subsequently implemented and verified in
the [v0.2 native milestone](results/2026-11-12-native-diagnostics.md). That report
records its separate APK and test results. The v0.1 hash above still identifies
the original connectivity-recording device test. AIDL and audio transport remain
planned; no AOSP HAL, Linux driver, or physical bus integration is claimed.
