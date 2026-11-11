# First live Wi-Fi recording and export

**Result:** passed for one manually operated disconnect/reconnect session.

Test date: 12 November 2026, Asia/Dhaka. Device: Redmi Note 11, Android 13
(API 33). App: NetDiag 0.1.0 debug. Source was a local development checkout;
the installed APK's SHA-256 is recorded in SETUP_STATUS.md.

The user started recording, switched Wi-Fi off and back on using the notification
shade, stopped recording, and exported CSV. The export was copied from the phone
to the local recordings directory and parsed on the workstation. The reviewed
export is included as [redmi-wifi-session.csv](../examples/redmi-wifi-session.csv).

## Observations

The CSV contains seven records, in this order:

1. SESSION_START with Wi-Fi and validated internet status.
2. LOST for the previous default network.
3. AVAILABLE for a new default network.
4. CAPABILITIES with Wi-Fi and internet not validated.
5. Another CAPABILITIES observation with internet not validated.
6. CAPABILITIES with Wi-Fi and internet validated.
7. SESSION_STOP with the reason User stopped recording.

All seven UTC timestamps parsed successfully. All elapsed-realtime timestamps
were strictly increasing. Both session boundary records were present, and the
five CSV columns matched the expected schema. The on-device timeline also showed
seven events and a completed session with export available.

## Measured intervals

Calculated from elapsed-realtime nanoseconds, rounded to milliseconds:

| Interval | Observed duration |
| --- | ---: |
| Entire session | 22.489 s |
| LOST to AVAILABLE | 7.279 s |
| AVAILABLE to first validated capability observation | 2.596 s |
| LOST to first validated capability observation | 9.875 s |

These are callback intervals from one manual scenario. They include the user's
delay before switching Wi-Fi back on and Android's validation behavior. They are
not a handover benchmark, a server reachability test, or an isolated measure of
network recovery performance. No cellular default-network transition was observed.

## Evidence and limits

Reviewed export: [redmi-wifi-session.csv](../examples/redmi-wifi-session.csv).
It contains event timestamps, temporary Android network handles, event types,
and connectivity descriptions; no SSIDs, IP addresses, or device identifiers.

Export SHA-256:
`AFD92755C54AACE2E32C572D7D8C47155CD4ADDABCB671FE08AF5FF57F876895`

User-provided on-device screenshots:
[session start](../images/session-start.png) and
[session recovery](../images/session-recovery.png).

This verifies basic foreground session recording, live Wi-Fi event collection,
session stopping, and a parseable export on this device. Rotation, backgrounding,
new-session reset, canceled export, and cellular transitions remain unverified.
No software bug or fix is claimed from this successful test.
