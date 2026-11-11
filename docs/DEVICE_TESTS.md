# Device checks

Initial check completed on 12 November 2026: NetDiag installed and opened on
Redmi Note 11, Android 13 / API 33. Its screen displayed Wi-Fi and Internet
validated. The app was confirmed in the foreground, with no entries returned
by its process-filtered crash-buffer check. A subsequent manual Wi-Fi test also
passed session recording and CSV export; see
[the recorded result](results/2026-11-12-redmi-wifi.md). Other checks remain pending.

Record phone model, Android version, app commit, test conditions, and outcome.

1. **Passed:** launch with Wi-Fi connected; verify transport and validated status.
2. **Passed once:** start a session; disconnect/reconnect Wi-Fi using system
   controls; verify timestamped events. Airplane-mode testing remains pending.
3. Repeat with mobile data enabled. Expect the default transport to change only
   if the system actually selects mobile. This does not measure modem handover.
4. **Passed once:** stop and export CSV; confirm event order, both clock fields,
   and a readable CSV. Seven records were verified in the first export.
5. Start another session. Confirm previous events are cleared.
6. Rotate while recording. Confirm the session survives without duplicate callbacks.
7. Press Home while recording. Return and confirm the session ended with a reason.
8. Cancel export; retry. Confirm no crash. Try an unwritable destination if available.
9. Test Wi-Fi with no internet. Network availability must not imply validation.

Android's validation describes system observations; it is not a guarantee that
any particular diagnostic server is reachable. Logs may contain local network
identifiers; review exports before publishing them.
