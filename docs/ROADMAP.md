# Milestones

1. Connectivity recorder: default network callbacks, validated internet status,
   foreground recording, timestamped CSV, tests, device demonstration.
2. Native diagnostics: C++ TCP connect timing and UDP echo, explicit deadlines/
   cancellation, RAII cleanup, and Android JNI are implemented in v0.2. Ten
   native Android tests and seven JNI/ViewModel tests passed; see the native
   milestone report. Linux host CI verifies the portable core. Five physical
   phone-to-laptop Wi-Fi recovery trials and automatic TCP-result CSV recording
   are documented in results/2026-11-12-wifi-recovery.md. One-off UDP-result
   export and broader transition measurements remain follow-up work.
3. Android IPC: separate-process AIDL service with concurrent requests,
   callbacks, process death, and reconnection tests.
4. Audio: phone-to-computer streaming, sequence numbers, jitter buffer,
   measurements, and repeatable network interruption investigations.
5. Optional hardware/platform work: choose a supported board and one actual
   peripheral; document driver/bus integration. AOSP HAL work is a separate
   environment and storage decision. Simulations must be explicitly labelled.

For each milestone, preserve real measurements, build instructions, and one
reproducible investigation. Write bug reports for bugs actually discovered.
