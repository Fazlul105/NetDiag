# Milestones

1. Connectivity recorder: default network callbacks, validated internet status,
   foreground recording, timestamped CSV, tests, device demonstration.
2. Native diagnostics: portable C++ TCP connect timing and UDP echo against an
   owned endpoint, explicit timeouts/cancellation, RAII cleanup, Linux tests,
   then Android JNI. NDK installation alone does not establish C++ experience.
3. Android IPC: separate-process AIDL service with concurrent requests,
   callbacks, process death, and reconnection tests.
4. Audio: phone-to-computer streaming, sequence numbers, jitter buffer,
   measurements, and repeatable network interruption investigations.
5. Optional hardware/platform work: choose a supported board and one actual
   peripheral; document driver/bus integration. AOSP HAL work is a separate
   environment and storage decision. Simulations must be explicitly labelled.

For each milestone, preserve real measurements, build instructions, and one
reproducible investigation. Write bug reports for bugs actually discovered.
