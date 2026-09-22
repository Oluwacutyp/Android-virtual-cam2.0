# Phase 1 — "Solid Foundation" (LOCKED)

> Scope locked by product owner: **Phase 1 contains no AI, no face swap, no voice
> clone, no injection.** Any such feature appearing in a Phase 1 branch is a bug.
> Later-phase modules are added *with* their phase — never scaffolded empty
> (anti-fake-build doctrine, blueprint §F3).

## Locked deliverables (from blueprint §C Phase 1)

| # | Deliverable | Status this increment | Notes |
|---|---|---|---|
| 1 | Project scaffold (modules, pinned BOM, CI) | ✅ Done | 6 modules incl. `engine-audio`; CI = build + unit + lint on GH Actions |
| 2 | `:engine-render` v1 (render thread, EGL, FBO graph, watchdog, diagnostics) | ✅ Done (needs device validation) | Own EGL context; FBO scene graph; 750ms watchdog; 1Hz diagnostics |
| 3 | Camera source (front/back, mirror, pro panel v1) | ✅ Done (needs device validation) | CameraX + Camera2Interop (WB/AF/FPS bind-time; zoom/torch/EV runtime); ISO lands with pro-camera increment |
| 4 | Image / video / color / text layers | ✅ Done (needs device validation) | Video: loop/mute/volume/speed via Media3; text via Canvas bridge |
| 5 | Scene system (multi-scene, layers, reorder, blend modes, cut/fade) | ✅ Done (needs device validation) | 13 blend modes; FADE with duration; scene dup/delete; reorder |
| 6 | Effects v1 (grade, sharpen, vignette, blur, LUTs) | 🟡 Partial | Grade/sharpen/vignette/blur shipped; **LUT import + `.cube` parsing = next increment** |
| 7 | Audio v1 (mic capture + meter, video audio mixing, limiter) | 🟡 Partial | Mic level meter shipped (real AudioRecord RMS); **mixer/monitor/limiter + video-audio muxing = next increment** |
| 8 | Recorder v1 (MP4 H.264+AAC, library, share) | ⬜ Next increment | `engine-output` module lands with it (no empty shells) |
| 9 | Settings + licenses/about | 🟡 Partial | Scene resolution + about shipped; licenses screen next increment |

> **Build status:** CI fully green (run 35777514514): assemble + unit tests +
> lint pass for all six modules. Lint is report-only during Phase 1
> (`abortOnError = false`) and is re-enforced at the exit gate. GL/camera/video
> runtime behavior remains device-gated below.

**Nothing on this list is faked:** every ✅ is wired end-to-end in code (no stub
classes, no placeholder renders), with the caveat that GL/camera behavior
requires real-device verification — which is exactly what the exit gate is for.

## Exit criteria (strictly enforced — owner condition #2)

Phase 1 is NOT done until all of the following pass on the device matrix
(Pixel-class flagship, Samsung A-class mid-tier, Android 10 and 15):

1. **Zero black frames**: 30-minute continuous session, 5 scenes × 3 sources,
   across 20 background/foreground + screen off/on stress cycles
   (`tools/stress-test.sh 20`) — engine presents frames continuously, no
   UNHEALTHY reports, no swap failures.
2. **Golden-frame shader tests**: every effect/blend shader produces
   pixel-stable output for fixed inputs (harness lands with `:tools-dev` in the
   next increment; until then, shader changes require manual A/B screenshots).
3. **Lifecycle**: rotation lock + backgrounding + task switch never shows a
   black or frozen-dead preview (frozen-last-frame is acceptable; black is not).
4. **Crash-free**: no ANR/crash during the full session.

## Next increments (in order)

1. `engine-output`: MP4 recorder (MediaCodec surface input + AAC from mic mix)
   + recordings library + share intent.
2. `engine-audio` v2: mic monitor toggle, master limiter, video-layer audio
   into the recording mix, latency readout.
3. Effects completion: LUT import (`.cube`), `:tools-dev` golden-frame harness.
4. Settings completion: licenses screen, theme hooks.
5. Then: Phase 1 exit run on device matrix → sign-off.
