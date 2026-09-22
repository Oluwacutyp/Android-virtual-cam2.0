# Build Progress Log

Append-only log. One entry per increment. No status inflation: partial work is
marked partial.

## 2026-09-22 — Phase 1 increment 1: scaffold + render engine + camera (this commit)

**Built:**

- Gradle 8.9 / AGP 8.5.2 / Kotlin 2.0.20 multi-module scaffold, version catalog
  pinned (blueprint §B.10). Modules: `core-common`, `engine-render`,
  `engine-capture`, `engine-media`, `engine-audio`, `app`. (Phase 2+ modules
  deliberately absent — added with their phases.)
- CI: GitHub Actions — `assembleDebug` + `testDebugUnitTest` + `lintDebug`,
  reports + APK artifacts on failure.
- `engine-render` v1, complete:
  - `EglCore` (ES3 context, RECORDABLE-capable config w/ fallback chain, 1×1
    pbuffer keep-alive), `EglWindowSurface` (swap failures marked invalid, not
    thrown).
  - `RenderThread`: single GL thread + Choreographer loop; volatile latest-wins
    scene/transition commands; attach/detach outputs without pipeline restart.
  - `SceneRenderer`: premultiplied-alpha pipeline; direct hardware-blend path
    (NORMAL) + FBO ping-pong path (12 exotic blend modes); two-pass gaussian
    blur; 3×3 unsharp (2D sources); vignette; corner-radius SDF; SurfaceTexture
    ST-matrix applied on CPU; letterbox present with FADE transitions.
  - Shaders: GLSL ES 3.0, OES variant with `GL_OES_EGL_image_external_essl3`;
    compile/link failures throw with driver logs (fail-fast, no garbage render).
  - Watchdog: 250ms checks, 750ms stall → in-place surface re-init; health
    (HEALTHY/DEGRADED/UNHEALTHY) computed from recovery cadence; diagnostics
    collector with FPS, p50/p95/max, 8-bucket histogram, drop counter, recovery
    ring buffer; machine-parsable dump (`VCAM-DIAG v1`) for the stress script.
- `engine-capture` v1: CameraX Preview → engine Surface; ResolutionSelector
  (1280×720); Camera2Interop bind-time WB/AF/fixed-FPS/ISO; runtime zoom, EV,
  torch; tap-to-focus metering.
- `engine-media` v1: ExoPlayer controller (loop/mute/volume/speed/clip trim)
  feeding engine external textures; downscaled software-bitmap image loader.
- `engine-audio` v0.1: real mic RMS meter (AudioRecord, dB-scaled).
- `app` v1: Compose M3 studio UI — stage (SurfaceView → engine attach/detach),
  transitions row (cut/fade+duration), scene bin, layers rail, 8-button dock,
  full inspector sheet (transform/blend/13 effects/camera pro/text), live
  diagnostics sheet (histogram, recovery log, force-recovery test, dump copy),
  settings (scene resolution, about), permissions gate, Hilt DI, DataStore.
- Unit tests: `LayerGeometryTest` (fit/fill/stretch/mirror/rotation/clip),
  `DiagnosticsTest` (histogram buckets, percentiles), `RingBufferTest`.

**Verification status (honest):**

- Sandbox egress is allowlisted to github.com only → no local Gradle/SDK build
  possible. Verification loop = GitHub Actions on real runners; this commit is
  the first CI attempt. Iterate on CI logs until green.
- Static checks done locally: shell `bash -n`, XML/TOML/YAML well-formedness,
  Gradle wrapper jar fetched from the official gradle/gradle v8.9.0 tag.
- GL/camera runtime behavior intentionally NOT claimed verified — that is the
  Phase 1 exit gate on the device matrix (docs/PHASE1.md).

**Deliberately NOT in this increment (Phase 1 locked scope):** recorder, LUTs,
mixer/limiter, licenses screen — next increments; AI/face/voice/injection — later phases.
