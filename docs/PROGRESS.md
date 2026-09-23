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
