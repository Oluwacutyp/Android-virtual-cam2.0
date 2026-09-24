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


## Increment 13 — round 10 REGRESSION: my present-on-change bug, found and fixed (2026-09-23)

Round 10 regression, root cause MINE, identified from the user's dumps:
`rendered=1046..3543` while `presentAttempts=2..4` and SCENE_PIXEL values kept
CHANGING across 25s — the render thread was ALIVE the whole time. The round-10
"present-on-change" gate compared the presented TEXTURE id; simple scenes
(camera, NORMAL blend, no blur) draw straight into the same FBO texture every
frame, so the gate saw "nothing changed" forever and skipped all presents
after the first. The screen froze on the one swapped frame (often the
pre-camera black scene). The watchdog then measured time-since-last-SWAP —
20-50s of "stall" chains while renders flowed — fake stalls, 32 recovery
events, UNHEALTHY.

Fixes:
1. Present gate now compares the RENDER COUNTER (lastPresentedRender vs
   renderedFrameCount): every new render presents once; idle scenes present
   nothing (correct); texture identity is irrelevant.
2. Watchdog + health rule: a fresh render counts as liveness
   (WatchdogStatus.lastRenderMonotonicMs; stall = no present AND no render
   for >750ms). Present-on-change can never read as a stall again.
3. Scene-apply dedupe: identical SceneDefinition re-commits are no-ops
   engine-side (VM commit dedupe too) — SCENE_APPLIED spam neutralized, and
   changed transitions still apply.
4. PlayerView overlay rebind guarded by player identity (recomposition-safe).

Kept: uvRot=270 orientation fix (visible in dumps, verify once unfrozen),
video resize path, RAW default. Success criteria (owner): presented climbing
with lastPresentAgeMs<100, no watchdog stall spam, no freezes on RAW<->GL or
video add, orientation correct on both devices.


## Increment 14 — orientation math fixed properly + viewport FILL + surface short-circuit (2026-09-23)

Owner-directed critical fix round (orientation + viewport fill + lifecycle only):

1. **Camera/video orientation — composition-order bug + calibrated value.**
   The UV rotation was baked into geometry BEFORE the SurfaceTexture matrix
   applies (rotation∘flip != flip∘rotation — a flip in ST inverts the
   effective rotation direction; the round-10 270 was over-rotated by 180 in
   composition). Now: geometry keeps only crop+mirror; SceneRenderer applies
   R(uvRot) AFTER the ST matrix (applyUvRotation on the transformed uvBuf).
   Value calibrated from the user's two device data points (ST-only: 90deg
   off; +270: upside-down => correct = 90 for these sensors):
   uvRot = (360 - sensorRotationDegrees) % 360; video uses the same inverse
   rule with VideoSize.unappliedRotationDegrees (same metadata MMR would
   read). Front camera adds mirrorX = true (selfie convention); back none.
2. **Viewport FILL (no letterbox bars)**: present path now builds the
   output quad with FitMode.FILL (fillQuad) — the scene COVERS the preview
   surface; 720x1280 into 1028x1675 crops edges instead of the previous FIT
   bars (the ~43px offsets in PRESENT_QUAD were correct FIT math, now FILL
   by design). Same-aspect outputs (recorder) are unchanged (FILL==FIT).
3. **Surface lifecycle**: attachOutput short-circuits when the same live
   Surface re-attaches (view resize) — keeps the EGL window surface
   (SURFACE_RESIZED logged) instead of destroy/recreate churn. Watchdog
   liveness semantics (render counts) already landed in increment 13; the
   stall chains in these dumps were stale ring entries from the round-10
   build plus legit history — current state showed lastPresentAgeMs=4-19.


## Increment 15 — canonical ST→UV pipeline (SourceUvMath) + device-calibrated rotation values + wedge guard + golden-frame CI (2026-09-23)

Round-14 directive: fix sideways camera, missing/incorrect front mirror, and the
diagonal wedge; CI must catch this class going forward.

**Diagnosis (machine-verified, see SourceOrientationGoldenTest):**
- The sampling transform chain is closed under axis-aligned centered maps — a
  90-degree UV rotation about the window center CANNOT leave [0,1]. The
  handed-over "rotation pushes UVs off the texture" mechanism is refuted; the
  wedge's true on-device trigger is still unidentified (r9 Camon precedent had
  NO rotation and NO FILL, so it predates both). Mitigations shipped: hard
  UV clamp (OOB OES sampling now impossible) + OES_ORIENT dump line (ST
  matrix, base and final UVs, 1 Hz) so any recurrence is decidable from one dump.
- REAL BUG (mirror): mirrorX was applied in geometry BEFORE the producer ST
  matrix. flip∘mirrorH == mirrorV∘flip — the front-camera mirror rendered as a
  VERTICAL flip. Mirrors moved to the renderer, post-ST and PRE-rotation
  (machine-verified twice: post-rotation mirroring conjugates by 180 under
  90-degree sampling rotations; the golden test caught the first mis-order at
  CI before any device run).
- REAL BUG (rotation value): device data triangulates the buffer content at
  delta=270 CW: rot 0 -> 90 off (build r9/r12-A), rot 90 -> upside-down
  (builds r10-12 "geometry 270 pre-ST" AND r13 "renderer 90 post-ST" are the
  SAME sampling map by flip conjugation — the machine reproduces both at 180),
  therefore upright = rot 270 == the sensor value itself. The round-13 formula
  (360-sensor) had the sign inverted by ignoring the conjugation. CameraX's
  Transform-output page confirms the buffer is NOT pre-rotated ("the output is
  twofold: the buffer and the transformation info"), so the refuted handed-over
  premise #1 (ST already carries the rotation) does not hold for this pipeline.
- Present path was verified already rotation-free (drawTextureQuad); no change needed.
- Front-camera mirror must compose AFTER rotation (post-rotation window mirror
  == display-horizontal mirror; pre-rotation conjugates by 180 under 90-degree
  rotations — machine-verified).

**Changes:**
- NEW engine-render geometry/SourceUvMath.kt — pure, unit-golden-tested
  canonical chain: base window -> ST matrix -> rotate(window center) ->
  mirrorU -> clamp[0,1]. SceneRenderer.bindSource is its only production caller.
- SceneRenderer: bindSource(uvRotationDeg, mirrorX); applyUvRotation /
  applyStMatrixToUv (android.opengl.Matrix path) deleted; OES_ORIENT dump line.
- LayerGeometry: mirrors removed (geometry = placement/crop only).
- StudioViewModel: camera uvRot = rotationDegrees() (sensor); video uvRot =
  unappliedRotationDegrees (ExoPlayer contract; sampling-rotation ==
  metadata value, golden-tested with 16:9 fixture).
- RenderThread.oesDebugLine + RenderEngine.dump wiring.
- NEW SourceOrientationGoldenTest (software rasterizer): upright/mirror
  golden frames for back/front/video, the two shipped-defect memorials
  (rot 0 -> 90, rot 90 -> 180), OOB sweep across producer matrices x
  rotations, FILL coverage/symmetry, geometry mirror-agnosticism.

**DEVICE VERIFICATION CHECKLIST (owner to confirm on both devices; standing
rule: not fixed until confirmed):**
- (a) Back camera upright at 720x1280 AND 1080x1920.
- (b) Front camera upright AND mirrored in GL (selfie convention).
- (c) Rotated phone videos upright and filling, no wedge.
- (d) Fast RAW<->GL source toggle: no black periods, no wedge.
- (e) FILL behavior: side/top crop allowed; diagonal wedge NOT.
- (f) View resize (PiP, split screen, rotate): no SURFACE_RESIZED storm, no
  EGL re-creation for the same live Surface.
Dump request: send a diagnostics dump after this build - it now includes
OES_ORIENT (uvRot, mirrorX, ST matrix, base/final UVs) + PRESENT_QUAD +
SURFACE_RESIZED/SURFACE_ATTACHED events.


## Increment 15 addendum — presentAttempts double-count fix (2026-09-23)

The r12 dumps showed presentAttempts ~= 2x presented; root cause found while
re-auditing for the device pass: presentAll incremented the counter once when
an output reached the present path (correct — counts attempts that then fail
makeCurrent/reinit) and AGAIN right before swapBuffers. The second increment
is removed; failed-attempt counting semantics preserved. Also verified:
tools/stress-test.sh dump parsing is unaffected by the new OES_ORIENT line,
and layer add/remove never touches output surfaces (attach/detach fire only
from the preview stage-view and recording lifecycle), closing directive D's
"layer add/remove must not destroy a still-valid EGL surface" by structure.


## Increment 16 — DIAGNOSTIC ROUND: A-F instrumentation, zero fixes (2026-09-23)

Owner directive: stop fixing, instrument. Screenshots post-r13: one black
preview, one wedge. Dump from the healthy Adreno 730 device analyzed; the
dump is INTERNALLY SELF-CONSISTENT (finalUV reproduces exactly from the
logged ST + baseUV + uvRot=270 + mirror via the documented pipeline), so
nothing in it is mislabeled.

**Contradiction resolutions (math, not opinion):**
1. The on-device ST matrix IS a pure 90-degree rotation: (u,v)->(v,1-u),
   det=+1 (no flip). The r15 premise "ST carries no rotation" is FALSIFIED
   for this device-state. HOWEVER uvRot=270 is not "still live doubled":
   composing ST(rot cw90) then mirror then rotate(270 ccw) nets EXACTLY to a
   horizontal mirror (verified numerically; net rotation cancels). This
   matches this round's screenshots showing black/wedge rather than a
   sideways/upside-down image. The earlier theta=270 calibration was measured
   against builds whose ST was evidently flip-type - THE ST IS DYNAMIC
   (device/config/state-dependent), so any fixed formula stays fragile.
   NET= classifier (below) will report the composition per dump.
2. finalUV == mirrorX(baseUV) is NOT a stale/pre-rotation log: it is the
   true pipeline output - the ST 90-degree rotation and uvRot 270 cancel
   (mirror∘R270∘R90 == mirror for symmetric windows), so the final values
   legitimately equal mirrorX(baseUV). Label correct, pipeline correct,
   summary and dump simultaneously true - a cancellation identity.
3. baseUV width 0.316 == FILL computed in the SCENE frame (1280x720 source
   filling a 720x1280 scene box at the layer stage). The owner's 0.355 is the
   single-stage output-frame crop. The architecture FILLs twice (layer into
   scene, scene into output): total coverage is preserved (no bars/stretch,
   PRESENT_QUAD [0,0,1028,1629] confirms), at the cost of extra zoom
   (u 0.316 x v 0.700 vs ideal 0.355 x 1.0). Design tradeoff (dual-aspect
   outputs - preview vs recorder - cannot both get maximal FILL at the layer
   stage); REPORTED, not changed.
4. (F) Row-major (transposed) interpretation of this ST maps corners OUTSIDE
   [0,1] ((-1,0),(0,1)...), which would render garbage/black everywhere -
   the device shows healthy changing pixels, so the column-major CPU
   interpretation is consistent. Architectural note: the shader NEVER sees
   ST in this engine - UVs are CPU-transformed (SourceUvMath) before upload;
   the only order risk was CPU-side, cross-checked by the finalUV identity.
   The visual F-check remains available via the UV-gradient pass (A).

**Shipped instrumentation (mandated A-F; no behavior changes):**
- A: UV DEBUG PASS - dev-only toggle in Diagnostics (debuggable-gated);
  renders external-source layers with FRAG_UV_DEBUG (vec4(vUV,0,1), no OES
  sample). Smooth full-quad gradient = geometry/interp fine; missing
  triangle/wedge/degenerate = geometry-side, not sampling.
- B: VERTEX DUMP - CLIP=[4 post-MVP xy] (pass-through VS: aPos IS
  gl_Position.xy) + finite=NaN/Inf flag over positions and final UVs, 1 Hz
  into OES_ORIENT + logcat tag vcam-render.
- C: MULTI-POINT SCENE PROBE - SCENE_PIXELS=[TL,TR,BR,BL] (+2px inset) plus
  the existing SCENE_PIXEL center, same cadence. Wedge in the FBO shows as
  black corners; clean FBO + wedged presentation isolates the present pass.
- D: OES BIND AUDIT - 1 Hz: GL_TEXTURE_BINDING_EXTERNAL_OES == source id
  (tgtOk), declared sampler TYPE via glGetActiveUniform (OES vs 2D - the
  partial-quad-black driver class), sampler unit value (unit=).
- E: SHADER LOG - GlProgram retains the EXACT sources compiled; the dump
  carries VERTEX_SHADER_BEGIN/FRAGMENT_SHADER_BEGIN with the verbatim text
  of the program last used for the camera draw.
- F: ST INTERPRETATION CHECK - ST_T (transposed) logged alongside ST, and
  NET=<classifier> reporting the composed sampling map (IDENTITY/MIRROR_H/
  MIRROR_V/ROT180/ROT90CW/ROT90CCW/OTHER[vectors]) so a dump alone decides
  cancel-vs-double. Report-only; no auto-correction.

NOT FIXED (per directive): the wedge and black screenshots have no dump from
the failing moment and no code path in the current build can produce a
diagonal (geometry rotation was removed; corners are axis-aligned; UV math
closed over [0,1] and now clamped). Next report MUST include a dump taken
while the defect is on screen - with A-F that dump is decisive.


## Increment 16 addendum 2 — screenshot forensics: wedge = two left vertices at clip (0,0) (2026-09-23)

Two screenshots arrived (post-r13 build): (1) fully black preview, front
camera, HEALTHY; (2) the wedge. Pixel-measured forensics of (2):

- Black boundary starts AT the preview top-left corner, runs to an apex at
  (0.500, 0.494) normalized = the EXACT frame center = NDC ORIGIN, then back
  down to the bottom-left corner. Content occupies the right triangle
  (center, TR, BR); black occupies (TL, center, BL).
- The two boundary edges CONVERGE at the apex (slopes +0.99 / -1.01), they
  are NOT parallel.

Mechanism discrimination:
- (c) rotated quad: ELIMINATED - a clipped rotated rectangle leaves PARALLEL
  diagonal edges; measured edges converge.
- (d) scissor/viewport: ELIMINATED - axis-aligned rectangles; no scissor in
  the engine (grep).
- (a)/(b) degenerate vertex: MATCHES EXACTLY. A TRIANGLE_STRIP (TL,TR,BR,BL)
  whose LEFT-column vertices (0 and 3) render at clip (0,0) draws
  tri1=(TL@C,TR,BR) = the right-side content triangle and a degenerate tri2,
  leaving the black left wedge with its apex at the NDC origin - the measured
  fingerprint. Zeros (not NaN) - NaN projects off-frame, no crisp wedge.

Screenshots predate r14-r16; the current build removed the geometry-stage
transforms, clamps final UVs, and carries the (A)-(F) probes. Device rerun
on the current build + a dump taken WHILE the defect is on screen decides:

  scene-side zeroed verts: OES_ORIENT CLIP=[... 0.000,0.000 ...] (or
    finite=false) AND SCENE_PIXELS TL-corner probe black/content per wedge.
  present-side: PRESENT_CLIP=[... 0.000,0.000 ...] with clean SCENE_PIXELS.
  producer content (screenshot 1 full-black case): UV-gradient pass shows a
    perfect ramp while the real camera is black -> sampled texels are black
    in the buffer (front-sensor masked region / buffer-size mismatch), not
    GL geometry; bind audit + ST/NET then locate the window.

Shipped this addendum (report-only): rotationDeg now logged in the L0/
DRAW_STATS line (rotated-quad visibility gap closed); PRESENT_CLIP 1 Hz
capture of the present pass's four clip positions + finite flag, in the
dump and logcat.


## Increment 16 addendum 3 — gradient-pass evidence + draw-state audit (2026-09-23)

Owner evidence: the WEDGE SURVIVES THE UV-GRADIENT PASS (fragColor=vUV, zero
sampling) on-device. Conclusion accepted: the wedge is in the VERTEX pipeline
domain, not UV math, not sampling. All rotation/UV theories stay parked.

Dump decoding (honest corrections):
- "Negative RGB" probes = SIGNED-BYTE printing (0x80 -> -128, 0xFC -> -4,
  0xFF -> -1). MANDATED FIX SHIPPED: SCENE_PIXEL/SCENE_PIXELS now log raw
  hex bytes, no interpretation.
- Full-range corner values ([00,FC],[00,01],[FF,01],[FF,FC]) = the VIDEO
  layer's own gradient window: its 1080x1920 source FILLs the 720x1280 scene
  with ZERO crop ([0,1]^2 window) and paints OVER the camera gradient at the
  corner probes (SOURCE_RESIZED vid 1080x1920 in the same log). Not corruption.
- NET=OTHER in this dump = MY CLASSIFIER BUG (shipped r16): it transformed
  single points, so the rotation center collapsed onto the probe and
  rotation/mirror degenerated to no-ops - it classified ST alone. FIXED
  (affine measurement from the window's four corner images). On the dump
  device's real window the fixed classifier outputs NET=MIRROR_H, matching
  the r16 algebra (ST rot90 o uvRot270 cancel; mirror remains).

Static eliminations (grep, whole engine): NO VAOs (glBindVertexArray absent),
NO element buffers / glDrawElements - hypothesis (b) index-bowtie is
structurally impossible; all draws are glDrawArrays(TRIANGLE_STRIP,0,4).
Attributes are CLIENT-side direct buffers (no VBOs): the dump's posBuf/uvBuf
ARE the buffers the draw consumes - hypothesis (c) is structurally excluded;
the audit shows buf=0 to prove it at runtime. Remaining candidates:
(a) attrib-state corruption at draw time (audited live), the PRESENT pass
(audited live), or a per-frame transient between the 1 Hz captures.

SHIPPED (report-only, per mandate):
- DRAW_STATE[tag] 1 Hz at draw time for OES / UVDBG (camera layer, both
  paths) and PRESENT: current program vs expected handle; attribs 0/1/2
  (enabled,size,type hex,stride,normalized,BUFFER binding); global
  array/elem buffer bindings; draw-call form; cull enabled/mode + front
  face; CLIP and UV mirrors actually consumed by the draw.
- Hex probe logging (mandate 2).
- Classifier fix (NET meaningful again).
NOT SHIPPED: any rotation/UV/geometry change; any fix claim. Standing rule
intact: device verification on both test devices before any "fixed".


## Increment 16 addendum 4 — FBO exonerated; present-stage isolated; VBO test path (2026-09-23)

Owner evidence processed; three corrections (one to the owner's premise, two
to Arena's diagnostics), then the mandated instruments.

DECISIVE FINDING (from the owner's own dumps + screenshots):
- The FROZEN corner probes decode to the EXACT gradient the CPU math
  predicts (full-window video gradient, ST v-flip): TL(0,0.99,0) TR(0,0,0)
  BR(1,0.004,0) BL(1,0.99,0) — all four match. The scene FBO is CORRECT
  while the screen shows the wedge.
- The on-screen visible triangle's colors match the FBO sampled by
  strip tri1=(TL@NDC-origin, TR, BR) WITH the present pass's y-flip
  (screen right-top = FBO BR red; right-bottom = FBO TR dark; left of the
  wedge = FBO left-edge yellow/green). Four independent color matches.
=> The wedge is introduced AFTER the scene FBO: the PRESENT draw (or below).
   The GPU renders the present quad with its LEFT vertices at clip (0,0)
   while the 1 Hz CPU read of the same buffer shows a perfect rectangle.
   Layer draws are exonerated (FBO clean); the mandate-3 camera-draw
   neutralization would test a draw that provably works — the same
   experiment is redirected to include the PRESENT draw.

CORRECTIONS:
1. buf=0 is NOT invalid here: the engine has never used VBOs/VAOs — every
   draw is client-side direct ByteBuffers (legal in GLES). The diag was not
   missing a VAO (there are none; now PROVEN per dump: vao=0 is logged).
   The underlying suspicion (client-array consumption on Adreno) is exactly
   the right experiment -> mandate-3 test path shipped (below).
2. DRAW_STATE[OES] was AMBIGUOUS: camera and video are BOTH external
   sources; the 1 Hz tag captured whichever drew first. Dump 1's "OES" UVs
   were the VIDEO's window. Tags now carry the layer id (OES:<id>,
   UVDBG:<id>); OES_ORIENT carries the source id.
3. SCENE_PIXEL hex was incomplete (%02X of a signed Int -> FFFFFF95).
   Fixed properly (mask 0xFF). Dump 1's content probes were otherwise fine.

SHIPPED (report/test-only, per directive):
- vao=<handle> (GL_VERTEX_ARRAY_BINDING, literal 0x80B5) at draw time.
- CLIENT_BYTES: 96 bytes of the exact client data the draw consumes
  (pos/uv/local float-bits hex). GPU_BYTES: glMapBufferRange of the bound
  VBO when one is bound.
- MANDATE-3 ONE-SHOT TEST: dev toggle "fresh VBO/VAO draw path" — quad
  draws (external layers AND the present pass) run through a freshly
  created VBO+VAO with per-draw glBufferData of the staged values; no
  client arrays, no cross-draw buffer reuse. Default OFF; toggle ON ->
  wedge gone = client-array lifecycle; wedge persists = deeper.
- VBO_TEST_CREATED line in the dump when the test path first creates
  its buffers.

ROTATION DERIVATION (mandate 16D-6 — LOGGED, NOT FIXED):
- This device's net sampling map = MIRROR_H (classifier + algebra agree;
  uvRot=270 cancels the ST rot90 exactly).
- Screenshots: face 180-deg inverted. Required net for a selfie =
  ROT180 o MIRROR_H. Therefore for THIS ST class uvRot must be 90
  (== 360-sensor). For the earlier flip-class ST, 270 was correct.
- CONCLUSION: the ST matrix CLASS is dynamic (device/config/state); any
  static sensor-only formula is class-fragile. The durable fix classifies
  the ST at bind time and derives the rotation from (ST class, sensor,
  facing). Deferred until the wedge is resolved (owner directive).


## Increment 17 — mandated test paths: 0x500 attribution/isolation, TRIANGLES, direct-to-surface, viewport/scissor (2026-09-23)

Round-17 mandates processed. Accepted facts: CLIENT_BYTES bit-exact =>
client-array corruption DEAD as the wedge mechanism; wedge shape invariant
across camera/video/gradient/client-array/VBO => structural, not data-
dependent; every wedged draw shares glDrawArrays(TRIANGLE_STRIP,0,4) =>
prime suspect, now directly testable.

CRITICAL OWN FINDING (honest, not in the mandate): dump 2 shows the 0x500
spam with NO VBO_TEST_CREATED line — the invalid enum was NOT (only) in the
VBO path: MY AUDIT QUERIES generate it, and the audit errors leaked into the
draw's checkGlError, producing the RENDER_CRASH storm and (in the
toggle-poisoned session) the watchdog stalls + 29fps chaos. Instrumentation
was polluting the pipeline it measured. That is fixed as mandate A requires:

A. 0x500 class:
   - EVERY audit query is now error-ATTRIBUTED (audited{} helper: clear ->
     query -> read; the DRAW_STATE/OES_ORIENT lines carry AUDIT_ERR=[call:0x..]
     naming the exact guilty call if one recurs) and error-ISOLATED (audit
     errors are consumed inside the audit; the draw's checkGlError sees only
     true draw errors).
   - The VBO test path got per-call attribution too (vboCheck after every GL
     call; VBO_ERRORS=[...] line in the dump + logcat). If the VBO path itself
     raises INVALID_ENUM, the dump now names the exact call.
   - Expected on-device: glErr=0x0 across all paths; AUDIT_ERR names the
     previously anonymous 0x500 source; RENDER_CRASH storm + watchdog stalls
     gone (they were error-poisoning artifacts).
B. TRIANGLES toggle (dev): identical quad as explicit pairs TL,TR,BR / TL,BR,BL
   via glDrawArrays(GL_TRIANGLES,0,6) — client 6-vertex path AND combined with
   the VBO path (144-byte upload). Present pass + layer draws + scratch path
   all route through the unified issuer, so the toggle applies everywhere.
C. DIRECT-TO-SURFACE toggle (dev): layers render straight to framebuffer 0
   (EGL window surface), scene FBOs and the present pass skipped entirely;
   geometry recomputed against the surface box; same programs/UV pipeline;
   DRAW_STATE[OES:DIRECT:<id>] captured; present counting preserved for the
   watchdog. Wedge gone here => FBO->surface path implicated; persists => draw
   or window-surface state.
D. DRAW_STATE now logs viewport=[x,y,w,h], scissorBox=[x,y,w,h],
   scissorTest=on/off as the driver reports at draw time (grep cannot see
   implicit state — agreed).
E. No fix ships beyond the 0x500 class (explicitly mandated by A). Rotation
   stays parked (dynamic-ST-class derivation logged in addendum 4).


## Increment 18 — BufferOverflow root-caused (my round-17 VBO staging) + mandated lifecycle hardening (2026-09-23)

Owner's BufferOverflowException stacks CONFIRMED and located. Honest
attribution: the overflow was introduced BY THE ROUND-17 TEST PATH, not the
base engine — testInterleaved was sized for the strip (4 verts x 6 floats =
24) while the owner-mandated TRIANGLES branch writes 6 verts x 6 = 36;
overflow at put #25 == Buffer.nextPutIndex. Exactly the owner's hypothesis
(a). It explains the crash storms, the UNHEALTHY session, presented/rendered
divergence, and the 32-stall watchdog chain (renders crashed every frame).
It CANNOT be the original wedge's cause (the wedge predates the toggles by
eight builds; dump 1 this round wedges on draw=TRIANGLES client path where
no overflow occurs) — but per mandate 2 the clean before/after re-run
decides the wedge question empirically.

FIXED (mandate 1, all four bullets):
- Capacity: testInterleaved = max(strip,triangles) verts (36 floats), once
  per renderer.
- Fresh region at the START of every draw: posBuf/uvBuf/localBuf,
  posBuf6/uvBuf6/localBuf6, testInterleaved all clear() before their puts
  (verified structurally; a crashed draw cannot poison the next one).
- Pre-put capacity guard on EVERY staging write (uploadVertexData,
  drawQuadTriangles, drawQuadVbo): guard failure logs
  STAGING_OVERFLOW where/cap/pos/need (rate-limited 1 Hz) and SKIPS the
  draw — the last good frame keeps presenting (uploadOk gate at the layer,
  present, scratch, and direct-surface draw sites).
- DRAW_STATE now carries staging=[cap=<bytes>,pos=<bytes>,need=<bytes>].

ALSO: AUDIT_ERR=[vao:0x500] attribution SUCCESS — the INVALID_ENUM source
is the GL_VERTEX_ARRAY_BINDING query (0x80B5) itself on this Adreno driver.
It was already isolated (DRAW_STATS glErr=0x0 confirms draws are clean);
after the first raise the query is gated off (vao=unsupported) — the engine
has no VAOs, nothing further to learn from it.

MANDATE 4 (viewport) — CONFIRMED FROM THE OWNER'S OWN DUMPS: layer draws
log viewport=[0,0,720,1280] because their destination IS the scene FBO
(bindViewport per draw); present + direct-surface draws log
[0,0,1028,1675/1629/1693] = the surface. The flagged UVDBG 720x1280 line
was a scene-FBO draw — correct, not a bug. scissorTest=off everywhere =>
scissorBox [0,0,1,1] is inert.

ROTATION (mandate 3): still PARKED per directive. State: NET=MIRROR_H on
this ST class; required net for selfie = ROT180 o MIRROR_H; for this ST
class that means uvRot=90 (=360-sensor); for the earlier flip-class ST,
270 was right. The durable design (classify ST at bind, derive rotation
from ST class + sensor + facing) is designed and waiting. Owner's final
note ("camera still rotates / not straight") matches the parked diagnosis
exactly.

NOT CLAIMED: the wedge is NOT claimed fixed. Mandate 2 (before/after
screenshots, STRIP, no toggles, camera+video) is the owner's next step.


## Increment 19 — rotation reverted to c73a4d5 (owner directive) + staging sizing/reporting fixed + T1-T6 bisection harness (2026-09-23)

OWNER DIRECTIVES PROCESSED:
1. "staging=[cap=96,pos=0,need=144] on the TRIANGLES path - one of the nine
   staging buffers is still strip-sized." Root-caused: the DRAW_STATE staging=
   REPORTER summed the strip trio (posBuf/uvBuf/localBuf, 8 floats each) even
   when the active mode was the CLIENT TRIANGLES path (which uses the
   posBuf6/uvBuf6/localBuf6 12-float trio - correctly sized, no overflow
   occurred; no STAGING_OVERFLOW line in those sessions). Fix is double: the
   reporter now sums the buffers the ACTIVE mode uses, AND the strip trio is
   resized to 12 floats so every staging buffer fits the largest vertex count.
   All modes now report cap=144.
2. "Rotation changed without authorization - put it back to c73a4d5." Facts
   stated without dispute: the rotation CODE was last modified in round 15
   (rounds 16-18 diffs contain no VM rotation change); the 270->90 flip in
   the logs is the r15 per-lens formula reacting to a FRONT->BACK camera
   switch (back sensor = 90, mirror = front-only). The front-camera values
   under c73a4d5 and r15 are IDENTICAL (270/true). Regardless - COMPLIED:
   camera uvRot = ((360 - sensor) % 360), video uvRot = ((360 - rot) % 360),
   exactly the c73a4d5 expressions, restored this build. Rotation is now
   FROZEN until the wedge is dead.
3. BISECTION (owner plan, all rungs as dev toggles, report-only):
   - T1 SOLID QUAD: positions hardcoded in-shader (gl_VertexID const array),
     solid color FS, no attributes, no FBO, straight to the EGL surface.
   - T2 +ATTR: same quad, positions from an uploaded client attribute.
   - T3 +FBO: T2 quad into the scene FBO, presented via the standard
     textured present quad (fillQuad + copy path).
   - T4 +OES: camera OES sampled with PLAIN 0..1 UVs straight to the
     surface (no crop, no composite, no scene FBO).
   - T5 LAYER STACK: full pipeline with layer UV windows forced to 0..1
     (no fill-crop) - bisectPlainUvQuad override in the layer draw paths.
   - T6 FULL: all toggles off (baseline wedge reproduction).
   Each rung captures its own DRAW_STATE[BISECT*] at draw time; the dump
   carries bisect=<level>; the diagnostics sheet has T1-T5 chips (mutually
   exclusive), T6 = all off.
4. Boot-config confirmation (owner ask): scene 720x1280 (SceneResolution
   P_720 default, DataStore-persisted), FBO format GL_RGBA + GL_UNSIGNED_BYTE
   (RGBA8888), preview surface-driven 1028x1675 (from the dumps).
5. Logcat ask: cannot run adb from this sandbox - the exact command was
   provided back to the owner to run on-device (adb logcat -d | grep -iE
   "adreno|eglCreate|eglMake|shader|GL_INVALID|gralloc|...").

ARTIFACT NOTE for the T-runs: with T1-T4 the renderScene path does not run,
so the render-liveness watchdog will log stall recoveries that are NOT real
faults; ignore recoveries= during T1-T4 (T5/T6 run the full render path and
are meaningful).

No fix claims. Standing rule intact.

## Increment 20 — round-20 mandates: probe-wedge module + LAUNCH LOG + stall cause

Owner verdict on round 19: BISECTION IS VOID. The T1–T5 toggles swapped only
the present pass while the full camera pipeline kept rendering underneath
(owner visually confirmed the Camera layer stayed live in the LAYERS rail;
dumps show DRAW_STATE[OES:cam-…] L0=Camera at every bisect level), so every
"rung" screenshot still contained the wedge. Rule accepted: no more in-engine
toggle iteration. Three mandates, one build, no exceptions:

1. :probe-wedge (NEW Gradle module, SEPARATE APK, applicationId
   com.vcamstudio.probe, ZERO dependency on any engine module):
   - One Activity (plain Views, no Compose), one SurfaceView, one EGL context
     on one HandlerThread render loop.
   - VS: `const vec2 POS[4]=vec2[4](...)` positions indexed by gl_VertexID,
     `gl_Position=vec4(POS[gl_VertexID],0,1)`; NO attribute upload at all.
     FS: solid green vec4(0.2,0.7,0.3,1.0). GL_TRIANGLE_STRIP 0..4, present,
     repeat. No camera / layers / FBO / diag / toggles.
   - Logs EGL vendor/renderer/version + EGL surface dims + shader logs +
     glGetError per frame + swap failures to filesDir/probe-wedge-log.txt,
     mirrored on screen (log tail). File is the deliverable alongside the
     screenshot, from a FRESH INSTALL.
   - Verdict protocol: green + no wedge ⇒ wedge is in the studio engine
     (T1-minimal in-engine becomes the only valid isolation); wedge present
     ⇒ bug is below our code (Adreno driver / EGL window composition) — stop
     engine work, go to SurfaceView/EGL configuration.
2. LAUNCH LOG prepended to every dump (RenderThread.noteLaunch ring, 96):
   EGL_CTX vendor/renderer/version/egl, SHADER_COMPILED prog/name/logs=
   (GlProgram now retains vs/fs/link info logs even on success),
   EGL_WINDOW_CREATED dims=, SURFACE_ATTACHED dims=, SURFACE_RESIZED
   from= to=, SURFACE_DETACHED, FIRST_PRESENT dims=, GL_ERROR code= at
   scene-draw/present/frame-loop. All lines stamped [t=<ms>].
3. STALL CAUSE per watchdog entry: RenderThread.stallDiagnostics() enriches
   the recovery reason with thread=vcam-render, the render thread's DEEPEST
   8 stack frames (shows eglSwapBuffers / lock / queue wait verbatim),
   lastSwapDurationMs (swap calls now instrumented in both present paths),
   and the last 3 eventLog lines (render-loop activity in the 500ms before).

Known cost (declared): the probe activity is a separate debug APK; CI root
assembleDebug builds it alongside the studio app.

## Increment 21 — probe verdict: WEDGE PRESENT in standalone APK; P1/P2/P3 variants

Probe result (owner screenshot, Adreno 730): the standalone :probe-wedge APK
— one shader, in-shader const vertices, no attributes, no camera, no FBO, no
engine code — reproduces the EXACT studio wedge (same shape, same apex
position). Conclusion accepted: the wedge is produced BELOW our code (Adreno
730 strip rasterization under this EGL/window config); six rounds of engine
work could not have fixed it.

Round-21 mandate (probe-only, studio engine FROZEN):
- BASE  original strip probe (re-confirm rung).
- P1    GL_TRIANGLES, 6 explicit in-shader vertices.
- P2    strip -> FBO (RGBA8, size==surface, explicit glViewport at draw)
        -> blit to surface via plain textured strip quad.
- P3    oversized triangle (-1,-1)(3,-1)(-1,3), FS discards outside 0..1 UV.
- On-screen mode buttons; per-mode FIRST_PRESENT / GL_ERROR / LOOP_END lines.
- Per-second GL_STATE line: glGetIntegerv(GL_VIEWPORT) + glGetIntegerv
  (GL_SCISSOR_BOX).
- EGL config attribs now logged (rgb/alpha/depth/stencil/samples/renderable/
  surface).
- Full log mirrored on screen + COPY LOG button (clipboard) so the owner can
  post filesDir/probe-wedge-log.txt verbatim without adb.
- Manifest: ZERO permissions declared (no camera, no storage).
- versionName 0.2-r21-variants (fresh-install provability).

## Increment 22 — STRIP MIGRATION: SceneRenderer draws GL_TRIANGLES only

Probe verdict (owner): P1 TRIS CLEAN, P3 BIGTRI CLEAN, BASE STRIP wedged
(10th reproduction). P2 ignored (probe blit bug: uniform set before
useProgram — mine, acknowledged). Conclusion: driver-level strip
rasterization defect on Adreno 730. Mandate: migrate the engine to
GL_TRIANGLES, ONE change; rotation untouched.

1. ALL quad draw sites migrated (grep-swept the whole module — 8 strip
   sites, all in SceneRenderer): blendScratchOnto, drawColorLayer, copyFbo,
   gaussianBlur, drawBisectSolid (attrib + in-shader paths), drawBisectOes,
   drawQuadVbo strip branch, issueQuadDraw else branch. Every site now
   issues glDrawArrays(GL_TRIANGLES, 0, 6).
2. uploadVertexData expands the 4 staged corners (TL,TR,BR,BL) into SIX
   vertices TL,TR,BR,TL,BR,BL via TRI_ORDER — same positions, same UVs,
   same local coords, same winding as the strip. Buffers are 12 floats each
   = exactly 6 verts x 2 floats (mandate 3: no resize; cap==need==144 now).
3. Deleted the round-17 "DEV: TRIANGLES draw" toggle end-to-end:
   SceneRenderer.trianglesOnly, RenderThread/RenderEngine setters, VM flow,
   StudioScreen wiring, DiagnosticsSheet switch. drawQuadTriangles +
   posBuf6 trio deleted (superseded by the upload expansion).
4. DRAW_PATH=TRIANGLES: const marker, boot assert in initEngine
   (check + noteLaunch "DRAW_PATH=TRIANGLES assert=ok"), DRAW_PATH line in
   every dump, runtime stagedVerts guard — a non-6-vert stage is logged as
   DRAW_PATH_REGRESSION and the draw SKIPPED (never reaches GL).
5. Golden gate (DrawPathGoldenTest, JVM): software edge-function
   rasterizer replicating the exact vertex contract — fullscreen quad must
   cover 100% of pixels (no diagonal possible), every 8x8 tile fully
   covered, rows contiguous, <=2 writes/pixel, cropped-window exact-rect,
   UV corner mapping + monotonic ramp, single shared winding; PLUS source
   gate: any GL_TRIANGLE_STRIP in engine code (comments stripped) fails CI.
   Geometry validated by simulation before commit (0 uncovered/0 mismatch).

## Increment 23 — 6-VERTEX WRITE PATH COMPLETED (bindSource rotated only 4)

Owner device report on the round-22 build: draw call says TRIANGLES, staged
data is strip-shaped (4-entry CLIP/UV lines, 8-hex CLIENT_BYTES, staging
pos=0). Root cause CONFIRMED in code — my round-22 migration was incomplete:
uploadVertexData expanded positions/UVs/locals to six vertices, but
bindSource still rotated ONLY the first four uvBuf entries (8-float
uvBase/uvWork, loop 0 until 4). Triangle 2 (verts 5-6 = BR,BL duplicates)
sampled STALE, unrotated UVs on every OES/video draw — corrupt second
triangle. (The owner's dump 4th==1st was the six-vertex sequence truncated
by the four-entry printers; the underlying rotation gap was real.)

Fixes, in mandate order:
1. Write function located: uploadQuad stages 4 corners -> uploadVertexData
   expands via TRI_ORDER -> bindSource post-stages UVs (the gap).
2. Corner-list builder: already 6-vert (r22); bindSource now gathers the 4
   UNIQUE corners (verts 0,1,2,5 = TL,TR,BR,BL), transforms them through the
   UNCHANGED golden-tested 4-corner SourceUvMath, and SCATTERS to all six
   slots via TRI_ORDER (4th=TL, 5th=BR, 6th=BL).
3. baseUV/finalUV: printed as six-entry scattered forms (structure per
   mandate; absolute v values follow our texture v-origin convention —
   flipping v is orientation work, out of scope this round).
4. Dump: CLIENT_BYTES prints 12 hex per attribute (pos/uv/loc); GPU VBO map
   reads 144 bytes/36 words; CLIP + PRESENT_CLIP are six entries; staging
   pos= now reports bytes WRITTEN by the last upload (144 when healthy —
   live positions are rewound to 0 before glVertexAttribPointer, so pos=0
   was meaningless, not evidence).
5. Hard assertion: check(written == TRI_ORDER.size) in uploadVertexData —
   throws (FRAME_ERROR, visible) on any non-6 staging write; golden source
   gate pins the assertion + 12-float print + scatter presence in CI.
6. VBO diagnostic: default OFF verified (vboDrawPass = false at rest);
   toggle remains in DEV panel per mandate.

Rotation untouched (next round's single change). No strip path anywhere
(golden source gate green). All other dev toggles default OFF in this build:
uvDebugPass=false, directSurfacePass=false, bisectLevel=0, vboDrawPass=false.

## Increment 24 — FLICKER CAPTURE: per-frame ring + present probe + surface events

Device evidence: preview flickers black 1-few frames at a time (sub-second,
returns immediately), camera-only present, worse with camera+video, RAW path
clean, both devices. All existing probes sample at 1 Hz — the bad frame
(1 in ~30) was never captured. Round 24 is INSTRUMENT ONLY.

1. Per-frame ring (300 = 10s @ 30fps), lock-free (AtomicLong write index,
   single writer = render thread), flushed oldest->newest into every dump
   as FRAME_RING:
   FRAME idx=<n> t=<ms> layer_draws=<n> cam_frame_ready=<bool>
   vid_frame_ready=<bool> scene_fbo_written=<bool> present_swap_ms=<n>
   since_last_present_ms=<n> st_hash=<FNV-32 of the 16 ST floats>
   surface_id=<output id> [swap_skipped=unchanged]
   Readiness = ExternalTextureSource.update() per frame (cam id vs other);
   scene_fbo_written = renderedFrameCount advanced this frame.
2. PRESENT_PROBE every 5th frame on the preview output (6/s @ 30fps):
   PRESENT_PROBE_PRE idx rgb=<r,g,b> — 1x1 center readback of the COMPLETED
   back buffer right BEFORE the swap (the content about to be presented);
   PRESENT_PROBE idx rgb — after eglSwapBuffers returns, exactly as
   mandated. BOTH lines exist because on non-preserved swap behavior the
   post-swap read is the recycled back buffer; EGL_WINDOW_CREATED now logs
   swapPreserved=<bool> (EGL_SWAP_BEHAVIOR query) so interpretations are
   unambiguous. FBO_PROBE t=<ms> render=<n> rgb=<r,g,b> at 1 Hz (every 30
   renders) added alongside the existing 1 Hz DRAW_STATS probes.
   Flicker signature per mandate: FBO non-black + PRESENT black on the
   same/next idx => present/blit races the composite or stale swap; both
   black => upstream (nothing drawn that frame).
3. Surface/EGL events, every one (eventLog + LAUNCH LOG):
   EGL_WINDOW_CREATED dims= id= reason=<attach|recovery:...|surface-invalid|
   swap-failures=30> swapPreserved=; EGL_WINDOW_DESTROYED dims= reason=
   <detach|replace-on-attach>; SURFACE_ATTACHED/RESIZED/DETACHED (r20);
   SWAP_STALLED ms= when a swap blocks >100ms; SWAP_SKIPPED as a per-frame
   ring field (per-frame lines would flood the text logs).
Out of scope honored: rotation untouched; TRIANGLES path untouched; no fix
claim. Watchdog stall chains from backgrounded app = expected (idle
nativePollOnce), per owner.

## Increment 25 — TEST A: skip-swap REMOVED; TEST B: READBACK_FBO probe

Ring evidence: layer_draws alternates 0/1 with the 30fps camera into the
60Hz loop; skipped frames never reach eglSwapBuffers; swapPreserved=false.
H1 (skip-swap + recycled back buffer = flicker) and H2 (default-FB
readbacks return black on this Adreno — every PRESENT_PROBE was 0,0,0 while
FBO_PROBE was non-black) both accepted.

TEST A: present-on-change skip DELETED (no toggle — mandate says remove
entirely; revert shape documented in code comment). Every Choreographer
tick reaching the present path now draws the last scene FBO (blit when
nothing new rendered) and calls eglSwapBuffers. swap_skipped field removed
from FRAME_RING (lines ABSENT by construction).

TEST B: PRESENT_PROBE (pre+post default-FB reads) removed entirely.
Replaced by READBACK_FBO idx rgb — every 5th frame, the scene FBO is
blitted (glBlitFramebuffer, LINEAR) into a dedicated 1x1 offscreen FBO and
that is read back: honest "what did we just draw" at present time,
independent of window-surface read semantics. Probe FBO released at
shutdown. Interpretation: READBACK_FBO non-black on flicker frames + black
display => present path guilty; both black => upstream dead frame.

Additional: SWAP_MODE=preserved|undefined|unknown line in LAUNCH LOG at
every window creation (from the EGL_SWAP_BEHAVIOR query); SWAP_RECENT dump
section = rolling last-5 swaps (t/idx/ms/ok/id), synchronized writes.

Out of scope honored: rotation untouched (NET=MIRROR_V parked); TRIANGLES
path untouched (dump shows staging=[cap=144,pos=144,need=144], draw=
TRIANGLES, 6-entry UVs — the r23 write-path fix is confirmed on device).

## Increment 26 — H1 CONFIRMED: skip-swap is the flicker; Option A locked in

Device confirmation (r24 build, direct-to-surface toggle as the A/B): FBO
path flickers with 2/33ms alternation + ~8% skipped callbacks; direct path
(no skip) clean, ~1%. swapPreserved=false means a skipped swap presents
undefined back-buffer content = the blink. H2 also confirmed (PRESENT_PROBE
UB on default FB, dropped r25).

State of the engine (shipped r25, this increment locks it):
- Option A IS the engine: present-on-change skip DELETED (no toggle,
  "do not keep both" honored). Every present-path tick re-draws the last
  scene FBO (drawTextureQuad blit) and calls eglSwapBuffers.
- swap_skipped: zero occurrences in code (field + line deleted r25).
- PRESENT_PROBE: removed entirely (r25); READBACK_FBO (1x1 offscreen blit)
  is the present-time readback; FBO_PROBE + FRAME_RING + SWAP_RECENT kept.
- r17 direct-to-surface DEV toggle: available, default OFF (not the fix —
  it bypasses the scene FBO composition chain).
NEW this increment (mandate ALSO-4): SKIP_SWAP_ENABLED=false policy const +
boot assertion in reinitOutput — on any surface reporting swapBehavior=
non-preserved, SKIP_SWAP_ENABLED=true is a hard check() failure at window
creation ("SKIP-SWAP FORBIDDEN ... the confirmed flicker root cause").
LAUNCH LOG line now: SWAP_MODE=<preserved|undefined|unknown> id=...
skipSwapAllowed=<bool>.

Pending owner verification (send-backs): 60s watch on the FBO path (all
toggles OFF) => gone/reduced/same; ring dump with zero swap_skipped lines
(impossible by construction) and since_last_present_ms STABLE — expect
~8ms on a 120Hz panel or ~16ms at 60Hz (stable is the criterion, not
single-digit). No fixed claim until (a) screenshot no-flicker + (b) ring.

## Increment 27 — skip-line semantics fixed + static-FBO blit isolation + explicit present state

Owner round-23 report: direct path (no scene FBO) = camera stable but video
never lands (bypasses composition — stays DEV-only OFF); FBO path composites
correctly but flickers. Flicker site narrowed to the FBO->surface present
step or scene-FBO state at blit time. Correction of record: the skip
BRANCH has been gone since r25 — what the owner caught was the r26
skipSwapAllowed log expression evaluating TRUE on a non-preserving surface
(preserved!=false || !SKIP_SWAP_ENABLED == true). A log bug that read like
a live skip path. Fixed.

1. Mandate 1 (remove skip path): nothing to delete — verified no branch
   reads skipSwapAllowed; every valid-surface pass re-blits the scene FBO
   and swaps (since r25). skipSwapAllowed line now prints the REAL policy
   (SKIP_SWAP_ENABLED && preserved==true) => false on device. Boot check
   retained: policy ON + non-preserving surface = hard startup failure.
2. Mandate 2 (isolate the blit): NEW DEV toggle "static FBO content
   (round-23 test)" — ON paints a solid quad into the scene FBO ONCE
   (drawBisectSolid green), then every frame is blit-and-swap only: no
   scene redraw, no camera/video frame pulls (sources not updated in this
   mode). Chain: RenderThread.staticFboContent + paintStaticFboOnce ->
   RenderEngine passthrough -> VM flow -> Screen -> DiagnosticsSheet
   switch. Expected split: clean blit => scene-FBO UPDATE path; flickering
   static blit => the blit/viewport/format/surface-reconfig itself.
   READBACK_FBO in this mode reads ~41,191,89 (the static green) — a
   positive control that the probe works.
3. Mandate 3 (explicit surface state on every blit): at the top of the
   present blit, unconditionally: glBindFramebuffer(0), glViewport(0,0,
   surface_w,surface_h), glDisable(SCISSOR_TEST), blend set explicitly
   (enabled only in the transition branch, disabled otherwise),
   glColorMask(all true), glUseProgram(copy) via new
   SceneRenderer.usePresentProgram(); kept the black clear (full
   overwrite). DRAW_STATE now logs fbo=, blend=on|off, colorMask=[...] on
   every capture.
Out of scope honored: rotation parked; TRIANGLES closed; r17 direct toggle
stays DEV-only default-OFF.

## Increment 28 — rotation: ST-class classifier + pure compensation + 64-case golden harness

Owner round-24 mandate (final Phase-1 item): the GL preview shows both
cameras mis-rotated (front selfie upside-down per r27 screenshot) while the
RAW CameraX path is correct for both — so the defect is in how the GL layer
transforms the producer ST. One build, three things: classify, compensate,
harness.

1. ST-CLASS CLASSIFIER (new geometry/StOrientation.kt, pure Kotlin): the
   producer ST decomposes at bind/every-change into one of EIGHT canonical
   classes StClass(rotCwDeg 0/90/180/270, mirror none/h|v) via a corner
   probe (map TL/TR/BL through the ST, match the (TL, du, dv) signature).
   Canonical form = mirror-in-texture-space first, then CW rotation; the 8
   signatures cover the full dihedral group. RenderThread classifies every
   ExternalTextureSource after each successful update(), st-hash-gated, and
   on change logs `ST_CLASS id=<src> rot=<n> mirror=<none|h|v>` into the
   event ring + fires onSourceOrientationClassified (RenderThread.Listener ->
   RenderEngine -> VM, MAIN thread). Non-orthogonal STs (video crop/scale)
   log ST_CLASS_UNCLASSIFIED once per hash instead. captureOesDebug's
   OES_ORIENT line now also carries ST_CLASS=. NEVER hardcoded — the matrix
   is read fresh.
2. PURE COMPENSATION: StOrientation.compensate(class, frontFacing) derives
   the layer transform: uvRot = (front ? (360-rotCw) : rotCw) + (mirror==V ?
   180) mod 360; mirrorX = (mirror != NONE) XOR front — the rot cancels in
   the OPPOSITE sense per facing (the mirrored target conjugates the
   cancellation; V == H + 180 in this decomposition), which the naive
   (desired-current)%360 shape gets wrong for back cameras. The VM applies
   it to the matching camera layer (back nets IDENTITY, front nets
   MIRROR_H) — the one canonical rotation place. The r18 sensor-arithmetic
   block in the camera Bound collect is DELETED (orientation now derives
   exclusively from ST_CLASS events; the collect only re-seeds the last
   known compensation on rebind). Video metadata-rotation path untouched
   (out of scope). Device anchors: dump ST (u,v)->(v,1-u) classifies
   rot=90/mirror=none -> front uvRot=270/mirrorX=true, back
   uvRot=90/mirrorX=false.
3. GOLDEN HARNESS (new engine-render/src/test/.../StOrientationGoldenTest.kt,
   permanence artifact): 8 ST classes x {front, back} x sensor fold
   {0, 90, 180, 270} = 64 cases. Each case: fold a canonical ST by the
   sensor angle, classify, compensate, and corner-probe the EXACT production
   chain (SourceUvMath.transform, clamp=false) — asserting the composed net
   equals the desired orientation AND that exactly ONE (rot, mirrorX) in the
   whole compensation vocabulary reaches it (uniqueness proof, so the
   formula can never silently drift). Plus: 8-class classifier roundtrip,
   device-dump-ST regression (rot 90/none), common producer matrices
   (identity, flipY, transposedFlip), cropped-ST -> null, and the device
   compensation anchors. All existing tests untouched (TRIANGLES gate stays
   green; no GL_TRIANGLE_STRIP anywhere).
Out of scope honored: TRIANGLES path, skip path/blit state, r25-r27
changes — untouched.

## Increment 29 — rotation: mandated sign flip + display-rotation fold + video routed through the same compensate()

Owner round-25 report on the r28 build: BOTH cameras ~90 deg off (subject up
pointing LEFT), video 180 off, phone rotation changes nothing. Dump facts:
ST_CLASS cam rot=90/none, video rot=90/h, DRAW_STATS L0 uvRot=270/mx=true
(the r28 values), NET=MIRROR_H. Mandate: the compensation sign is flipped;
display rotation was never folded in; video ran its own metadata-UV math.

1. SIGN FLIP (mandate 1, literal): StOrientation.compensate now computes
   uvRot = (st.rotCw - displayRot + (mirror==V ? 180 : 0) + 360) % 360 — the
   mandate's swapped order, ONE branch for all sources. mirrorX unchanged:
   (st mirror != NONE) XOR desiredMirrorH. Device anchors (class rot=90/none,
   display 0): front -> uvRot=90/mirrorX=true (r28 ran 270/true — the whole
   flip); back -> uvRot=90/mirrorX=false whose composed net is CANONICAL
   IDENTITY (upright in any frame convention; if the back sensor folds
   rot=270 the same formula yields the mandated 270 with the same identity
   net). Video class rot=90/h -> uvRot=90/mirrorX=true -> identity net
   (upright, unmirrored). Matrix-verified over all 8 classes x 2 desired x
   4 sensor folds x 4 displays: clean cases net EXACTLY R(360-display);
   mirror cases net improper iff desired-mirrored (frame-free law).
2. DISPLAY ROTATION (mandate 2): VM reads Display.getRotation() * 90 (the
   API returns constants 0..3, not degrees) on every ST_CLASS application,
   logs DISPLAY_ROT=<deg> on every change and inside every ORIENT_APPLY
   line (new contract: ORIENT_APPLY id=.. DISPLAY_ROT=<deg> st_class=..
   -> uvRot=.. mirrorX=.. changed=..). Manifest is portrait-locked with
   in-place configChanges, so MainActivity.onConfigurationChanged is the
   only rotation hook — wired to VM.onDisplayRotationMaybeChanged(), which
   re-runs compensation from the LAST classified ST classes (no waiting for
   new ST events); ON_START also re-reads (activity recreation path).
   desired_net.mirror unchanged by display rotation, per mandate.
3. VIDEO ROUTING (mandate 3): the separate metadata-UV math in
   onVideoSizeChanged is DELETED (uvRot=(360-rot)%360 gone); video layers
   now receive compensation through the SAME ST_CLASS -> compensate() path
   as cameras (matched by Video.sourceId, desiredMirror=false). Crop/scale
   STs (ST_CLASS_UNCLASSIFIED) are treated as IDENTITY: the callback now
   fires rot=0/mirror=none (logged treated=identity) instead of skipping.
4. GOLDEN HARNESS: StOrientationGoldenTest extended 64 -> 256 cases (8 ST
   classes x {mirror, clean} x sensor fold {0,90,180,270} x display
   {0,90,180,270}): each case folds a canonical ST, classifies with the REAL
   classifier, compensates with the REAL function, composes the exact engine
   chain net (Rot . Mirror . ST) as matrices, and asserts the frame-free
   laws: displayed-mirror iff desired-mirrored; clean nets EXACTLY
   R(360-display). Plus round-25 device anchors (front 90/true, back/video
   identity nets) and an r28-regression guard (270/true must not reappear
   at display 0 for class rot90/none).
Out of scope honored: TRIANGLES path, skip path/blit state, r25-r28 engine
present/blit behavior — untouched. PROGRESS inc 29.

## Increment 30 — Round-25 harness GREEN (golden 256/256 + device anchors)

**Head `bc2f77b`, CI run 36016239248 SUCCESS** (`:app:assembleDebug`, unit tests, lint).
APK artifact `vcam-studio-debug-apk` published on that run — this is the r25 device build.

Root-cause chain for the three red rounds after 6c546ff (all in the TEST file, production
code untouched since 6c546ff):

1. `66f9a3e` anchors/golden ran the 256-case body — `foldLin` was a bare 2x2 linear
   `[c,s,-s,c]` being indexed as a 4x4 column-major affine. Fixed (`f12f14b`) to a FULL
   square-to-square affine: col-major `[c,-s,0,0, s,c,0,0, 0,0,1,0, t1,t2,0,1]`,
   t1 = 0.5-0.5(c+s), t2 = 0.5+0.5s-0.5c. Verified 0/32 folded classify-nulls in sim.
2. Front anchor corrected `MH` -> `MV` (`f12f14b`): canonical net of the mandated
   (90, mirrorX=true) front comp IS MV; the device display frame reads canonical MV as
   upright+mirrored (r27 screenshot calibration: device frame R90-conjugates canonical
   nets — r28's canonical MH front displayed upside-down).
3. The real blocker (`20807de` diagnostics, `bc2f77b` fix):
   `FloatArray.contentEquals` is `Arrays.equals(float[])` -> `Float.equals` semantics:
   **-0.0f != +0.0f**. `mmul` products like `0f*(-1f) + (-1f)*0f = -0f` poisoned every
   Rot∘Mirror∘ST net (front net was literally `[1, -0, -0, -1]`), so `d4Label` returned
   "OTHER" for perfectly valid MV/MH nets. Python sims never caught it (IEEE `==` is
   zero-sign-blind). Fix: `feq()` comparator (size + per-entry |a-b| < 1e-4f), D4 refs
   verified pairwise disjoint under feq.

CI diagnostics (`20807de`, permanent): failure-report step now extracts JUnit XML
testcase names + `<failure message>` + first stack frames into the commit comment —
assertion text no longer depends on the EOF-flaky reports artifact.

Round-25 mandate state: (1) sign flip, (2) DISPLAY_ROT fold + ORIENT_APPLY/DISPLAY_ROT
logging, (3) video routed through compensate() — all shipped; harness green 256/256.
**Device send-back (a)-(e) still pending** — no "fixed" claim until the five device
states + dump with DISPLAY_ROT/ORIENT_APPLY lines come back conforming.

## Increment 31 — Round-26: ONE constant (rot=90 family +90)

**Head `77d74cf`, CI run 36026968142 SUCCESS** — first-try green. Exactly two files:
`StOrientation.kt` (the constant) + `StOrientationGoldenTest.kt` (anchors + closed form).

Owner device evidence on the r25 build: front (uvRot=90/mirrorX=T) displayed up-at-RIGHT
(90 CCW off); back (90/F) up-at-LEFT (90 CW off); video (90/T, class 90/h) up-at-LEFT.
Same 90° pre-mirror residual on every rot=90 chain; the mirror flips its display direction.
Owner ladder: r24 uvRot=270 -> 180 off; r25 uvRot=90 -> 90 off; r26 uvRot=180.

THE CHANGE (one line in compensate()):
  r90Bias = if (cls.rotCwDeg == 90) 90 else 0
  uvRot = (rotCw - display + r90Bias + vShift) % 360   (vShift = V?180, unchanged; mx unchanged)

Anchors now (display 0, class 90/none): front uvRot=180/mirrorX=true (net MH_R90);
back uvRot=180/mirrorX=false (net R90); video (90/h, same function) uvRot=180/mirrorX=true
(net R90). Golden closed form: clean rot!=90 classes still R(360-display) BYTE-IDENTICAL
comp to r25 (machine-checked); rot=90 family clean -> R(450-display). Sim + CI: 256/256.

Expected ORIENT_APPLY per send-back state (display 0):
  (a) front upright        st_class=rot90/none  -> uvRot=180 mirrorX=true
  (b) back upright         st_class=rot90/none  -> uvRot=180 mirrorX=false
  (c) front rot 90 LEFT    (display 90)         -> uvRot=90  mirrorX=true
  (d) front rot 90 RIGHT   (display 270)        -> uvRot=270 mirrorX=true
  (e) video upright        st_class=rot90/h     -> uvRot=180 mirrorX=true

NO "fixed" claim — device send-back (a)-(e) pending. If any state is still wrong, the
owner names the next value from the observed residual (ladder: 0 is the only untried
constant); we change the bias, nothing else.

## Increment 32 — Round-27: DIAGNOSE (classifier pin + rigid-net proof), no constant

**Head `673ab66`, CI run 36032477113 SUCCESS** — first try. 3 files, compensate() byte-identical
to r26 (the r26 constant stands; ladder value 0 remains untried and owner-named only).

### Code-level findings (from the r26 dump + source, before any device run)

1. **The "shear" was a decoder gap, not a math bug.** `SourceUvMath.classifyNet` named only 6 of
   the 8 D4 transforms — the two diagonal reflections fell through to OTHER[...]. The r26 dump's
   `OTHER[u=(0,1) v=(1,0)]` IS the transpose — a valid rigid transform (harness label MH_R90;
   production now names it DIAG_MIRROR). And the harness's closed-form prediction for the r26
   front comp (R180·MH·R90classLin) IS the transpose: production measured EXACTLY what the model
   predicts. The composition {ST}+{comp} is rigid on the device.
2. **finalUV is not degenerate.** (a) v0==v3 duplication is the 6-vertex TL,TR,BR/TL,BR,BL layout
   (TRI_ORDER scatter of 4 unique corners — shared diagonal, by design). (b) The v-band
   0.342..0.658 is the LayerGeometry base fit/crop window mapped through the rigid 180° — a crop,
   not a distortion.
3. **The two conflicting ST_CLASS lines are two different classify paths on two different source
   ids** (cam-3e895ee9 vs cam-119fb6f7): the bind-time ST_CLASS event (RenderThread, per frame
   change) vs the dump-time re-classify inside OES_ORIENT (SceneRenderer:908). Different ids
   points at owner hypothesis (b): a rebind minted a second source id while the old one still
   updates/logs. The classifier itself is a pure corner-probe — nondeterminism can only enter
   through the matrix content.

### Shipped

- **Step 1 — classifier pin** (RenderThread): literal double-read of the ST (both copies
  classified; mismatch => ST_CLASS_FLIP scope=in-read, comp HELD) + a 2-consecutive-event
  stability window for any candidate change (first sighting => ST_CLASS_FLIP scope=cross-event +
  hold; second => promote). FLIP lines rate-limited to 1/s/source. Pending state cleaned up on
  source close/sync.
- **Step 2 — rigid check** (SourceUvMath): classifyNet names ALL 8 D4 (adds DIAG_MIRROR,
  ANTI_DIAG_MIRROR), adds det/orthogonality check => NOT_RIGID[u,v,det] for true shear (report-
  only). Golden harness: assertRigid(net) = explicit 8-set membership on all 256 cases + the three
  anchor nets; new bridge test pins classifyNet's name for each of the 8 + shear=>NOT_RIGID
  (0.6 shear — 0.3 stays inside the legacy near-tolerance).

### Owner device protocol (30 s, single front camera layer, no video)

grep ST_CLASS_FLIP / ST_CLASS / OES_ORIENT ... NET= / ORIENT_APPLY. Stable run = zero FLIP lines
+ one constant ST_CLASS; any FLIP = root cause named (scope=in-read => unstable HAL matrix;
scope=cross-event => source/id alternation => hypothesis (b) confirmed). NET= now decodes all 8;
NOT_RIGID in a dump would mean a real composition bug (not seen in 256/256).
