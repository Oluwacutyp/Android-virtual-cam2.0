# VCam Studio

A free, pure-Android **virtual camera studio**: professional scene compositor,
real-time sources, effects, recording and streaming. 100% free, no watermark,
no subscription, fully offline. (Live face swap / voice cloning / system-wide
virtual camera arrive in later phases — see `docs/BLUEPRINT.md`.)

**Current status:** Phase 1 in progress — locked scope in
[`docs/PHASE1.md`](docs/PHASE1.md), plan and research in
[`docs/BLUEPRINT.md`](docs/BLUEPRINT.md), append-only build log in
[`docs/PROGRESS.md`](docs/PROGRESS.md).

## Modules

| Module | Purpose |
|---|---|
| `app` | Compose Material 3 studio UI, DI, settings, onboarding |
| `core-common` | Clock, dispatchers, ring buffer, result types (pure) |
| `engine-render` | Own GL ES 3.0 engine: one EGL context/thread, FBO scene graph, watchdog, diagnostics — the "never black" core |
| `engine-capture` | CameraX source + Camera2 pro controls |
| `engine-media` | ExoPlayer video layers (loop/mute/speed/trim) + image loading |
| `engine-audio` | Mic metering now; routing/DSP/limiter in the Phase 1 audio increment |

Later phases add: `engine-output` (record/stream), `engine-ai-face`,
`engine-ai-audio`, `vcam-module` (LSPosed, optional, root) — always *with*
their phase, never as empty shells.

## Build

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # lint
```

Requirements: JDK 17, Android SDK 34. CI builds on every push
(`.github/workflows/ci.yml`).

## Device stress test (Phase 1 exit gate)

```bash
./gradlew assembleDebug
tools/stress-test.sh 20           # 20 background/screen cycles; PASS/FAIL
```

See `docs/PHASE1.md` for the full (strictly enforced) exit criteria.

## Principles

- **Nothing ships because it compiles** — phases end on device, with demo checklists.
- **No black frames, ever** — the render engine holds last-good-frame, self-heals via watchdog, and exposes its own vitals in-app.
- **Honesty in the UI** — features that are partial are labeled partial; nothing is faked.
- **Privacy-first** — no internet permission in Phase 1; no telemetry without opt-in; no watermark; no paid tier.

License: MIT (see `LICENSE`).
