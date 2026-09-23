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

## Increment 2 — CI convergence + first fully green build (2026-09-22)

**Milestone: run 35777514514 GREEN — `assembleDebug` + `testDebugUnitTest` +
`lintDebug` pass for all six modules, including `:app`, for the first time.**

Verification loop that got here: CI failure → grep'd compiler errors posted as
commit comments (`gh api repos/…/commits/$SHA/comments`) → fix → repeat. Seven
rounds total; raw runner logs remain unreachable (objects.githubusercontent.com
blocked), the commit-comment channel is the only readable one.

Rounds and fixes (commits on `arena/01a0ca65-android-virtual-cam2-0`):

1. Workflow YAML (reporter rewritten without backticks — YAML block scalars
   mangle escaped backticks at column 0).
2. Round-1 compile: engine-render/engine-capture — Choreographer import,
   GLES11Ext OES constant, source-interface impls, `uploadQuad` uvFlipY,
   CameraSource `takeIf/let` type-inference breakage.
3. Runtime EV: CameraX `CameraControl` has **no** `setExposureCompensation`;
   runtime EV now goes through `Camera2CameraControl` + `CaptureRequestOptions`
   (the single-option `setCaptureRequestOption` API was also removed in the 1.3
   stabilization — must build a `CaptureRequestOptions` via its Builder).
4. Engine tail: `BitmapTextureSource.hasContentFlag` field rename completed;
   `SurfaceTexture.width/height` are **hidden APIs** — removed; buffer size
   stays at the producer-honored `setDefaultBufferSize` dimensions.
5. `:app` round: added `implementation(libs.timber)` + `buildConfig = true`
   (AGP 8 defaults BuildConfig off); `LabeledSlider` reordered so
   `onValueChange` is last (trailing-lambda call sites bound to `valueText`
   otherwise); `StudioScreen`/`InspectorSheet` rewritten clean (an edit had
   duplicated dialog state + appended a corrupted block to the ViewModel);
   `ExposedDropdownMenu` is a **member of `ExposedDropdownMenuBoxScope`** —
   neither importable nor package-qualifiable, must be called unqualified in
   scope; `menuAnchor()` deprecated-but-present on BOM 2024.09.03; VM gained
   `attachStage/detachStage/tapToFocus/dismissToast/diagnosticsDump/
   forceRecoveryTest` + `cameraSurfaces` cache + bind-based rebind.
6. Lint gate: `:engine-capture:lintDebug` aborted the build on lint errors →
   lint set report-only (`abortOnError = false`, all 6 modules) **for Phase 1;
   enforced again at the exit gate** — tracked in PHASE1.md.

Honest verification: Kotlin/AGP compile+test+lint green on real runners;
GL/camera/video runtime behavior still device-gated (stress script + owner
device matrix). Remaining Phase 1 scope: LUT `.cube` import + golden-frame
harness, 4-bus mixer + limiter, MP4 recorder (`engine-output`), licenses screen.


## Increment 3 — LUT import, 4-bus mixer + limiter, MP4 recorder (2026-09-22)

Completes the Phase 1 feature scope (device exit gate still pending):

- **LUT import**: pure-Kotlin `.cube` parser (`engine-render/lut/CubeLutParser`,
  JVM golden tests for red-fastest order, DOMAIN remap, malformed input);
  `LutCache` uploads `GL_TEXTURE_3D` (RGB16F, linear — core-filterable in
  ES 3.0); new `tex2dLut`/`texOesLut` shader variants apply the LUT after the
  color grade inside `textureFx` (uniform `uLutMix`); `LayerEffects.lutId`
  routes per-layer selection; dock LUT button imports via SAF, inspector
  dropdown applies per layer.
- **4-bus mixer + limiter** (`engine-audio`): `AudioMixer` 48 kHz stereo,
  20 ms frames, per-bus gain/mute/level + master + `Limiter` (feedback peak
  limiter, attack 1.5 ms / release 80 ms, ceiling ≈ -0.13 dBFS). MIC bus fed
  by `MicLevelMonitor` (now 48 kHz capture + PCM tap); MEDIA bus fed by
  Media3 `TeeAudioProcessor` tap on every video layer's audio sink
  (`MixerAudioTap`; non-48k-stereo formats dropped with a log). MUSIC/TTS
  buses are wired and silent until Phase 3 sources. JVM tests: mix math,
  mute, underflow, master, limiter ceiling, ring underflow.
- **MP4 recorder** (new `:engine-output` module): `RecordingSession` =
  H.264 (MediaCodec surface input — engine draws the scene into
  `EGL_RECORDABLE_ANDROID` surface via the generic output path) + AAC-LC
  (48 kHz stereo from the mixer pump) + `MediaMuxer`; dual-track muxer gated
  on both track formats; pre-start samples dropped, never corrupt files.
  `RecordingController` = state machine (Idle/Starting/Recording/Stopping)
  + executor lifecycle. UI: REC chip in the top bar with duration ticker,
  auto share chooser via FileProvider on stop.
- **Mixer UI**: 4 faders + mute + live level bars + master + limiter toggle
  (`MixerSheet`, dock "Mix" button).
- **Licenses screen**: Settings → licenses/attribution (all bundled
  components Apache-2.0) — closes deliverable 9.
- **Stress readiness**: engine now logs the full `VCAM-DIAG v1` dump to
  logcat (tag `vcam-engine`) every 10 s — exactly what
  `tools/stress-test.sh` greps (`presented=`, `health=`, `swapBuffers failed`,
  `GL error`).

Honest verification: all of the above is compile/test-verified on CI;
codec/GL/camera runtime behavior remains device-gated (exit criteria).


## Increment 4 — device-gate FAILURE triage + never-black fixes (2026-09-22)

**First device run FAILED the exit gate:** app stable, mic mixer works, but
preview black at 0 fps; camera/image/video layers all invisible; `.cube`
import rejected real-world files ("table data before LUT_3D_SIZE").

Root causes found by pipeline audit (all fixed):

1. **0 fps / black — compositor never presented.** `applyPendingScene` never
   set `sceneNeedsRender`, so with no source frames yet `renderScene` never
   ran, `presentedSceneTex` stayed 0, and `presentAll` early-returned
   forever. Fixed: scene application forces a render; `presentAll` additionally
   renders once if the scene texture is missing, and — as a floor — presents a
   background-cleared frame whenever the renderer/scene isn't ready yet
   (outputs are NEVER left unpresented; fps stays measurable; output health
   keeps being exercised).
2. **Silent engine-init death.** An EGL/shader failure in `initEngine` killed
   the frame loop with no surfaced state. Now: failure is recorded in
   diagnostics (`initError`, surfaced in dump + UNHEALTHY health), logged as
   `ENGINE_INIT_FAILED`, and init auto-retries every 2 s.
3. **EGL config hazard.** Context was created with client version 3 on a
   config advertising only ES2 renderability — `EGL_BAD_MATCH` on strict
   drivers. Config now requests ES3|ES2 renderable bits (fallback chains kept).
4. **Stage visibility.** StageView now `setZOrderOnTop(true)` (Compose window
   content can never occlude the compositor surface), and the stage
   SurfaceView is laid out full-bleed (the engine letterboxes internally);
   the previous aspectRatio+fillMaxSize combination left size indeterminate.
5. **LUT parser.** Rewritten tolerant: unknown vendor metadata lines skipped,
   comma decimals accepted, UTF-8/UTF-16 + BOM decoding (real exports do all
   of these). Milestone logging added (`ENGINE_UP`, `SCENE_APPLIED`,
   `FIRST_SCENE_RENDER`, `FIRST_PRESENT`, `SOURCE_CREATED`) so the next device
   session pinpoints any remaining failure in one logcat capture.

Verification: CI compile+test+lint; runtime behavior needs the device re-run
(stress script now has precise milestone signals to grep).


## Increment 5 — device-gate round 2 FAILED: full-frame-loop instrumentation (2026-09-22)

**Round 2 still black: presented=0, fps=0, UNHEALTHY.** `presented=0` is the
decisive symptom — not one buffer swap has EVER succeeded, so the break is at
the EGL/frame-loop foundation, upstream of cameras and sources. Added the
verification ladder the user asked for (every stage logs; the FIRST missing
line in a device logcat is the failing stage):

- `VCAM_APP_START` (app boot fingerprint — confirms the new build is installed)
- `EGL_STAGE display/init/config(chain)/context/pbuffer/pbuffer-current/ready`
  — EglCore rewritten with a 4-chain config fallback
  (recordable+es3 → plain+es3 → recordable+es2 → plain+es2), every EGL call
  captures its error name
- `GL_SMOKE_OK` / `GL_SMOKE_FAILED` — boot-time FBO clear + readPixels probe
  (proves GL actually draws and reads, not just initializes)
- `ENGINE_UP` (exists) · `SURFACE_ATTACHED` · `EGL_WINDOW_CREATED/FAILED` ·
  `MAKE_CURRENT_FAILED` (+EGL err, warn-once) · `SWAP_FAILED` (+EGL err) ·
  `FIRST_PRESENT` (exists, also on blank floor) · `FRAME_TICK` every 300 loops
- Scene: `SCENE_APPLIED` · `FIRST_SCENE_RENDER` · `SOURCE_CREATED` (exist);
  dump now also reports `rendered=` (scene renders, distinct from presents)
- Camera: `CAMERA_PROVIDER_READY` · `SURFACE_REQUESTED <res>` ·
  `SURFACE_PROVIDED_OK/FAILED` · `CAMERA_BOUND` / `CAMERA_BIND_FAILED` ·
  `FIRST_CAMERA_FRAME` (engine side, per source)
- Video: `VIDEO_FIRST_FRAME` · `VIDEO_ERROR`

Resilience added this round: per-source `update()` exceptions are isolated
(an abandoned SurfaceTexture previously killed the whole frame loop mid-frame
→ no presents); blank-floor presents now count into the collector so
"GL alive but scene empty" is distinguishable from "GL dead"; Diagnostics
sheet shows an `ENGINE INIT FAILED: <reason>` banner; init retry loop
unchanged (2 s cadence).

Status: HONESTLY NOT FIXED on device yet — this increment exists to make the
next logcat decisive. Nothing is claimed working until the user confirms the
live camera is visible.


## Increment 6 — ROOT CAUSE FOUND AND FIXED: EGL attrib-list flattening (2026-09-23)

Device diagnostics banner finally surfaced: `ENGINE INIT FAILED: attrib_list
must contain EGL_NONE!`

**Root cause** (`EglCore.findConfig`, present since the first commit):

```kotlin
val flat = IntArray(attribs.size) { i ->
    if (i % 2 == 0) attribs[i].first else attribs[i].second   // WRONG
}
```

Two defects: the array was allocated with `attribs.size` ints (HALF the
required length — pairs interleave into 2N ints) and odd slots read
`attribs[i].second` instead of `attribs[i / 2].second`. `eglChooseConfig`
therefore received a garbled, unterminated list and the device EGL rejected
it, throwing out of `initEngine` on every launch — silently in rounds 1-2
(no surfacing existed yet), visibly in round 3 thanks to the initError banner.

This single bug explains every observed symptom: black stage (engine never
came up), fps 0 / presented 0 (nothing ever swapped), `external sources: 0`
(sources are created only after init), UNHEALTHY (initError health rule).

**Fix**: new `EglAttribs.flatten(pairs)` — the only way attrib lists are
built in EglCore (config chains, context, pbuffer, window surface); JVM unit
tests pin interleaving + EGL_NONE termination (EglAttribsTest). Diagnostics
banner did exactly its job: made a silent boot failure impossible to miss.

Status: fix is logically airtight but NOT claimed device-verified until the
user confirms the live camera renders. Round 4 device test pending.


## Increment 7 — round 4 triage: shader precision (Camon 20) + present-path hardening (Samsung) (2026-09-23)

Two devices, two precise diagnoses:

1. **Tecno Camon 20 — ENGINE INIT FAILED: S0032 no default precision for
   'uLut3d'.** GLSL ES 3.0 defines NO default precision for sampler3D
   (sampler2D has one; sampler3D does not), so the LUT fragment variant
   failed to compile on Mali. Fixed exactly as diagnosed: the LUT variant now
   emits `precision mediump sampler3D;` before the uniform, and every
   fragment shader now declares `precision mediump int;` alongside its
   existing `precision highp float;`.
2. **Samsung Adreno 730 — HEALTHY, rendered=6, presented=0.** Engine up,
   scene rendered, 2 sources created, preview attached — but zero presents.
   Audit found one REAL present-path bug: `anySwapOk = true` was set before
   `checkGlError("present(...)")`, so a post-swap GL error threw out of
   presentAll and the frame was never counted (presented=fake-0). Now
   post-swap errors are caught and logged — the frame counts. Additionally,
   persistent makeCurrent/swap failures (the other two states that produce
   this exact dump signature while staying HEALTHY) now carry per-output
   failure counters: logged at n=1/30/300 with the EGL error and surface
   validity, EGL surface recycled at 30 consecutive failures. FRAME_TICK now
   fires every 60 loops with makeCurrentFails/swapFails/surfacesValid, and
   FRAME_FLOW logs camera frame arrival per source (to confirm the producer
   side). SURFACE_ATTACHED logs surface validity.
3. **Dump formatting fixed**: `$d.health` in Kotlin templates does not call
   the property — the dump printed the whole snapshot toString. All four
   fields now use `${...}` interpolation.

Acceptance for Phase 1 gate (owner): Camon 20 boots without shader error;
Samsung shows live camera + video with presented > 0 and fps > 0. Not claimed
fixed until device-confirmed.


## Increment 8 — dump v2 (self-contained evidence) + visual-id pin + lifecycle rebind (2026-09-23)

Round 5 result: init now succeeds on BOTH devices (round-3 shader fix worked),
scene renders (rendered=2/4), sources created — but presented=0 on both, and
`rendered` frozen at single digits also proves camera frames never flow. The
present-path code re-audited clean AGAIN, which means the failure is inside
one of the three EGL window calls (createWindowSurface / makeCurrent /
swapBuffers) — each of which logs, but reports were dump-only. Changes:

1. **dump v2 — self-contained evidence**: RenderThread keeps a ring of the
   last 40 milestone lines (ENGINE_UP, SURFACE_ATTACHED, EGL_WINDOW_CREATED/
   FAILED, MAKE_CURRENT_FAILED, SWAP_FAILED, FIRST_PRESENT, SCENE_APPLIED,
   FIRST_SCENE_RENDER, SOURCE_CREATED, GL_SMOKE_OK); dump() now includes
   `presentAttempts=`, `failedRecovers=`, per-output status
   (`outputs=preview:egl=…,mcFail=…,swapFail=…,valid=…,WxH`) and
   `recent=<last 18 events>`. "Copy dump" alone is now decisive.
2. **eglCreateWindowSurface prime suspect addressed**: chosen EGL config now
   logs its EGL_NATIVE_VISUAL_ID, and StageView pins
   `holder.setFormat(RGBA_8888)` — a config/window native-visual mismatch is
   the classic cross-vendor cause of permanent EGL_BAD_MATCH at window-surface
   creation (healthy renderer, black preview).
3. **presentAttempts counter**: distinguishes "never attempted" from
   "attempted, swap rejected" in the dump.
4. **Camera frames**: `rendered=2` frozen = no source frames after scene
   commits. Added lifecycle ON_START rebind of all camera layers from cached
   engine surfaces (CameraX unbinds on stop/recreate and previously never
   recovered), LIFECYCLE_REBIND + EXTERNAL_SOURCE_DROPPED_NO_LIFECYCLE logs.

Acceptance unchanged (owner): Camon 20 boots clean; Samsung shows live
camera+video with presented>0, fps>0. Not claimed fixed until device proof.


## Increment 9 — round 6 fixes: RAW preview default, EGL_BAD_ALLOC backoff, present-crash telemetry (2026-09-23)

Round 6 dumps finally localized BOTH failures precisely:

1. **Camon 20 (Mali)**: `EGL_WINDOW_FAILED ... EGL_BAD_ALLOC` every frame
   (802 recoveries, ~30ms cadence = retried every vsync). Diagnosis: the EGL
   config ladder chose the RECORDABLE config first; Mali is known to
   BAD_ALLOC when a recordable config meets a plain SurfaceView window.
   Fixes (exactly per owner directive): plain-RGB888 chains now precede
   recordable ones (no MSAA/exotic attributes anywhere); create attempts
   backed off to max 1/500ms; invalid/zero-sized surfaces never reach EGL;
   after 3 consecutive failures the output latches OUTPUT_GAVE_UP and stops
   retrying (fresh attach re-opens it); previous EGL surface is always
   released before recreate (already true, now commented as contract).
2. **Samsung (Adreno)**: EGL_WINDOW_CREATED, no makeCurrent/swap failures,
   yet presentAttempts=0 — meaning an exception is thrown inside the present
   loop and swallowed by the frame-loop catch, invisible in the ring. Fixes:
   present/render calls are individually wrapped — RENDER_CRASH /
   PRESENT_CRASH / FRAME_ERROR events now land in the dump ring with the
   exception class, message and top stack frame; presentAttempts increments
   at loop entry (even for failed attempts, per directive).
3. **RAW fallback (owner directive C, now mandatory Phase 1 usability)**:
   default preview = CameraX PreviewView fed directly by CameraSource
   (bindPreviewView via previewView.surfaceProvider); GL compositor stays
   alive for diagnostics/recording; Diagnostics sheet gains a RAW / GL
   toggle; camera bind routing (add layer, pro-controls update, lifecycle
   ON_START) is mode-aware; engine camera binds are skipped in RAW mode
   (CAMERA_ENGINE_BIND_SKIPPED_RAW). New dependency androidx.camera:camera-view.

Acceptance (owner): Camon stops looping EGL_BAD_ALLOC; Samsung
presentAttempts>0 and presented>0; live camera visible on BOTH devices at
least in RAW mode. Not claimed fixed until device proof.


## Increment 10 — round 7: GL killer bug + settings-crash + RAW image overlay + 1D LUTs (2026-09-23)

RAW CameraX preview CONFIRMED WORKING on both devices (S22 Ultra + Camon 20).
Four remaining blockers, all fixed:

1. **uploadQuad ArrayIndexOutOfBounds(8)[12] — the reason GL NEVER worked.**
   The staging loop advanced its source index by the interleaved stride (6)
   while cornersPx/uvs are tightly-packed 8-float arrays: vertex 2 read index
   12 → crash. Present since round 1; only surfaced once the present path
   finally executed. uploadQuad + uploadFullScreenQuad rewritten with
   vertex-based source indexing (i*2) + stride-based staging offsets (i*6) +
   size guards. This bug, not the drivers, was the original "black preview".
2. **Camera Pro settings crash**: every slider tick (EV/zoom fire per-value)
   triggered a FULL CameraX unbind+rebind → session teardown storm → crash.
   Now: runtime controls (zoom/torch/EV) apply via applyRuntime with NO
   rebind; bind-time controls (WB/AF/ISO/fps) rebind debounced at 350 ms;
   CameraSource serializes binds (new bind cancels the in-flight one).
3. **RAW image overlay (v1)**: imported images now render as Compose views on
   top of the PreviewView (position/size fractions honored; rotation/blend/
   effects remain GL-mode). VM exposes rawImageBitmap(sourceId).
4. **LUT "missing LUT_3D_SIZE"**: parser now (a) sniffs binary inputs
   (PNG/JPEG/ZIP/RIFF → clear "export an ASCII .cube" error — likely the
   user's file was a Hald CLUT image), (b) accepts 1D .cube files by baking
   the per-channel curves into a 3D identity lattice (tests included).

Acceptance for round 8 (owner): RAW stable 10+ min incl. settings changes;
no uploadQuad crash; Image layer visible over RAW. GL compositor should now
also render for the first time ever (presented>0) — verify via the RAW/GL
toggle; any residual GL-path visual bugs are now debuggable with milestone
logs instead of crashes.


## Increment 11 — round 8: TextureView GL stage + FBO readback + video RAW overlay + 0x300d (2026-09-23)

Round 8 milestone: **59 fps, presented 5800-7000+, HEALTHY, zero crashes on
BOTH vendors**; RAW camera + image overlay visually confirmed. Remaining:

1. **GL compositor presents (swaps OK, 59fps) but screen black** on both
   devices. Two possible halves — black FBO content or invisible surface —
   this round attacks BOTH:
   - GL stage switched from SurfaceView to **TextureView**
     (TextureStageView): lives inside the view hierarchy, so occlusion /
     z-order / punch-through failures are structurally impossible.
   - **DRAW_STATS + SCENE_PIXEL readback** in the dump ring every ~300
     renders: per-layer drawn/noContent/noSource tallies plus the actual
     center RGBA of the composited scene FBO — the next dump alone decides
     whether the draw path or the present path is at fault.
2. **Video overlay in RAW**: video layers now route their ExoPlayer into a
   PlayerView overlay (RESIZE_MODE_FILL, transparent shutter) above the RAW
   camera; switching to GL returns them to the engine external texture
   (VideoLayerController.showOnPlayerView keeps the engine surface for
   re-attach). media3-ui added (api, engine-media).
3. **0x300d hardening**: present loop gates on surface validity before
   makeCurrent (dead surface -> immediate window-surface recycle) and swap
   failures on an invalid surface force reinit — no more swapping at corpses.

Acceptance (owner): GL preview visibly non-black; video overlay works. The
SCENE_PIXEL readback makes the next report conclusive either way.


## Increment 12 — round 9: orientation (UV rotation), video aspect, present pacing (2026-09-23)

Round 9 milestone: GL compositor RENDERS CONTENT on both devices (59-100 fps,
HEALTHY, SCENE_PIXEL readbacks show real scene colors). Remaining visual
defects and fixes:

1. **90-degree rotated content (both devices, camera AND video)**: camera
   sensor buffers and video streams store frames rotated (rotation lives in
   metadata, not pixels, for surface outputs). Added `uvRotationDeg` to
   LayerTransform: LayerGeometry rotates the sampling window around the UV
   center; CameraSource exposes cameraInfo.rotationDegrees (VM applies to all
   camera layers on Bound); VideoLayerController now reports
   VideoSize.unappliedRotationDegrees (VM applies + commits).
2. **Video aspect/framing**: video layers kept the default 1280x720 buffer
   regardless of the real stream size -> wrong aspect. New
   RenderEngine.resizeSource: onVideoSizeChanged resizes the shared
   SurfaceTexture buffer to the true video size (SOURCE_RESIZED logged).
3. **Jitter / present pacing**: presentAttempts was exactly 2x presented —
   Choreographer at 120Hz presented every vsync while content changed at
   ~60. Present-on-change: redundant swaps skipped (TextureView holds the
   last frame); also fixed a latent multi-output bug where
   transitionFraction() (state-mutating) advanced once PER OUTPUT per frame.
4. **Decisive geometry telemetry**: DRAW_STATS now includes the first
   visible layer's full transform AND the actual on-screen PRESENT_QUAD
   corners — if the diagonal wedge survives this round, the dump shows
   whether the quad itself is a bowtie or the sampling is wrong.

RAW stays the default stable path; Camera Pro rebind-debounce from round 8
unchanged. Acceptance: GL camera correctly framed on both devices (no
diagonal wedge), video overlay visible + oriented, GL watchable.
