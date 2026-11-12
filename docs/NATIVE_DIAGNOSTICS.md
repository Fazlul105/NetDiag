# C++ TCP/UDP diagnostics and JNI

## Implementation flow

```text
Compose form -> NativeProbeModel -> Dispatchers.IO -> NativeBridge
    -> JNI handle registry -> portable C++ probe -> typed result -> Compose
```

`native/src/probe.cpp` owns socket creation, connection, polling, deadlines,
payload checking, and descriptor cleanup. It builds on Linux and Android.
`app/src/main/cpp/jni_bridge.cpp` registers JNI methods and converts inputs and
results; networking logic stays outside JNI. `NativeProbeModel.kt` owns the
request lifetime and dispatches blocking work off the main thread.

The form accepts numeric IPv4/IPv6, port 1-65535, and timeout 100-10000 ms.
DNS and IPv6 zone IDs are deliberately outside this version. This avoids an
unbounded blocking hostname lookup inside an otherwise bounded socket probe.

## TCP: connect timing

1. Create a nonblocking, close-on-exec socket, immediately owned by an RAII object.
2. On Android, bind non-loopback traffic to the network selected at request start.
3. Record a monotonic start time and call `connect`.
4. If connection is in progress, poll until writable, cancelled, or timed out.
5. Read `SO_ERROR`: writability alone does not prove a successful connection.
6. Return connect time on success, or a typed failure and POSIX error number.
7. Close the descriptor automatically on every return path.

No HTTP request or application-level handshake is sent. A successful TCP probe
proves connection establishment to that address and port, not application health.

## UDP: echo round trip

The client connects a UDP socket to the selected peer, sends one 32-byte payload
with a request identifier, and waits for an exact echo. UDP `connect` establishes
the peer association; it is not a TCP-style handshake. The kernel filters other
peers, and the client rejects a changed, truncated, or oversized payload.

An exact matching response produces an RTT. A silent peer produces TIMEOUT;
a malformed echo produces PROTOCOL_ERROR. A single UDP probe is not a packet-loss
estimate or throughput measurement.

## Deadlines, cancellation, and ownership

Socket waits share one steady-clock deadline. Each `poll` slice is at most 50 ms,
so an atomic cancellation flag can be checked without closing another thread's
descriptor. Closing from another thread could race with descriptor reuse.
The 50 ms slice is not a hard real-time guarantee: scheduling and Android system
calls can add delay. DNS is excluded, and no wait restarts the full timeout.

JNI returns integer request IDs rather than raw pointers. A mutex-protected
registry owns shared cancellation objects. Executing calls retain shared
ownership, so releasing a handle cannot free an object a worker is still using.
The managed coroutine's completion releases the ID, including early cancellation.
Leaving the app cancels active native work; orientation changes retain the model.

## Build and test

Linux host tests use only loopback endpoints and require a C++17 compiler:

```sh
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Debug
cmake --build native/build
ctest --test-dir native/build --output-on-failure
```

The ten test cases cover validation, TCP connection/refusal, UDP echo/timeout/
malformed responses, cancellation before/during work, repeated failure-path
descriptor cleanup, and IPv6 TCP/UDP. No public internet service is probed.

On the original Windows development machine, `tools/run-native-tests.ps1 -Serial
<device-serial>` cross-compiles the same executable with the NDK and runs it under
`/data/local/tmp` on an authorized ARM64 Android device.

Build the app and run the managed and JNI integration checks:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

The connected tests use owned Java loopback servers, exercise native JNI calls,
and verify that the ViewModel's worker returns TCP/UDP results to its observable
UI state. They require an authorized phone or emulator and permission to install
the separate test APK. Actual button interaction is a separate manual check;
ActivityScenario-based launch stalled on the test Redmi even though the app
opened normally from ADB.

## Try an echo server

On a Linux machine you control:

```sh
native/build/netdiag_echo_server 127.0.0.1 7777
```

To reach that server from another device, bind it to the appropriate LAN address
and enter that address on the phone. `127.0.0.1` on the phone means the phone
itself, not the laptop. Both TCP and UDP use the configured port. Any firewall
access must be limited to your intended test network. Stop the server with Ctrl+C.

For a device-local demo, cross-compile the server with the NDK, copy it to
`/data/local/tmp`, and launch it bound to `127.0.0.1`. The app can then probe that
address. Such results demonstrate native/JNI integration, not Wi-Fi latency.

After `tools/run-native-tests.ps1 -Serial <device-serial>` has built the ARM64
binaries, these commands run that demo server in the foreground:

```sh
adb push native/build/android-arm64/netdiag_echo_server /data/local/tmp/netdiag_echo_server
adb shell chmod 755 /data/local/tmp/netdiag_echo_server
adb shell /data/local/tmp/netdiag_echo_server 127.0.0.1 7777
```

Keep that terminal open while trying 127.0.0.1, port 7777 in the app. Stop the
foreground server with Ctrl+C. A server must be running for a successful UDP
echo; entering an arbitrary internet service's port is not an echo test.

## Current limits

Only the most recent native result is displayed. It is not yet part of the
connectivity-session CSV. Supported packaged ABIs are arm64-v8a and x86_64.
The C++/JNI implementation is application-level networking; AIDL, HAL, modem,
kernel driver, audio, and hardware buses remain separate milestones.

## Platform references

- [Android JNI tips](https://developer.android.com/ndk/guides/jni-tips)
- [Gradle external native builds](https://developer.android.com/studio/projects/gradle-external-native-builds)
- [NDK network binding](https://developer.android.com/ndk/reference/group/networking)
