# Redmi-to-laptop Wi-Fi recovery experiment

Five physical Wi-Fi disconnect/reconnect trials completed on 12 November 2026.
All five reached the laptop's TCP listener. Median observed reachability after
the recovered Wi-Fi capability callback was 207.602 ms. Trial 3 required three
attempts and reached the laptop 1055.630 ms after Android reported validated
internet access.

## Conditions and method

- Redmi Note 11, Android 13 / API 33, ARM64, connected by USB for test control.
- Windows laptop and phone on the same Wi-Fi LAN. The laptop remained connected.
- Mobile data was off before, during, and after the experiment. Wi-Fi was restored.
- PowerShell TCP listener on the laptop's Wi-Fi IPv4 address, port 7777.
- The baseline native TCP connection to the laptop succeeded before any toggles.
- An explicitly enabled Android instrumentation experiment called the same
  `DiagnosticsModel.startRecovery` used by the app's **Record Wi-Fi recovery**
  button. It disabled the phone's Wi-Fi, waited for a recorded loss, held it off
  for five seconds, enabled it, and waited for recovery and validation.
- Each recovered Wi-Fi network triggered one trial. Repeated capability updates
  did not start duplicate trials. Probes used that network's handle through JNI.
- One native TCP connection attempt ran at a time, with a 500 ms timeout and a
  250 ms pause after each failed attempt, within a 15-second observation budget.
- Trials waited for internet validation and a one-second pause before the next
  disconnection. The five-trial instrumentation run completed in 49.05 seconds.

The tested build was based on main commit `f111daa` with the recovery recorder,
tracker tests, and opt-in experiment added. The implementation and this report
are committed together so the exact experiment can be reproduced.

## Measurements

Both elapsed columns start at `RECOVERY_START`, recorded in the first Wi-Fi
capability callback after a loss. TCP recovery ends at the first successful
`PROBE_RESULT`, when its result reaches the model's main thread. Internet
validation ends at the first validated capability callback for that network.
All differences use the phone's `elapsedRealtimeNanos()` clock.

| Trial | TCP attempts | Observed TCP recovery (ms) | Internet validated (ms) |
| --- | --- | --- | --- |
| 1 | 1 | 207.602 | 346.252 |
| 2 | 1 | 63.603 | 349.534 |
| 3 | 3 | 1659.581 | 603.951 |
| 4 | 1 | 122.860 | 269.133 |
| 5 | 1 | 298.866 | 424.371 |

- Successes within the observation budget: 5/5.
- Median observed TCP recovery: 207.602 ms.
- Minimum/maximum: 63.603 / 1659.581 ms.
- Seven TCP attempts in total, including two 500 ms timeouts in trial 3.

Inspect the [reviewed CSV](../examples/redmi-wifi-recovery.csv). Its laptop IP
and Android network IDs were replaced with labels. Event timestamps, statuses,
and measurements are preserved. Original exports and host logs remain in the
ignored `recordings/` directory.

## Investigation

Trial 3 recorded its first timeout at 502.761 ms, Android internet validation at
603.951 ms, a second timeout at 1257.715 ms, and a successful third attempt at
1659.581 ms. The target became observably reachable about 1.056 seconds after
Android reported internet validation. Four other trials reached the laptop
before that validation callback.

The app therefore records Android validation and target reachability as separate
observations. The two timeouts establish the failed connection attempts. Their
underlying LAN cause remains unisolated because this experiment did not collect
packet captures or access-point traces. No app or native-code defect was
established by those timeouts.

These are five observations on one phone/LAN. The recovery metric includes
coroutine scheduling, native connection work, and delivery back to the model.
For example, the first trial's first probe started about 116.6 ms after its
recovery marker. Retry spacing and timeouts also limit when success can be
observed. The metric begins after Wi-Fi is reported again, so the five-second
off period and Wi-Fi reassociation precede it.

Phone and laptop wall clocks differed by several minutes. Host log timestamps
were used to confirm accepted connections only. They were not subtracted from
phone timestamps. Android API definitions are documented in
[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback).

## Reproduce

Build and install the app and test APK. Connect both devices to the same Wi-Fi,
turn mobile data off on the phone, and start the listener on the laptop:

```powershell
./tools/start-recovery-server.ps1 -Address <laptop-wifi-ip> -Port 7777
```

Keep the server running. In another terminal run the explicitly enabled
experiment. It toggles the phone's Wi-Fi five times and re-enables Wi-Fi in its
cleanup path. The ordinary automated test suite skips this experiment.

```sh
adb shell am instrument -w -r -e class dev.netdiag.WifiRecoveryExperiment -e wifiExperiment true -e serverIp <laptop-wifi-ip> -e serverPort 7777 dev.netdiag.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as dev.netdiag cat files/wifi-recovery.csv
python tools/summarize-wifi-recovery.py <saved-csv-path>
```

For manual recording, enter the laptop IP/port in NetDiag, select **Record Wi-Fi
recovery**, toggle Wi-Fi while keeping the app foreground, stop the session,
then export CSV. A UI smoke check confirmed that this button starts recording
and adds the session/configuration events. The five measured trials above used
the instrumentation harness.

## Verification

The debug app and instrumentation APK built successfully. All 10 JVM tests
passed, including three tracker regression tests for duplicate capability
events, interrupted trials, and reset. Seven existing Android JNI/ViewModel
tests passed, followed by the opt-in physical experiment. Lint reported zero
errors and seven existing version-update advisories. The installed app opened
successfully after the experiment.
