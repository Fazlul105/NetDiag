# NetDiag working instructions

NetDiag is a graduate engineering portfolio project about Android connectivity,
native networking, IPC, and eventually audio diagnostics.

- Keep changes small and explain systems concepts and design decisions plainly.
- Use real device observations. Label fixtures, simulations, and unverified claims.
- Work through the milestones in docs/ROADMAP.md in order.
- Do not claim modem, HAL, kernel, or physical bus experience from an app API.
- Put workstation-specific paths and environment setup in ignored
  tools/local-env.ps1. Keep shared build instructions portable.
- On the original Windows workstation, keep installations and large caches on D:.
- Build with the Gradle wrapper: assembleDebug testDebugUnitTest lintDebug.
- Verify changes with the relevant build, lint, and tests. Report what actually ran.
- Keep secrets, machine paths, recordings, and generated build outputs out of Git.
- Preserve unrelated files and existing machine configuration.
