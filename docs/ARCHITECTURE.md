# Initial connectivity recorder

`MainActivity` renders the Compose screen and owns the system CSV document picker.
`DiagnosticsModel` owns one ConnectivityManager callback, serializes callbacks
on the main looper, and exposes immutable screen snapshots through StateFlow.
`Session` is pure Kotlin recording/export logic and can be tested without a phone.

The callback follows the application's default network. `onAvailable` means a
network is ready for use; capability updates describe its transport and whether
Android has validated internet connectivity. Neither proves that a particular
TCP/UDP test endpoint is reachable. That is a later native diagnostic.

Each event records UTC for alignment with logs and elapsed realtime nanoseconds
for ordering/durations within one device boot. Wall time can jump when the
clock is adjusted. Monotonic timestamps should not be compared across boots.

Activity recreation retains the ViewModel and its callback. Leaving the app
normally stops recording and unregisters the callback. Long-running background
recording, persistent session storage, and a separate AIDL process are future
milestones. The current CSV intentionally contains observations, not invented
performance or handover measurements.

For an interview, first explain a real captured transition, how the callback
maps to the exported rows, and which conclusions the measurements do and do not
support. Then demonstrate a bug you actually reproduced, fixed, and verified.
