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

## Increment 33 — Round-28: RESTRUCTURE (one derivation, no constants)

**Head `401706e`, CI run 36041578747 SUCCESS** (ea69c4d had a test smart-cast compile error,
fixed same round). 4 files: StOrientation.kt, RenderThread.kt, StudioViewModel.kt, golden test.

### The device model (derived, not guessed — closes r24+25+26 simultaneously)

From the owner's r28 dump + prior reports, exactly one display model fits ALL six readings:
  displayed(X) = R180·X;  upright-clean = canonical R90;  upright-mirror = canonical MH_R90.
  r24 front MH → displayed MV (=upright-mirror·R180) "180 off" ✓ · r25 front MV → MH
  (=upright-mirror·R90) "90 off" ✓ · r25 back R0 → R180 (=upright-clean·R270) "90 off" ✓ ·
  r26 front MH_R90 → MH_R270 (=upright-mirror·R180) "180 off" ✓ · r26 back R90 → R270
  (=upright-clean·R180) "180 off" ✓.

### THE DERIVATION (sole source of truth; compensate() DELETED)
  transformFromST(st, isFront, display) = classify(st) → transformFromClass:
    mirrorX = (st.mirror != NONE) XOR isFront
    uvRot   = (st.rotCw - display - 90 + (V?180)) mod 360
  Class-covariant: BOTH observed camera classes ((90,none)→(0,true) and (90,h)→(0,false) at
  display 0) land upright under the model — no per-class constants.
  Predicted anchors @display0: front (0,true) net MH_R270; back (0,false) net R270;
  video (90,h) (0,true) net R270. Ladder end uvRot=0 = theorem, not guess.

### Classifier hardening (mandate 2)
  ST read twice per classification; reads must agree or the frame is INVALID (hold previous
  transform). Unclassified → ST_CLASS_AMBIGUOUS + hold (identity only as ever-first default).
  ST_CLASS_FLIP label REMOVED. The r28 dump had already proven the classifier deterministic:
  st_hash 4AE91905 constant across 300+ frames, zero in-read flips; the r27 FLIP lines were
  first-classification events of newly created sources (`first=none` = sentinel, removed).

### Record corrections (owner premises vs their own dump)
  - "classifier non-deterministic": NO — constant st_hash; flips were new-source firsts.
  - "NET=DIAG_MIRROR is a shear": NO — it is the transpose, one of the 8 D4 rigid transforms;
    production measured exactly the harness's closed-form prediction.
  - "finalUV degenerate": NO — v0==v3 is the 6-vertex shared diagonal; the quad's corners are
    TL(0,0.342) TR(0,0.658) BR(1,0.658) BL(1,0.342) — four distinct.

### Harness (mandate 4)
  Drives transformFromST over 256 cases (8 classes × isFront × sensor fold × display).
  Laws: net rigid ALWAYS; improper IFF isFront; clean == R((270-display) mod 360) for every
  class. 256/256 in sim AND CI. Expected device lines (front): ST_CLASS rot=90 …; NET=
  ANTI_DIAG_MIRROR (rigid ✓); finalUV four distinct corners.

Ops: sandbox restored the base commit AGAIN mid-round (2nd time); recovered via fetch +
reset --hard to c14b6aa + re-apply of the 4 staged files (verified diff-identical).

## Increment 34 — Round-29: UI wiring (REC + scene tabs)

**Head `e3e82cf`, CI run 36051740222 SUCCESS** (8dee59a lacked a theme import, fixed same round).
2 files: StudioScreen.kt, StudioViewModel.kt. No engine/rotation/GL changes.

### Findings (code-level, before the fixes)
- The recording CHAIN was already fully wired (RecChip -> toggleRecording -> RecordingController
  -> RecordingSession [real MediaCodec+MediaMuxer, AVC Surface input + AAC from mixer] ->
  engine.attachRecordingOutput -> render-thread RECORDING_OUTPUT_ID second output). Two real
  defects made REC read dead: (a) the idle chip was StudioBorder grey (looks disabled) and
  (b) startRecording() SILENTLY returned when uiState.activeScene was null (stale active id).
- Scene tabs: the red "delete" label rendered ALWAYS when scenes.size > 1 (looked like a stuck
  delete-mode). Active highlight (surfaceVariant + 1dp border) was near-invisible in dark theme.
- Duplicate "Scene 2": count-based naming (size+1) after a deletion — delete "Scene 1" leaves
  "Scene 2"; create names size+1="Scene 2" again. Exactly the device screenshot state.

### Fixes (8dee59a)
1. REC chip: enabled = health != UNHEALTHY && scenes non-empty && activeSceneId non-empty;
   idle chip now StudioRed (visibly tappable), disabled = 40% alpha + not clickable; recording
   state unchanged (amber ● REC mm:ss, tap = stop+save).
2. startRecording: stale/empty activeSceneId no longer silent — REC_FALLBACK log + recover to
   first scene; toast only if NO scene exists.
3. Scene tabs: delete affordance ONLY in per-tile edit mode (long-press); exits on any tap,
   scene selection, and back-press (BackHandler); edit-mode tile gets error-tinted border/bg.
   Active tab: accent fill (28%), 2dp accent border, bold accent label + dot marker.
4. Naming: nextSceneName() = max existing "Scene <N>" + 1 (deletion-proof; sim-verified on the
   exact device scenario ['Scene 2','Scene 2'] -> next "Scene 3").

Device verification (owner): REC active + timer while recording; 3 scenes Scene 1/2/3 with one
highlight and no delete labels; long-press -> labels appear, tap-away -> disappear; recorded
MP4 playable + shareable. Recording pipeline itself unchanged this round.

## Increment 35 — Round 30 (f734da4): recorder codec-ordering fix + master audio graph

Owner device evidence on r29 build (e3e82cf): (1) REC tap → toast "Recorder: setInputSurface() is valid only at Configured state; currently at Running…" — recording never began. (2) Media-bus mute showed MUTED but video-layer audio stayed audible (bypassed the bus). Mandated FIX A + FIX B, both shipped.

### FIX A — codec lifecycle (RecordingSession.kt / RecordingController.kt)
- Root cause: session property-init ran `createVideoCodec()` (configure + **start()**) before `createInputSurface()` → `IllegalStateException` on a Running codec → controller catch → onFailed → Idle.
- Contract now enforced: construct = `configure()` both codecs (createInputSurface in Configured state; muxer still starts lazily on first video frame) → controller posts surface to app (`onSurfaceReady` → `engine.attachRecordingOutput`, render thread owns the EGL window) → `session.start()` = `codec.start()` ×2 + drain threads spawn → stop = `signalEndOfInputStream` → drain → `stop` → `release`.
- `start()` failure inside the controller cleans the session up on its executor (`s.stop()`), reports onFailed; `toggleRecording` ignores Starting/Stopping.
- RECORDER_STATE lines: `RECORDER_STATE CONFIGURED file=<name> WxH@fps` (session init), `SURFACE_ATTACHED WxH` (VM onSurfaceReady), `STARTED` (session.start), `STOPPED file=<name> bytes=<n>` (+ `(controller)` variant when the controller tears down). App wires `recorder.eventSink = { Timber.i(...) }`.

### FIX B — audio graph exclusivity (MasterMonitor.kt / StudioViewModel.kt / VideoLayerController.kt)
- Root cause: `TeeAudioProcessor` is a tee — ExoPlayer's `DefaultAudioSink` rendered straight to the speaker while the mixer's output reached only the recorder (its pump ran only while Recording), so bus faders/mutes had no audible effect.
- New `MasterMonitor` (engine-audio): USAGE_MEDIA AudioTrack (48 kHz stereo PCM16); `write()` blocks → real-time pacing of the pump.
- ONE mix pump, always on, in `StudioViewModel.init`: `mixer.read()` → master monitor always → `recorder.offerAudio` while Recording. The record-only pump is deleted.
- Video-layer direct output pinned silent whenever a tap is installed (`player.volume = 0f` in `load`; `setMuted`/`setVolume` no-op in tapped mode). media3 applies player volume at the AudioTrack AFTER the tee ⇒ the tap keeps full-scale PCM; per-layer volume/mute is applied app-side on the MEDIA bus feed (videoParams re-read per buffer; zero-volume skips the offer).
- Graph: Mic → MIC bus, video → MEDIA bus, buses (gain×mute) → master limiter → monitor/recorder. `AUDIO_ROUTE source=mic bus=mic` / `AUDIO_ROUTE source=video sourceId=<id> bus=media` logged at graph build. Muted MEDIA bus ⇒ ring drops frames ⇒ master receives silence within one 20 ms buffer.

### Verification checklist (device, r30 build)
- REC tap → amber ● REC timer counts; MP4 grows in app-private Movies/; appears in recordings list on stop; plays.
- Mute cycle on a video layer: audible → silent ≤ one buffer (no tail > 100 ms) → Live → audible.
- Log dump contains RECORDER_STATE CONFIGURED→SURFACE_ATTACHED→STARTED→STOPPED and both AUDIO_ROUTE lines.
- 0 fps in the status bar while the mixer sheet is open = EXPECTED (preview backgrounded), not a bug.

### CI
- f734da4 (code) run: SUCCESS (first try). Docs increment: this commit.

## Increment 36 — Round 31 (461cf13): recordings land in the public media library

Owner evidence on r30 build: recording works end to end (REC, timer, encoder chain, MP4 produced —
shared via a third-party app in the device screenshot), but the file was written to
`getExternalFilesDir(DIRECTORY_MOVIES)` — app-private, unindexed by MediaStore on Android 10+, so
gallery/Files never see it. Mandated ONE fix: write to PUBLIC MediaStore. Encoder chain untouched.

### Output contract (engine-output)
- New `RecordingOutput` sealed interface: `FileOutput(file)` | `FdOutput(ParcelFileDescriptor, name, uri)`.
  `RecordingSession`/`RecordingController` are output-agnostic; muxer is `MediaMuxer(fd)` on MediaStore
  targets (fd closed by the session after `muxer.release()`; created in a try that closes the fd on failure).
- `State.Recording(output, startedAtMs)`; `stop(onStopped: (RecordingOutput?) -> Unit)`. STOPPED log carries
  bytes (`file.length()` for file targets, sampled sum for FD targets).

### MediaStore path (API 29+, the owner's device)
- `RecordingStore.createTarget`: insert row (`DISPLAY_NAME vcam_<ts>`, `MIME video/mp4`,
  `RELATIVE_PATH Movies/VCamStudio`, `IS_PENDING 1`) → `openFileDescriptor(uri,"rw")` → encoder writes into
  the row's fd. On stop: fd closed → `publish` sets `IS_PENDING 0` → clip visible in gallery/Files
  immediately. Failed start/discarded busy → `discardPending` (no 0-byte ghosts).
- Logs: `REC_TARGET kind=mediastore|public-legacy|private-fallback name=… uri=…`, `REC_SAVED uri=…`.

### Legacy path (API <29)
- Public `Movies/VCamStudio` via `getExternalStoragePublicDirectory` + `MediaScannerConnection.scanFile`
  after stop; `WRITE_EXTERNAL_STORAGE` declared with `maxSdkVersion="28"`; falls back to the private dir
  (still shareable via FileProvider — `file_paths.xml` gained `external-path Movies/VCamStudio`).

### In-app library (mandate 2 — same URIs as the gallery)
- New `RecordingsSheet` ("Clips" button in the dock bar): MediaStore query
  `RELATIVE_PATH LIKE 'Movies/VCamStudio/%' AND DISPLAY_NAME LIKE 'vcam_%'` sorted DATE_ADDED DESC
  (legacy: public dir listing). Tap = `ACTION_VIEW` on the content uri; Share = `ACTION_SEND` content uri.
  Post-stop auto-share now uses the MediaStore uri (no FileProvider hop).

### Migration (mandate 3)
- `StudioApp.onCreate` spawns `RecordingStore.migrateExisting`: moves every `vcam_*.mp4` from the old
  private locations (external app Movies dir + filesDir root) into Movies/VCamStudio, deletes the private
  copy, logs `MIGRATED_RECORDING name=<n>` per move + `MIGRATED_RECORDINGS count=<n>` (idempotent).

### CI ledger
- f8f1bdb FAIL `:engine-output:compileDebugKotlin` — member smart cast does not cross the `runCatching`
  lambda (`output.pfd` unresolved) → capture `val fdOut = output as? FdOutput` (df09238).
- df09238 FAIL `:app:compileDebugKotlin` — top-level `RecordingItem` duplicated `RecordingStore.Item` →
  single model `RecordingStore.Item` (461cf13 SUCCESS). Docs: this commit.

### Device verification checklist (r31 build)
- Record → clip appears in system Files/Gallery under Movies/VCamStudio/vcam_<ts>.mp4 with no share-through.
- In-app Clips sheet lists the same clip; tap plays; Share hands the file to the target app directly.
- Reinstall-over-old-build → private clips migrate into Movies/VCamStudio (MIGRATED_RECORDING in log).

## Increment 37 — Phase 2 build war (rounds 32-41): first green :app assemble

Phase 2 shipped feature-complete in 3db25ae (Model Manager + SCRFD) — then the
build war began. Final resolution: **0668992 (rounds 39+40) builds green in
~5 minutes** with a 22 MB arm64 APK (run 36240595047: Compile 2m56s, Dex 38s,
Package 1m08s).

### What it was NOT (all exonerated by controlled runs)
- Maven Central throttling (r32b): mirrors made it worse (aliyun 502-cascaded,
  run 36100804971); canonical repos only since.
- ORT AAR size: the asset is 26.6 MB, not ~200 MB (r33); vendoring via CDN
  worked (1 s) yet the hang persisted (run 36138073042 capped at 120 min).
- packageDebug payload alone: fixed by arm64-only + jniLibs excludes (r35),
  Package is now 68 s — but the hang predated it.
- Runner degradation: real symptoms existed (cache service 400s all day,
  0 caches persisted repo-wide, job-log blob EOFs for 20+ h) but were not
  the build blocker.

### What it WAS (three stacked bugs, each masking the next)
1. **Kotlin 2.0.20 inference hang**: the inline `ImageAnalysis.Analyzer` SAM
   lambda on FaceDetectionController (with StateFlow props) hung the compiler
   indefinitely (owner's Codespace bisection: both files real = hang forever;
   either stubbed = 17 s). Fix (r39, e395511): analyzer as a proper class
   (ScrfdAnalyzer) + internal currentDetectorOrNull/lastRunMs/handleFrame —
   logic moved verbatim.
2. **Latent missing imports** beneath the hang (r40, 5efa0ab): ScrfdAnalyzer
   lacked `import timber.log.Timber` (new file, r39) and the controller never
   imported `java.nio.FloatBuffer` — :engine-ai-face had NEVER compiled; the
   hang hid it.
3. **Nested-class import** (r40 fix 2, 0668992): `SessionOptions` is
   `ai.onnxruntime.OrtSession.SessionOptions`, not top-level — the only ORT
   import failing on both vendored and Maven classpaths.

### Build-config hardening that stays (r35/r36, correct end-state)
multiDexEnabled; no-daemon + -Xmx2g + in-process Kotlin; arm64-v8a-only
abiFilters + jniLibs excludes (x86/x86_64/mips) + keepDebugSymbols for the
pre-stripped ORT libs; 60 s HTTP timeouts; step-level bisection (Compile/Dex/
Package @ 15-30 min); job timeout 120 min. Vendored-AAR CI fetch removed
(r40): an AAR via files(...) contributes no classes to compilation; Maven
1.20.0 is the compiling path (the libs/ort.aar conditional remains in the
build files for future vendoring done right).

### Device verification now OPEN (Phase 2 checklist)
Settings -> AI Models: SCRFD row (Get model, 16.9 MB, sha256 5838f7fe...) ->
download -> Ready; kill mid-download -> resume (MODEL_DOWNLOAD_RESUME);
hash mismatch -> MODEL_HASH_MISMATCH. Camera layer + face -> cyan box overlay
+ confidence; Diagnostics: SCRFD_MS/SCRFD_FPS/FACE_BOX; NNAPI dev toggle
default off. Screenshots + dump or it did not happen.

## Increment 38 — Round 42: device crash post-download; crash net + guarded model path

Field report (owner, Samsung S22 Ultra, fresh r40/r41 debug install): AI Models ->
SCRFD -> Get -> download climbs to 100%, app dies immediately after completion;
every reopen then shows the system crash dialog. First execution ever of the
download-complete path on a device — and, because init adopts an existing file as
READY, the same path (setModel -> ORT session build) also runs at every launch,
which explains the reproducible reopen crash.

### FIX 1 — crash net (app/crash/CrashLogger.kt, NEW)
- `CrashLogger.install(this)` FIRST in StudioApp.onCreate: chained
  UncaughtExceptionHandler writes `/sdcard/Android/data/<pkg>/files/crashes/
  crash-<epochMs>.txt` — header (version/device/SDK) + `thread=<name>` +
  `Log.getStackTraceString` + the last 400 RingLog engine lines — then delegates
  to the PREVIOUS handler (platform dialog preserved). DEVIATION from the
  mandate snippet (intent preserved): the snippet re-reads
  getDefaultUncaughtExceptionHandler() INSIDE the handler = self-reference =
  guaranteed StackOverflowError; we capture the previous handler before install.
- `RingLog` (Timber tree, planted debug AND release): synchronized 400-line RAM
  ring — the owner has no adb, so MODEL_STATE/SCRFD lines only reach him if the
  crash file carries them.
- `CrashLogger.onAppStart` on "vcam-crash-boot" thread: CACHE_TOMBSTONE sweep
  (crash files > 7 days deleted, counts logged) + append launch line (ts +
  versionName/versionCode/debug) to `files/launch.log`.
- Diagnostic asymmetry (documented in-file): a NATIVE death (SIGSEGV/SIGABRT in
  JNI, e.g. ORT session code) bypasses ANY Java handler. If a crash leaves NO
  crash file -> native suspect -> next round attacks session build directly.

### FIX 2 — guarded post-download path (engine-ai-core ModelManager)
- State enum gains VERIFIED (between DOWNLOADING and READY; FAILED/READY
  existed): DOWNLOADING -> (hash ok) -> VERIFIED -> (promoted) -> READY.
- ALL state changes now flow through guarded `transition()`: try/catch(Throwable)
  + `MODEL_STATE name=<file> state=<STATE>` logged on every STATE change
  (DOWNLOADING progress ticks are pct changes, not state changes -> not logged,
  else 16.9 MB / 64 KB would flood the ring).
- Post-100% tail, per step: hash mismatch -> runCatching delete of partial +
  MODEL_HASH_MISMATCH + FAILED (flag, no throw, return); VERIFIED marker
  publish; promote() — rename-first (same-volume rename is atomic AND overwrites
  an existing destination on Android), fallback stream-copy to `.tmp` sibling +
  atomic rename over destination (no RAM slurp of 16-280 MB models), fully
  runCatching, never throws if destination exists; then MODEL_DOWNLOAD_DONE +
  READY. Any failure -> guarded FAILED, never the vcam-model-dl thread floor.
- init adoption (file exists -> READY) guarded (runCatching exists + MODEL_ADOPT
  log); delete() guarded.
- StudioViewModel: all four SCRFD/model observable callbacks (states, camera-
  layer combine, stats, phase) runCatching-wrapped -> MODEL_OBSERVE_FAIL logged;
  an observer bug can no longer kill the main thread.
- ModelsSheet: exhaustive when gains VERIFIED -> "Verified…" row label.
- Diagnostics dump gains MODEL_SECTION (ModelManager.dumpSection: per-row
  MODEL name/state/pct/bytes/msg) appended after SCRFD_SECTION.

### Round 42 contracts
Crash file path: /sdcard/Android/data/<applicationId>/files/crashes/ (debug build
suffix .debug applies on-device). launch.log: one line per boot. New log keys:
MODEL_STATE / MODEL_STATE_FAIL / MODEL_ADOPT / MODEL_PROMOTE_FAIL /
MODEL_PROMOTE_COPY_FAIL / MODEL_OBSERVE_FAIL / CACHE_TOMBSTONE (sweep|launch) /
CRASH_LOGGER_INSTALL. Dump keys added: MODEL_SECTION > MODEL_STATES.
Untouched (mandate): r39 analyzer class, r40 SessionOptions/import fixes, r41
tests+lint, Kotlin/AGP/ORT versions, all CI green-path config.

## Increment 39 — Round 43: crash log to public DCIM; whole post-download chain instrumented

Field (owner, S22 Ultra, r42 build): crash STILL reproduces at download
completion AND the r42 crash file is UNREADABLE — Android/data is gated to file
managers on Android 11+. Net effect: the r42 net probably WROTE the trace; the
owner just cannot open it. R43 makes the sink public and the chain loud.

### FIX 1 — reachable sink: /sdcard/DCIM/VCamStudio/ (crash + launch.log)
- Crash dumps: /sdcard/DCIM/VCamStudio/crashes/crash-<epochMs>.txt.
  - API 29+: MediaStore.Files.getContentUri(VOLUME_EXTERNAL_PRIMARY) +
    RELATIVE_PATH="DCIM/VCamStudio/crashes/" + IS_PENDING flow, via
    ContentResolver — no permission. DEVIATION from the mandate's
    "MediaStore.Video or MediaStore.Images": those collections validate mime
    against media extensions and REJECT .txt on-device; MediaStore.Files is
    the resolver-based collection that accepts text under DCIM (R+).
  - API <= 28: direct file path under DCIM (WRITE_EXTERNAL_STORAGE already
    declared maxSdkVersion=28 — the r31 recordings pattern; NOT changed this
    round). Honest note: pre-29 also needs that permission granted at
    runtime, which no UI does yet — those devices take the fallback.
  - ANY failure: r42 app-specific fallback. Which sink won is IN the file
    header (`sink=`) and logcat (CRASH_LOG_SINK).
- launch.log: /sdcard/DCIM/VCamStudio/launch.log, one line per boot
  (ts + versionName + versionCode + debug), append via openFileDescriptor
  "wa" on the owned MediaStore row (API 29+), direct append pre-29.
- CACHE_TOMBSTONE now: legacy-dir sweep + MediaStore 7-day sweep of the
  crashes dir (DATE_MODIFIED cutoff) + a WRITETEST file at boot
  (writetest-<ts>.txt, kept — file-manager proof WITHOUT a crash; swept
  after 7 days) + the launch marker.
- RingLog unchanged (400-line ring rides along in every crash file).

### FIX 2 — whole post-download chain guarded + step-marked
- MODEL_DL markers (crash ring names the LAST step reached; the missing _OK
  is the suspect): MODEL_DL_VERIFY_START / MODEL_DL_VERIFY_OK (ModelManager
  verify), MODEL_DL_MOVE_START / MODEL_DL_MOVE_OK (promote),
  MODEL_DL_STATE_READY (after READY transition), MODEL_DL_SESSION_CREATE_START /
  MODEL_DL_SESSION_CREATE_OK (FaceDetectionController.setModel — the
  boot-adoption path marks the same lines, which covers the reopen crash).
  Mandate listed "six" but enumerated seven — all seven implemented.
- Guarded the last unguarded async continuation in the chain:
  CameraSource.setAnalysisAnalyzer's main-scope rebind (READY ->
  syncDetection -> rebind) is runCatching-wrapped (ANALYSIS_REBIND_FAIL);
  bindInternal's inner coroutine already caught Throwable -> State.Failed.
- syncDetection() itself is now never-throw (MODEL_OBSERVE_FAIL
  src=syncDetection) — it is reachable from collectors and UI alike.
- Compose: ModelsSheet's when-block and FaceDebugOverlay are pure rendering
  (no side effects on recomposition) — verified; Compose-internal throws are
  Java exceptions and land in the r42 net + DCIM file.
- VM-clear-vs-download: ModelManager/FaceDetectionController are @Singleton
  (AppModule) — the download executor outlives any ViewModel; r42 collector
  guards cover the rest.

### Verify (owner, next boot + next crash)
Boot once: file manager -> DCIM/VCamStudio/ shows launch.log and
crashes/writetest-<ts>.txt — reachability PROVEN without a crash. Then
reproduce: crash-<ts>.txt appears in DCIM/VCamStudio/crashes/; its ring's
last MODEL_DL_* line names the failing step; `sink=` confirms which path won.
Untouched per mandate: r39/r40 fixes, unit tests, versions, CI config.
NOTE (sandbox): local git state reset to the initial commit between rounds
(restore incident #9); recovered via fetch + mixed reset to 8d49158 —
working tree verified byte-identical before this round's edits.

## Increment 40 — Round 44: park detection, ship the studio; r33 LANDED

Field verdict (owner): the crash leaves NO Kotlin trace and NO crash file —
consistent with a NATIVE death inside OrtSession creation (outside every
catchable surface). Owner decision: stop chasing, park detection off the user
path, land r33.

### Decision 1 — detection is parked (no ORT on the user path)
- Download-complete: model verifies -> VERIFIED -> promoted -> READY. That is
  where the chain ENDS. No setModel, no OrtSession, no ScrfdDetector — the
  file sits on disk (READY == the mandate's MODEL_DOWNLOADED; the "not
  active" half is carried by the UI label, not the enum).
- DEV toggle "DEV: enable SCRFD session (requires restart)" in Diagnostics
  (debug-build section like the other DEV switches), persisted in DataStore
  (dev_scrfd_session, default OFF). The BOOT-TIME value (read once per
  process) drives the attempt — flipping mid-session never hot-starts ORT.
- Native-crash sentinel (filesDir/scrfd_session_sentinel): armed right before
  FaceDetectionController.setModel; cleared when the phase collector proves
  the process survived (RUNNING or Java-level SESSION_FAILED). A boot that
  finds the sentinel auto-disables the toggle, deletes it, toasts, logs
  SCRFD_DEV_AUTODISABLE. Mandate's DO-NOT honored: NO new guards around the
  ORT path — only gating + bookkeeping.
- New logs: SCRFD_DEV_TOGGLE / SCRFD_SENTINEL_ARMED / SCRFD_SENTINEL_CLEARED /
  SCRFD_DEV_AUTODISABLE.

### Decision 2 — detection UI hidden until the DEV toggle is used
- FaceDebugOverlay (cyan box + confidence): not composed unless toggle on.
- Diagnostics: SCRFD_MS/SCRFD_FPS/SCRFD_RUNS/FACE_BOX block + NNAPI row only
  when toggle on; diagnosticsDump omits SCRFD_SECTION likewise (MODEL_SECTION
  always present).
- AI Models row: "Ready (not active)" (READY + toggle off), "Ready ✓" when
  the dev session is enabled. syncDetection additionally requires the toggle
  before attaching the analyzer.

### r33 LANDED (queued since r32)
- FIX A — RecordingSession PTS rebase: firstVideoPtsUs captured on the first
  surface-encoded buffer, firstAudioPtsUs on the first queued audio buffer;
  every PTS rebased to its track origin (video subtraction is the real fix —
  audio was already session-relative, kept for contract parity).
  REC_PTS_ORIGIN video=<us> audio=<us> once both known; REC_DURATION
  us/ms/sec at stop (max of both track ends).
- FIX B — monitor routing: AudioMixer.readInto(out, monitorOut) — the
  recorder keeps the FULL mix; the monitor mix excludes MIC unless
  setMonitorMic (DEV "monitor mic", persisted, default OFF, runtime-applied —
  headphones case). TTS stays monitored. Limiter applies to the record path
  only (single stateful pass). MasterMonitor unchanged as renderer (routing
  lives at the feed). AUDIO_MONITOR bus=<..> enabled=<..> logged at graph
  build + on toggle. MonitorRoutingTest.kt (4 tests): mic-excluded-by-default,
  dev-toggle-includes, TTS-monitored, pop-once-no-double-count.

### Device verification queue (owner, r44 build)
1. Boot + download: no crash, row "Ready (not active)", DCIM launch.log +
   writetest still prove the r43 sink. 2. Fresh 60 s recording -> correct
   duration (REC_PTS_ORIGIN/REC_DURATION). 3. Speaker with media + mic feed:
   no mic echo; DEV monitor-mic on + headphones = mic audible. 4. (Optional,
   DEV) enable SCRFD session toggle, restart -> if it natively crashes, next
   boot auto-disables with the toast.

### DO-NOTs honored
r39/r40 untouched; Kotlin/AGP/ORT versions untouched; ORT dependency stays;
no model bundling; no new ORT guards. Sandbox restore incident #10 (git reset
to initial commit between rounds) recovered via fetch + mixed reset — tree
verified intact first.

## Increment 41 — Round 45: media-bus echo in recordings closed at the monitor

Field (owner): recordings with a video layer play the video's audio TWICE —
direct + delayed 100-200 ms. Same feedback shape r33 FIX B closed for the Mic
bus: MEDIA still reached the monitor, speaker -> mic -> recording's MIC bus.

### Implementation
- AudioMixer: r44's single monitorMic flag generalized to a @Volatile monitor
  MASK over AudioBusId (default: every bus except MIC). setBusEnabled(id, on)
  / isBusEnabled(id) — MONITOR-ONLY by contract (the recorder is fed before
  the split and keeps the full mix). setMonitorMic/monitorsMic kept as MIC
  wrappers (r44 contract + MonitorRoutingTest unchanged-and-passing).
- REC arm (startRecording, right after mixer.reset(), BEFORE recorder.start):
  capture prev monitor state of MEDIA + MUSIC; unless the DEV toggle is on,
  mute both from the monitor; log AUDIO_REC_MONITOR media_enabled=false
  reason=arm (true + reason=arm when the DEV toggle is on).
- REC disarm (top of stopRecording): restore both, log AUDIO_REC_MONITOR
  media_enabled=<restored> reason=disarm. Failed recorder.start restores too.
  Fresh-VM init restores defaults (a process killed mid-recording must not
  leave media muted forever).
- DEV toggle "monitor media while recording" (default OFF, persisted
  dev_monitor_media_rec): Diagnostics DEV section, DataStore-backed, consulted
  synchronously at arm via the VM mirror flow.
- MUSIC bus: identical arm/disarm treatment now, so the Phase-3 stub inherits
  the correct behavior when it lands (mandate 4).
- Tests: MonitorRoutingTest +2 — media rec-mute is monitor-only (monitor 0 /
  record intact / restore works), MUSIC same pattern.

### DO-NOTs honored
SCRFD/DEV-toggle/ModelManager/ORT untouched; no ORT-crash "fix" (parking is
the end-state); rotation/TRIANGLES/skip/blit untouched; r33 A+B untouched
(readInto math identical, mask lookup replaces the single flag).

## Increment 42 — Round 46: ORT 1.20.0 -> 1.17.1 (one-variable native-crash experiment)

Field signature: native abort inside libonnxruntime.so at
OrtEnvironment.createSession (no Kotlin trace, no crash file; parked r44).
Hypothesis under test: 1.20.0's native-lib/Kotlin-metadata overhaul; 1.17.1
is the last release before it. NOT the r32-era 1.17.0 question (that was the
compile-time metadata hang r39 killed with the ScrfdAnalyzer class).

Diff (complete): gradle/libs.versions.toml onnxruntime "1.20.0" -> "1.17.1";
app/build.gradle.kts Maven fallback literal -> 1.17.1. engine-ai-face
follows via libs.onnxruntime.android (version.ref). Vendored libs/ort.aar
conditional untouched; no source/tooling/other-version changes; SessionOptions
import untouched.

CI: run 36255250801 GREEN first try (:engine-ai-face:compileDebugKotlin
compiled against 1.17.1 -> the compile-time-metadata concern is empirically
dead; assembleDebug APK produced). Device protocol: DEV toggle ON ->
force-close -> relaunch; launches = 1.17.1 fixed the native crash -> verify
SCRFD READY + cyan box; still crashes = report and stop, round 47 is process
isolation.

## Increment 43 — Round 47: ORT process isolation (:ai) — the durable fix

Two ORT versions (1.20.0, 1.17.1) natively abort at OrtEnvironment.createSession
with the same signature (no Kotlin trace, no crash file, instant death).
Version-swapping is dead; per owner mandate the durable fix: the main process
never touches ORT again.

### The isolation
- Manifest: <service android:name=".ai.AiInferenceService"
  android:process=":ai" android:exported="false"/> in the APP manifest.
- AiInferenceService (app/ai/): session creation on a "vcam-ai-probe" worker
  thread behind the model_path extra; reports pid/state/model/ts into
  ai_proc_state.tmp at started -> ok | fail | stopped (shared filesDir, same
  uid). Detector HELD ALIVE per mandate (round 48 wires inference); closed in
  onDestroy (clean teardown only — a native death never reaches it). Logs
  AI_PROC_START pid= / AI_PROC_SESSION_OK model= / AI_PROC_SESSION_FAIL.
- AiProcMonitor (app/ai/, MAIN process): probe() = mandate steps 3+4 — writes
  ai_proc_probe.tmp (main pid), startService, polls the child report +
  /proc/<pid> liveness every 2 s for 30 s (the mandate's "simpler" option;
  same-uid /proc visibility). Child died in-window -> AI_PROC_CRASHED +
  AI_PROC_STATE=dead + deathEvents. Stale reports discarded via the report ts.
- Boot path (StudioApp): process-name-guarded onCreate (26-27 via
  /proc/self/cmdline, 28+ Application.getProcessName()). MAIN process only:
  tombstone sweep, migrate thread, and startAiProcProbeIfArmed() — DEV SCRFD
  toggle ON (DataStore, read on a boot thread; nothing blocks the main
  thread) + model READY -> AiProcMonitor.probe(path). Toggle OFF -> nothing.
  r44's main-process sentinel RETIRED (retired file deleted at boot; the
  VM's tryScrfdDevSession/clearScrfdSentinel machinery removed).
- Main-process ORT paths eliminated: syncDetection never attaches the
  analyzer (round 48 re-wires via :ai); the states collector no longer calls
  setModel for READY; phase collector's sentinel-clearing gone. The main
  process's remaining FaceDetectionController usage is state-only (setModel
  (null) / stats / phase / dumpSection) — no ORT construction anywhere.
- Diagnostics: AI_PROC_SECTION (AI_PROC_STATE=idle|running|dead /
  AI_PROC_PID=<pid|-> / AI_PROC_LAST=<ts> / AI_PROC_MODEL=<path> /
  AI_PROC_DETAIL=<reason>); VM toasts "AI inference stopped unexpectedly —
  see Diagnostics" on deathEvents (mandate 6).

### Expected device behavior (owner's test)
DEV toggle ON + model on disk + restart: if ORT aborts again, the studio
STAYS USABLE and Diagnostics shows AI_PROC_STATE=dead within ~30 s (watcher
poll) — the app is now immune to the native crash. If 1.17.1 survives,
AI_PROC_STATE=running + pid, and round 48 wires real inference. Either way
the main process lives.

### DO-NOTs honored
No new ORT versions (1.17.1 stays); main-process engine/render/capture/
output/audio untouched; no Kotlin catch attempted around the native abort;
no dependency changes. Restore incident #12 mid-round recovered via fetch +
mixed reset (diff verified = exactly the r47 changeset before commit).

## Increment 44 — Round 48: SCRFD native crash — CPU-only EP + model-file proof

r47 verdict (owner dump): AI_PROC_STATE=dead, DETAIL=child died without Java
trace — native abort, main process healthy. Isolation works; the crash lives in
ScrfdDetector init. r48 narrows variables (owner mandate, three changes, all in
ScrfdDetector.kt):

- CHANGE 1: SCRFD_MODEL_FILE exists=<bool> size=<bytes> logged BEFORE
  createSession — proves the on-disk file (expected ~16,923,827 B; a truncated
  ONNX protobuf aborts natively at parse). Placement note: top of init (the
  mandate said "just before createSession" — earlier is strictly safer and
  still before the call).
- CHANGE 2: CPU-ONLY EP — addXnnpack/addNnapi GONE; intra=2, inter=1,
  setEnableCpuMemArena(false), setEnableMemPattern(false);
  SCRFD_EP cpu-only arena=off memPattern=off. useNnapi stays in the signature,
  ignored (API compatibility).
- CHANGE 3: defense try/catch around createSession -> SCRFD_CREATE_FAIL +
  rethrow (native aborts escape it; a catchable one now leaves the log).

Observability bridge (required for the mandated dump lines to exist at all):
the child's RAM ring dies with its native abort, so SCRFD_* lines could never
reach the main process's dump. NEW app/ai/AiChildFileLog.kt — file-backed
Timber tree planted in :ai only (StudioApp proc-name branch); truncated at
probe start (service onCreate); 32 KB cap. AiProcMonitor stores appContext at
probe() and dumpSection appends the last 24 lines as AI_PROC_CHILD_LOG=...
ModelManager: adopted files now report their REAL downloadedBytes — the r47
dump's "bytes=0" on the READY scrfd row was an init-adoption artifact, NOT
truncation evidence (SCRFD_MODEL_FILE is the authoritative check).

Expected next dump: AI_PROC_STATE=running (XNNPACK was the crash — done) or
dead + AI_PROC_CHILD_LOG showing SCRFD_MODEL_FILE size + SCRFD_EP as the last
lines (abort inside createSession with CPU-only) — and if size < 16,000,000,
the file is corrupt: fix is re-download + re-verify, not ORT.

DO-NOTs honored: no XNNPACK reverts, no ORT version change, main-process
engine untouched, r33/r45 untouched, :ai structure unchanged.

### r48 CI ledger (4 runs) + the K2 resolution anomaly
- 36294970948 (cf73e0e) FAIL: setEnableCpuMemArena/setEnableMemPattern do not
  exist in ORT 1.17.1 (they are later-API methods). Verified against ORT
  v1.17.1 OrtSession.java on GitHub: addCPU(boolean useArena) +
  addConfigEntry(String,String) exist -> addCPU(false) (arena) + config keys
  session.enable_cpu_mem_arena/session.enable_mem_pattern (unknown keys are
  ignored harmlessly by ORT core). fe469a8.
- 36295137527 (fe469a8) FAIL: Unresolved reference 'AiChildFileLog' from
  StudioApp ONLY — byte-level verification of the committed blobs (package,
  object name, import) was clean; probe-wedge green proved checkout integrity;
  the SAME symbol resolved from same-package AiProcMonitor.kt (no error).
- 36295436189 (e28892e) FAIL: fully-qualified reference ALSO unresolved;
  AiProcMonitor's reference to the relocated symbol still resolved.
  PATTERN: any new symbol added to package com.vcamstudio.app.ai in r48 is
  invisible to StudioApp's cross-package references (import AND FQN), while
  r47's symbols from the same package import fine and same-package
  references resolve. Cause UNKNOWN (K2 anomaly suspected); documented, not
  guessed.
- 36295649054 (b205568) GREEN: routed around — StudioApp carries ZERO
  references to the new symbol; AiInferenceService.onCreate (running in :ai
  before any SCRFD_* line) plants AiChildLogTree with a double-plant guard.
  StudioApp is byte-equivalent to the proven r47 shape. IF a future round
  needs a new cross-package symbol from StudioApp, declare it in a file that
  already resolvable (e.g. AiProcMonitor.kt) and TEST FIRST.

### r48 device-verify reminder
Expected next dump: AI_PROC_STATE=running (XNNPACK was the crash — done) or
dead + AI_PROC_CHILD_LOG lines ending at SCRFD_MODEL_FILE/SCRFD_EP (abort
inside createSession with CPU-only) — and if size < 16,000,000, re-download.

## Increment 45 — Round 49: real SCRFD inference in :ai + ring/AIDL transport

Mandate: r48 proved the :ai child survives createSession (owner device dump:
ONNX_SESSION_OK ep=cpu). The remaining variable: REAL detection in the child —
frames main->:ai, boxes :ai->main. r49 delivers the transport + inference
pipeline + wiring; the r47 log-line contract is preserved byte-for-byte.

### New files
- app/src/main/kotlin/com/vcamstudio/app/ai/AiRing.kt — file-backed mmap slot
  ring; ONE file, two MAP_SHARED mappings (coherent via page cache; NOT
  SharedMemory [API 27+ vs minSdk 26], NOT MemoryFile FD [not SDK API]).
  Layout LE: [0..64) header "AIRN" v1 + slotCount/slotBytes/payloadBytes;
  [64..) slots = 64 B meta (state,seq,payloadSize,tag) + payload. publish()
  bumps seq THEN writes state LAST (handoff word). create() truncates to 0
  first. open() validates magic/version/geometry -> IllegalStateException
  (message built with the file length captured BEFORE raf.close() — the known
  r49 regression where length-after-close raised IOException("Stream
  Closed")). One lock per ring.
- app/src/main/aidl/com/vcamstudio/app/ai/IAiDetector.aidl — registerCallback
  / unregisterCallback (oneway), attachRings (sync, returns Boolean),
  setModel (oneway), submitFrame (oneway).
- app/src/main/aidl/com/vcamstudio/app/ai/IAiDetectorCallback.aidl — oneway
  onResult(frameId, boxSlot, hasFace, preprocessMs, inferMs) +
  onState(0 idle / 1 model-missing / 2 session-failed / 3 running).
- app/src/main/kotlin/com/vcamstudio/app/ai/AiDetectorClient.kt — main-side
  IPC: creates ai_frames.ring 2x1,382,400 B payload + ai_boxes.ring 8x64 B in
  filesDir (fresh per connect — a child restart never sees stale FULL slots);
  bindService(BIND_AUTO_CREATE); on connect registerCallback -> attachRings
  -> setModel(pending); 5 Hz submit gate; FREE slot -> payload+tag ->
  publish(FULL) -> submitFrame; none free -> drop + count (never a binder
  queue); onResult reads the box, releases the slot, RTT from a 4-entry
  pending table; linkToDeath -> AI_BINDER_DIED (r47 30 s /proc poll stays as
  backstop).
- app/src/main/kotlin/com/vcamstudio/app/ai/AiFrameAnalyzer.kt — CLASS
  implementing ImageAnalysis.Analyzer (never a SAM lambda — r39 hang). Only
  compacts YUV_420_888 -> packed I420 via ScrfdPreprocess.compactI420 and
  hands to AiProcMonitor.onFrame; closes the proxy on every path.
- app/src/main/kotlin/com/vcamstudio/app/ai/AiDetectorBinder.kt — :ai-side
  IAiDetector.Stub: session creation stays on "vcam-ai-probe"; ONE worker
  "vcam-ai-infer", queue capacity 1 keep-only-latest. Per frame: copy payload
  out -> release slot IMMEDIATELY -> ScrfdPreprocess.fill -> detectTop ->
  write box (box offsets HAS_FACE 0 | X1 4 | Y1 8 | X2 12 | Y2 16 | SCORE
  20f | TS 24L | FRAME_W 32 | FRAME_H 36 | PRE_MS 40 | INFER_MS 48 |
  FRAME_ID 56L) -> onResult; all box slots unread -> recycle round-robin
  (newest always lands). New child lines: AI_RING_OPEN, AI_INFER_SAMPLE
  (every 10th), AI_CHILD_STATE. r47 lines kept: AI_PROC_SESSION_OK model=,
  AI_PROC_SESSION_FAIL, report states started->ok|fail|stopped.
- engine-ai-face/src/main/kotlin/com/vcamstudio/engine/aiface/
  ScrfdPreprocess.kt — the ONE YUV->tensor implementation shared by the
  parked main path and the live :ai child: compactI420 (YUV_420_888 ->
  packed I420), fill (letterbox: scale=min(640/uprightW, 640/uprightH), grey
  127.5 pad -> 0.0 after (v-127.5)/128, rotation folded into upright->sensor
  sampling), toFaceBox (detection -> normalized upright FaceBox).
- Tests: app/src/test/.../AiRingTest.kt (8) + engine-ai-face/src/test/.../
  ScrfdPreprocessTest.kt (8). AiRingTest's centerpiece: TWO mappings of ONE
  temp file (payload survives, box fields round-trip, seq/tag travel, 720p
  I420 fits a slot, create() truncates stale content, open() rejects
  mismatch and undersized files with IllegalStateException).

### Changed files
- app/build.gradle.kts — `aidl = true` in buildFeatures (the ONE permitted
  build-config addition; without it AGP 8.5.2 emits no :app:compileDebugAidl
  -> "Unresolved reference 'IAiDetector'").
- AiInferenceService.kt — thin shell: onCreate keeps the AiChildLogTree
  plant (guard + reset + AI_PROC_START pid=) BYTE-FOR-BYTE; onBind returns
  the binder (reused across reconnects; a pre-bind model_path extra from the
  r47 startService path is stashed and handed over at onBind);
  onStartCommand forwards model_path -> binder.setModel; onDestroy ->
  binder.shutdown() + report stopped. AiChildLogTree object itself is
  byte-identical (verified by diff).
- AiProcMonitor.kt — additions only: bind/unbind/isBound/setModelPath/
  analyzer()/onFrame, bound: StateFlow<Boolean>, remoteResults: SharedFlow
  <RemoteResult>, note{Bound,FrameSubmitted,FrameDropped,Result,ChildDeath,
  ChildRunning}, and the dump block AI_IPC_BIND / AI_FRAMES_SUBMITTED /
  AI_FRAMES_DROPPED / AI_RESULTS_RECEIVED / AI_IPC_RTT_MS / AI_PRE_MS /
  AI_INFER_MS / AI_RING=frames=2x1382400 boxes=8x64. r47 probe/watch/report
  machinery untouched.
- FaceDetectionController.kt — setModel(non-null) now REFUSES with
  SCRFD_SETMODEL_REFUSED (main must never build OrtEnvironment; null still
  tears down + MODEL_MISSING); setNnapi stores the flag only (no main-process
  session build); runFrame delegates to ScrfdPreprocess (compactI420 -> fill
  -> detectTop -> toFaceBox; behavior unchanged); NEW reportRemoteResult
  (box, preMs, inferMs) feeds the EXISTING stats surface so SCRFD_MS/FPS/
  RUNS/FACE_BOX go real; box==null clears the box.
- StudioViewModel.kt — wiring only, ALL through existing AiProcMonitor:
  init -> DEV-toggle-on-at-boot => bind(context); scrfd READY => setModelPath
  (absolute) else null; syncDetection() attaches the analyzer iff DEV on at
  boot AND bound AND model READY AND camera layer on the active scene (else
  null); remoteResults -> reportRemoteResult; bound flips -> re-sync;
  stats.box==null -> clear _faceOverlay; onCleared -> unbind. STUDIOAPP.KT
  UNTOUCHED (r48 CI constraint; no new app.ai top-level symbol is referenced
  from any file outside app/ai — the VM only touches the r47-resolvable
  AiProcMonitor object's members).

### DO-NOTs honored
No CI-pipeline or dependency changes (ORT 1.17.1, CPU-only, addCPU(false),
no XNNPACK/NNAPI); no ORT construction in the main process (setModel refusal
+ log; native abort never caught); engine-render/capture/output/audio,
TRIANGLES path, swap behavior, rotation untouched; ONE inference worker,
queue capacity 1; AiChildLogTree stays inside AiInferenceService.kt;
AiFrameAnalyzer is a class; ScrfdDetector.kt init FROZEN (r48 final);
StudioApp.kt untouched.

### Expected device behavior (owner's test)
install -> Models: scrfd Ready -> DEV SCRFD ON -> force-close -> reopen ->
dump. Sanity: no AI_IPC_* lines in the dump = still running the pre-r49 APK.
Healthy: STATE=running, AI_IPC_BIND=bound, AI_FRAMES_SUBMITTED and
AI_RESULTS_RECEIVED climbing together, AI_IPC_RTT_MS real (tens of ms),
SCRFD_STATE=RUNNING, SCRFD_RUNS>0, SCRFD_MS ~ AI_PRE_MS+AI_INFER_MS,
FACE_BOX=[x1,y1,x2,y2] score=..., cyan overlay tracking the face. Triage:
dead -> post AI_PROC_CHILD_LOG tail; SUBMITTED=0 -> analyzer gate false
(check bound/READY/camera-layer); RESULTS=0 with submits>0 -> no session in
:ai (look for AI_PROC_SESSION_FAIL); AI_INFER_MS>~400 -> next variable is
640->320 input, NOT the transport.

### r49 CI ledger
- 36301385974 (f6b79ef) FAIL :app:compileDebugAidl (1m39s) — aidl (build-tools
  34.0.0) rejected app/src/main/aidl/com/vcamstudio/app/ai/IAiDetector.aidl;
  the ProcessException shows the tool ran and exited non-zero (its own
  diagnostic prints in the step log above the "What went wrong" block — the
  commit-comment greps do not capture aidl diagnostics). Diagnosis: the file
  referenced IAiDetectorCallback with NO import — the legacy aidl resolves
  cross-file interface types only via explicit imports, even same-package
  (cf. the classic SDK RemoteServiceController IRemoteService.aidl importing
  IRemoteServiceCallback from the same package). Fix f7bb9xx: add
  `import com.vcamstudio.app.ai.IAiDetectorCallback;` — zero-risk under
  either compiler generation (newer aidl auto-imports same-package; explicit
  import stays legal).
- 36302736795 (b15ba58) FAIL :app:compileDebugKotlin — the AIDL import fix
  WORKED (task got past compileDebugAidl; 113 tasks executed; engine modules
  + all cross-package references resolved, zero unresolved-reference errors).
  Three new-code errors, all in app/ai: (1) AiDetectorClient used
  android.util.Log with %-format varargs — Log has NO varargs overload (that
  is Timber's signature) -> converted all 8 call sites to Timber (tag
  dropped, Timber prepends its own); (2) AiProcMonitor.ipcBound was
  AtomicLong but used as an if-condition -> AtomicBoolean; (3)
  AiRing.readPayload was a block-bodied Int function whose synchronized
  expression was discarded -> added return. Fix next commit.
- 36303339970 (381907e) FAIL :engine-ai-face:testDebugUnitTest — ONE test,
  and it is a WRONG ASSERTION in my own ScrfdPreprocessTest (production code
  correct): `letterbox 480x640 upright is portrait` fed source w=480 h=640
  at rotation 0 and expected upright 640x480; rotation 0 means upright ==
  source dims (480x640 IS portrait). Everything else green: all app/ai +
  VM wiring COMPILED (test task ran = compilation passed), rot90/mid-grey/
  pad/rejects/AiRing-adjacent tests passed; :app tests had not run yet.
  Fix: assert uprightW=480, uprightH=640 + comment. First run where
  AiRingTest will actually execute.
- 36303795548 (7fb2200, HEAD) GREEN — both jobs; artifact
  vcam-studio-debug-apk id 10926438377 (21,267,865 B; +26,323 B over r48 =
  app/ai classes + AIDL stubs), probe-wedge id 10926875738. Test total now
  90 (74 prior + 8 AiRingTest + 8 ScrfdPreprocessTest) — the r49 mandate's
  "82" figure under-counted by assuming one new suite.

### r49 device-verify protocol (owner)
install the CI APK -> Models: scrfd Ready -> DEV SCRFD ON -> force-close ->
reopen -> Diagnostics dump. Sanity: NO AI_IPC_* lines = still the pre-r49
APK. Healthy: STATE=running, AI_IPC_BIND=bound, AI_FRAMES_SUBMITTED and
AI_RESULTS_RECEIVED climbing together, AI_IPC_RTT_MS real (tens of ms),
SCRFD_STATE=RUNNING, SCRFD_RUNS>0, SCRFD_MS ~ AI_PRE_MS+AI_INFER_MS,
FACE_BOX=[x1,y1,x2,y2] score=..., cyan overlay tracking the face. Child log
expectations (AI_PROC_CHILD_LOG): AI_RING_OPEN frames=2x1382464
boxes=8x128... (slot bytes = meta+payload), AI_INFER_SAMPLE every 10th
frame, AI_CHILD_STATE state=running. Triage: dead -> post
AI_PROC_CHILD_LOG tail; SUBMITTED=0 -> analyzer gate false (check
bound/READY/camera-layer); RESULTS=0 with submits>0 -> no session in :ai
(AI_PROC_SESSION_FAIL); AI_INFER_MS>~400 -> next variable is 640->320
input, NOT the transport.

## Increment 46 — Round 50, ITEM A: the ring contract fix (checkpoint-gated)

Owner r49 device dump = baseline: AI_IPC_BIND=bound, SUBMITTED=221,
RESULTS=70, RTT=868 (pre+infer 853 -> ~15 ms IPC inside ~870 ms), health
HEALTHY 58.8 fps dropped=0 recoveries=0, ONNX_SESSION_OK ep=cpu. No cyan
box: the child shipped RAW 640-letterbox Detection coords as the FaceBox
(dump FACE_BOX=[-42.857,805.997,177.422,1105.577] — values outside [0,1]),
so the overlay drew ~3 orders of magnitude off-canvas, and
reportRemoteResult never touched phase (SCRFD_RUNS=70 next to
SCRFD_STATE=OFF). Round 50 is THREE checkpointed items; this commit is
ITEM A ONLY (one variable at a time; B = threads, C = overlay polish come
after the owner's A-checkpoint dump).

### A1 — child converts (AiDetectorBinder.processFrame)
The letterbox from ScrfdPreprocess.fill is now kept (was a local lb that
went out of scope); the raw Detection is converted via
ScrfdPreprocess.toFaceBox(detection, letterbox, ts) BEFORE the ring write:
BOX_X1..Y2 hold an UPRIGHT, NORMALIZED FaceBox in [0,1] (toFaceBox clamps,
so a partly out-of-frame face hugs the edge instead of landing off-canvas);
BOX_TS = box.timestampMs; BOX_FRAME_W/H = the UPRIGHT frame dims
(letterbox.uprightW/H), NOT task.width/height. Ring contract stated once:
the child performs the one and only conversion; nothing downstream
rescales. FaceBox import added (r47-era symbol; mandate's "already
imported" was off by one — the r49 binder never named the type).

### A2 — SCRFD_STATE honest (badge)
- FaceDetectionController.reportRemoteResult now sets
  _phase.value = Phase.RUNNING first (results flowing = the child IS
  running), and a new reportRemotePhase(p) routes the other child states.
- Channel (all inside the proven pattern): AiDetectorClient.onState ->
  AiProcMonitor.noteChildState(state) -> childPhase: StateFlow<Int>
  (0 idle/1 model-missing/2 session-failed/3 running; state 3 also keeps
  the r47 noteChildRunning mirror) -> VM collector maps onto
  FaceDetectionController.Phase (OFF/MODEL_MISSING/SESSION_FAILED/RUNNING)
  -> reportRemotePhase. VM references stay FQN (file has no
  FaceDetectionController import — r48 anomaly discipline).

### Build gate (mandate 5)
ScrfdPreprocessTest + `toFaceBox maps content origin and far corner and
clamps overhang`: content origin (letterbox 0,80 @ padY 80) -> (0,0); far
content corner (640,560) -> (1,1); overhang (x1=-60, y2=700) clamps to
0.0 / 1.0. Test total 91 (one more than the r49 green run's 90).

### Checkpoint A (owner device protocol)
Same scene, same light, 30+ s, then dump + screenshot. Expected:
SCRFD_STATE=RUNNING; SCRFD_RUNS climbing; every FACE_BOX value inside
[0,1]; for the r49 detection specifically the mandate computes normalized
~[0.000, 0.630, 0.246, 0.864] (upright 720x1280; verified consistent with
the r49 raw values); a cyan rectangle visible in frame. If visible but
misplaced: STOP — geometry bug (layer quad / fill-crop mapping), report
OES_ORIENT + DRAW_STATE[OES], do not start B or C.

DO-NOTs honored: no CI/dependency changes; no ORT outside :ai; no
XNNPACK/NNAPI; no engine-render/capture/output/audio changes; ring
geometry (2x1382400 / 8x64), AIDL interface, and dump field names
untouched (no new dump fields in A); ScrfdDetector.kt init FROZEN;
StudioApp.kt untouched; no input-shape change (B2 measurement comes with
item B); no smoothing yet (item C).
- 36307201599 (5b6e4f3, HEAD) GREEN first-try — both jobs; artifact
  vcam-studio-debug-apk id 10927852313 (21,268,510 B). Awaiting the owner's
  checkpoint-A dump/screenshot before item B (threads) and C (overlay
  polish).

### Round 50 item A0 — make the child's death name its step

Owner checkpoint-A device evidence: the A-build shows AI_PROC_STATE=dead
with the child log stopping at AI_RING_OPEN — no SCRFD_MODEL_FILE, no
SCRFD_EP, no ONNX_SESSION_OK. probeLoop wraps construction in
catch (t: Throwable) and would have logged AI_PROC_SESSION_FAIL; it logged
nothing -> the child died WITHOUT a Throwable: native abort or external
kill. Why the window is invisible: in ScrfdDetector `env` is a property
initializer declared BEFORE the init block, and Kotlin runs property
initializers and init blocks in SOURCE ORDER — so
OrtEnvironment.getEnvironment() (the first ORT touch, which loads
libonnxruntime) ran before the first step log. (r48's init-block freeze is
superseded here by this explicit owner mandate.)

Three patches (this commit):
1. ScrfdDetector.kt — early init block BEFORE `env` (SCRFD_MODEL_FILE moved
   there + ORT_ENV_BEGIN); env creation bracketed:
   OrtEnvironment.getEnvironment().also { ORT_ENV_OK }. Everything after
   `val opts = SessionOptions()` unchanged byte-for-byte.
2. AiDetectorBinder.probeLoop — AI_PROBE_BEGIN path=%s immediately before
   the ScrfdDetector constructor call (pins "before construction" vs a
   specific constructor step).
3. AiInferenceService.onCreate — AI_CHILD_MEM max=%dMB free=%dMB right
   after AI_PROC_START (native abort vs OS/LMK kill discriminator when all
   step lines are present but the child is still dead).

Next-dump decision table: ORT_ENV_BEGIN without ORT_ENV_OK = abort inside
getEnvironment / lib load; SCRFD_EP without ONNX_SESSION_OK = abort inside
createSession (the r47 signature); all lines present but still dead =
external kill — check AI_CHILD_MEM. Checkpoint A is FAILED/blocked on the
dead child: items B (threads) and C (overlay polish) stay gated until a
build both survives AND shows the A contract (FACE_BOX inside [0,1] +
cyan box). A0 changes logging only — no behavior, no ORT calls added or
removed, no CI/dependency changes; expected green.
- 36308405455 (4fe357e, HEAD) GREEN — both jobs; artifact
  vcam-studio-debug-apk id 10928457587. Waiting on the owner's A0 dump:
  the child-log tail now names the death step (decision table above);
  checkpoint A (cyan box) remains gated on a surviving child.

### Round 50 item A0.1 — the A0 dump verdict + the last unbracketed window

OWNER A0 DUMP VERDICT: the child died between AI_RING_OPEN and
AI_PROBE_BEGIN — before SCRFD_MODEL_FILE (pure java.io.File + Timber),
before ORT_ENV_BEGIN, before the ScrfdDetector class load. That window
contains ONLY pure Java/POSIX work (binder delivery of setModel,
offerProbe, closeDetector no-op on first pass, one file append): Kotlin in
this window CANNOT native-abort. Corroboration: the r49 build survived the
identical code path on the same device (ONNX_SESSION_OK), and the A and A0
builds carry ZERO child-path behavior changes vs r49 before the
constructor. Conclusion: NOT a native abort — external kill (LMKD-class)
or a process-level event is the remaining cause class. AI_CHILD_MEM
max=256MB free=252MB at birth: the child's own heap was never the
constraint (LMKD is about system pressure, not the child's heap).

SECOND (independent) FINDING in the same dump: the MAIN process never
attached a preview surface (outputs= empty, preview=none), never created a
camera source (sources=0), and the active scene had ZERO layers
(SCENE_APPLIED layers=0; the r49 run showed layers=1). presented=0,
rendered=1. So even a LIVING child would have shown
AI_FRAMES_SUBMITTED=0 — no camera, no analyzer. Either the dump was taken
before the camera layer was added, or the scene reopened empty (possible
scene-restore issue after force-close/reinstall) — needs owner
confirmation before anyone touches anything.

A0.1 patches (diagnostics-only, no behavior change):
1. AiChildLogTree — every line now carries its age since reset:
   `[+832ms] W/vcam: AI_...` (epochMs captured at reset). The child's
   LIFETIME becomes visible: an instant stop after AI_RING_OPEN reads very
   differently from a death 3 s into createSession. Line CONTENT after
   the prefix is unchanged.
2. AiDetectorBinder.setModel — AI_SETMODEL_RECV path=... (binder thread;
   proves delivery).
3. AiDetectorBinder.probeLoop — AI_PROBE_WAKE path=... (probe thread woke
   with a non-null task).
4. AiDetectorClient deathRecipient — AI_PROC_DETAIL no longer claims
   "native abort": linkToDeath fires for ANY death and cannot name a
   cause (the A0 dump proved the old label wrong). New text: "binder died
   (cause unnamed — read AI_PROC_CHILD_LOG)".

Updated next-dump table (child log tail -> verdict):
- AI_RING_OPEN last, lifetime <~100 ms, no SETMODEL_RECV -> killed between
  transactions: external kill confirmed.
- SETMODEL_RECV present, no PROBE_WAKE -> killed before the probe woke
  (still pure Java -> kill).
- PROBE_WAKE present, no PROBE_BEGIN -> killed in the wake->begin window
  (microseconds -> kill).
- PROBE_BEGIN present, no SCRFD_MODEL_FILE -> died in CLASS LOAD of
  ScrfdDetector (this is where ORT classes + libonnxruntime.so load — the
  only remaining spot a real lib-load abort can live).
- ORT_ENV_BEGIN without ORT_ENV_OK -> abort inside
  OrtEnvironment.getEnvironment / JNI init.
- SCRFD_EP without ONNX_SESSION_OK -> abort inside createSession (r47
  signature).
- All lines present incl. ONNX_SESSION_OK but STATE=dead -> died AFTER a
  good session: look at the age deltas and AI_CHILD_MEM.
- 36319668288 (9104f94, HEAD) GREEN — both jobs; artifact
  vcam-studio-debug-apk id 10932251825 (21,269,555 B). Awaiting the owner's re-test dump:
  per-line ages + SETMODEL_RECV/PROBE_WAKE decide kill-vs-class-load; the
  scene/camera-layer question (layers=0 this run) needs an answer too.

### Round 50 item A0.2 — the A0.1 dump verdict + kill-context instrumentation

OWNER A0.1 DUMP VERDICT (deepest bracket yet): the child died at +38 ms of
child life, last line AI_SETMODEL_RECV, BEFORE AI_PROBE_WAKE — a window of
microseconds of pure Java (notifyAll + one file append). Second
consecutive death before ORT is touched, at a CONSISTENT child age
(+28 ms -> dead in A0; +38 ms -> dead in A0.1; dumps ~16.5 min apart), on
a code path the r49 build SURVIVED (ONNX_SESSION_OK, 221 frames, 70
results on this same device). Conclusion stands and strengthens: external
kill, NOT native abort, NOT our child code. The regular ~30-40 ms age
suggests something reaps the child deterministically shortly after the
bind handshake — OEM/background-process killers (Tecno/Infinix HiOS/XOS
class) are the prime suspects; LMKD needs the memory numbers to confirm
or exclude. Also RESOLVED from the same dump: the previous empty-scene
concern was protocol, not a restore bug (this run: layers=1, camera
source created, preview 1028x1675, 62.5 fps, HEALTHY, presented=881).

A0.2 patches (main-process diagnostics only, no behavior change):
1. AiProcMonitor.noteBindContext (from onServiceConnected): AI_SYS_BIND_
   AVAIL_MB, AI_SYS_THRESHOLD_MB, AI_CHILD_IMPORTANCE (RunningAppProcess
   info of the :ai process — same-uid listing; ~300 = service-class while
   bound; 100 fg / 200 vis / 300 svc / 400 cached / 1000 gone).
2. AiProcMonitor.noteDeathContext (from the death recipient, BEFORE the
   death event): AI_SYS_DEATH_AVAIL_MB, AI_SYS_DEATH_LOW +
   AI_DEATH_SYSMEM log line.
3. onServiceDisconnected now logs AI_SERVICE_DISCONNECTED (was silent).
New dump fields only — names untouched.

Next-dump decision table (system side):
- AI_SYS_DEATH_AVAIL_MB < AI_SYS_THRESHOLD_MB (or DEATH_LOW=true) -> LMKD.
- Plentiful RAM + AI_CHILD_IMPORTANCE ~300 + still dead -> OEM/policy
  killer. Remedy directions (owner's call, NOT this round): foreground
  service in :ai (persistent notification), battery-optimization
  exemption, or a cooldown-guarded rebind loop.
- DEFINITIVE (if owner has adb): `adb logcat -b events -d | grep -iE
  "lmkd|lowmemory|kill|am_proc_died"` right after a repro names the
  killer directly.
Checkpoint A remains gated on a SURVIVING child; B (threads) and C
(overlay polish) remain gated behind A per mandate 6.
- 36320634775 (ff15b44, HEAD) GREEN — both jobs; artifact
  vcam-studio-debug-apk id 10932228419 (21270670 B). Next: owner re-test; the dump's
  AI_SYS_* + AI_CHILD_IMPORTANCE decide LMKD vs OEM-policy kill.

### Round 50 item A0.3 — the A0.2 dump verdict (LMKD EXCLUDED) + exact time-of-death

OWNER A0.2 DUMP VERDICT: LMKD is EXCLUDED by the numbers —
AI_SYS_BIND_AVAIL_MB=3567, AI_SYS_DEATH_AVAIL_MB=3622, threshold=564,
DEATH_LOW=false. A foreground child with 3.6 GB free is not an LMKD
victim. Third consecutive kill in the same window (this run: last line
[+20ms] AI_SETMODEL_RECV, no AI_PROBE_WAKE; window = microseconds of pure
Java). AI_CHILD_IMPORTANCE=100 — FOREGROUND class (stronger than the
predicted ~300 service class, because the activity binds with
BIND_AUTO_CREATE) — and it STILL died. Per the A0.2 decision table this
is the OEM/policy-killer branch: something kills a foreground-importance
sub-process ~20-40 ms into the bind handshake, deterministically, across
three builds with zero child-path behavior change. Stock Android has no
mechanism that does this. Prime suspect remains an aggressive OEM battery
manager (HiOS/XOS class).

Watchdog (carry-forward data point, NOT acted on): recoveries 0 -> 1 this
run; stall 6810ms thread=vcam-render; BUT the stack is
MessageQueue.nativePollOnce (IDLE wait) and the recovery lands ~0.5 s
after FIRST_PRESENT during startup — the stall window started BEFORE
SURFACE_ATTACHED. Different signature from the earlier joinToString
occurrence (busy string-building mid-run). Startup-gap hypothesis: the
watchdog clock starts before the first surface exists. failedRecovers=0,
58.8 fps after. REMAINS GATED per the carry-forward rule (climb gate met
once: 0 -> 1; no round spent).

A0.3 patches (diagnostics-only):
1. AiDetectorBinder: 1 Hz heartbeat thread ("vcam-ai-hb", daemon) —
   AI_HEARTBEAT up=%dms tick=%d every second into the durable child log.
   Three dumps ended at the last LOG line; "last log" is not "time of
   death" (a failed/blocked write would look identical). The next dump's
   last heartbeat pins the kill to the second: death AT +20ms (synchronous
   with the handshake) vs +Ns (something periodic) are different killers.
2. AiChildLogTree MAX_BYTES 32 KB -> 256 KB: at 32 KB the cap silently
   drops NEW lines once exceeded — 1 Hz heartbeats would freeze the file
   after ~15 min and HIDE later step lines in a long healthy session.
   256 KB = ~2 h heartbeat history; file is app-private, reset each
   onCreate.
3. AiProcMonitor probe-watch: the /proc-poll death text no longer claims
   "native abort" (this dump's 3.6 GB avail PROVED that wording wrong);
   both death paths (linkToDeath + /proc poll) now report "cause unnamed —
   read AI_PROC_CHILD_LOG".

OWNER DECISION MENU for the remedy (owner's call; NOT implemented — each
is its own round):
a) Foreground service in :ai (persistent notification) — strongest
   protection; changes UX.
b) Battery-optimization exemption — zero code, may not be respected by
   aggressive OEM managers.
c) Cooldown-guarded rebind loop in AiDetectorClient (e.g. retry every 5 s,
   backoff, give the toggle a "recovered N times" line in the dump) —
   tolerates the killer instead of stopping it.
d) ZERO-CODE, DEFINITIVE first: adb logcat -b events -d | grep -iE
   "lmkd|lowmemory|kill|am_proc_died" right after a repro names the killer
   component directly; plus Settings -> battery / app-freeze checks for
   VCam Studio.
- 36321992512 (73bd766, HEAD) GREEN — both jobs; artifact
  vcam-studio-debug-apk id 10933265023 (21271217 B). Next: owner re-test — the last
  AI_HEARTBEAT [+Nms] pins time-of-death; adb events logcat (menu item d)
  remains the definitive killer-name if available.

### Round 50 item A0.4 — the A0.3 dump: TRANSPORT PROVEN + probe-thread silence isolated

OWNER A0.3 DUMP = the biggest payout so far. THE TRANSPORT WORKS END-TO-END
on device: AI_FRAMES_SUBMITTED=7, AI_RESULTS_RECEIVED=7, AI_IPC_RTT_MS=2,
AI_FRAMES_DROPPED=0, SCRFD_RUNS=7, SCRFD_FPS=5.0 (the 5 Hz gate exact),
SCRFD_STATE=RUNNING (A2 badge honest), FACE_BOX=none, studio HEALTHY
58.8 fps dropped=0 recoveries=0. Heartbeats: ticks at +20ms / +1023ms /
+2026ms — the child lived 2+ seconds and DIED IN THE +2..3 s WINDOW (the
kill moved from the 20-40 ms handshake window to seconds in: a PERIODIC
sweep profile, consistent with an OEM battery manager scanning subprocesses,
not a handshake-synchronized killer). LMKD triply excluded:
BIND=4500MB, DEATH=4506MB, threshold=564, LOW=false, importance=100.

NEW IN-CHILD ANOMALY the heartbeats exposed: AI_SETMODEL_RECV at +41ms,
child alive and logging for 2+ s after — and AI_PROBE_WAKE NEVER appeared.
The probe thread never woke from offerProbe's notify during the child's
whole life. Code reviewed on HEAD: offerProbe/probeLoop are correct (same
monitor, flag set before notify, wait loop rechecks) — no visible defect.
Consequence: no session was ever built (no SCRFD_* lines); the 7 results
are PRE-SESSION null-face frames (PRE=0/INFER=0) — correct protocol
behavior for "no session yet", and exactly why even a surviving child
would have shown no face.

A0.4 patches (diagnostics + one badge-honesty fix):
1. probeLoop/workerLoop: AI_PROBE_THREAD_UP / AI_WORKER_THREAD_UP markers +
   whole-body try/catch(Throwable) -> AI_PROBE_THREAD_DIED /
   AI_WORKER_THREAD_DIED with stack. An uncaught exception on these
   threads goes to the DEFAULT handler (logcat only) and is INVISIBLE to
   the durable log — a dead probe thread reads exactly like a missed
   notify, which is the A0.3 signature. Next dump decides: thread died
   (stack in dump) vs notify genuinely lost (UP present, OFFERED present,
   no WAKE = scheduling-freeze class).
2. offerProbe: AI_PROBE_OFFERED inside the monitor (SETMODEL_RECV only
   proved the binder thread entered setModel).
3. Badge honesty on death: noteChildDeath drops childPhase to 0 —
   SCRFD_STATE no longer reads RUNNING after a dead child.

DECISION STILL WITH OWNER (unchanged menu, sharper evidence): (a) :ai
foreground service; (b) battery-optimization exemption; (c) cooldown-
guarded rebind loop; (d) ZERO-CODE definitive killer-name:
adb logcat -b events -d | grep -iE "lmkd|lowmemory|kill|am_proc_died".
The +2..3 s periodic profile makes (a) or (c) the likely code remedies;
(b)+(d) cost nothing to try first. Checkpoint A (cyan box) is one
surviving child away — the transport itself is proven.

### Ledger

| Round | Commit | CI run | Result | Artifact (vcam-studio-debug-apk) |
|---|---|---|---|---|
| r50 A0.4 | 632f59e | 36326975052 | GREEN 7m11s | id 10934163397 (21,271,516 B) |

### Round 51 — dump-5: CHILD SURVIVED + FIRST DETECTION (checkpoint A PASS) → B+C implemented (owner: "Implement all too")

DUMP-5 (A0.4 build, artifact 10934163397) = THE MILESTONE. The child process
SURVIVED the whole session: heartbeats tick=53..73 (+52 s..+72 s of child
life, 1 Hz exact), AI_PROC_STATE=running PID=21996, no death line. The
A0.4 build's only changes were diagnostics — so the +2-3 s killer is
NONDETERMINISTIC (an OEM sweep that fires some sessions, not others). The
remedy menu (a FGS / b battery exemption / c rebind / d adb events logcat)
STANDS; the child died in dumps 1-4 and lived in dump-5 with identical code
paths for survival.

FIRST END-TO-END DETECTION: FACE_BOX=[0.000,1.000,0.178,1.000] score=0.639
— normalized upright [0,1] with A1 clamps visibly working (x1 clamped to
0.000, y1/y2 at the bottom edge: a face exiting the frame bottom-left).
SCRFD_RUNS=81, SCRFD_MS=844.60, PRE=113 INFER=726 (3 AI_INFER_SAMPLEs all
face=true), SCRFD_STATE=RUNNING, studio HEALTHY 58.8 fps dropped=3
recoveries=0, golden orientation unchanged (ST_CLASS rot=90 mirror=none,
NET=ANTI_DIAG_MIRROR, bisect=5). Accounting note: SUBMITTED=305 vs
RUNS=81 with DROPPED=0 — the 224 gap is keep-only-latest coalescing,
which B3's new counter now measures directly. RTT=-1 (no fresh round-trip
sample in window; results provably flowed — 81 received).

R51 SCOPE (owner instruction "Implement all too" — gate lifted: survives +
FACE_BOX subset [0,1]):
- B1: ScrfdDetector INTRA_OP_THREADS 2->4 (INTER_OP stays 1) + SCRFD_THREADS
  log. Baseline to beat: INFER 726 / SCRFD_MS 844.6. Checkpoint: no win,
  child death, or fps drop -> revert to 2.
- B2 (measure-only): ONNX_INPUT_SHAPE line via session.inputInfo (symbolic
  vs fixed 640 decides any future 320 round).
- B3: framesCoalesced counter + ` coalesced=%d` on AI_INFER_SAMPLE +
  AI_PRE_AVG_MS / AI_INFER_AVG_MS (mean of last 20 frames).
- C1: EMA in StudioViewModel stats.collect BEFORE the front mirror — 0.45
  fresh + 0.55 prev per coord, upright space, NO smoothing on null->box
  (state resets); pure math extracted to FaceOverlayMath (unit-tested).
- C2: overlay clears 1500 ms after the last box even if results stop
  arriving (expiry job, main dispatcher).
- C3: overlay styling density-correct — stroke 3.dp, radius 8.dp, label
  14.dp (was raw px 5/10/36); NN% label already existed, kept.
- C4: FACE_OVERLAY=age=<ms> mirrored=<bool> upright=<w>x<h> box=[…] score
  line in diagnosticsDump SCRFD_SECTION.
6 new unit tests (EMA appear/smooth/fields, mirror once, double-mirror
identity WITHIN TOLERANCE — 1f-(1f-x) is not bit-exact x, convex hull).

### Ledger

| Round | Commit | CI run | Result | Artifact (vcam-studio-debug-apk) |
|---|---|---|---|---|
| r51 B+C | 602e366 | 36348809303 | GREEN 7m4s | id 10940949013 (21,272,865 B) |

### Round 51.1 — dump-6: B1 checkpoint FIRED (4 threads lost, reverted) + THE BOTTOM-PINNED-BOX BUG FOUND & FIXED (owner-directed)

DUMP-6 (r51 build): child SURVIVED again (heartbeats tick 107-127, 127+ s;
two consecutive survives -> the killer is nondeterministic, remedy menu
stands). B3/C4 verified live on device: coalesced=337 (reconciles
SUBMITTED=476 vs RUNS=115), FACE_OVERLAY=age=1002ms mirrored=true
upright=480x640 box=[0.006,0.986,0.147,1.000] score=0.838. C1 EMA + C2
expiry + C3 dp styling in effect (mirrored box consistent with front cam).

B1 CHECKPOINT FIRED -> REVERTED: 4 threads averaged AI_INFER 1093ms /
AI_PRE 173ms vs 738 / 115 at 2 (SCRFD_MS 1205.7 vs 844.6); renderer
dropped=39 p95=34 (vs 3 / 22 in dump-5) — contention visible. INTRA_OP
back to 2.

THE REAL BUG (owner diagnosis, confirmed in code): the face box was pinned
to the frame bottom (score tracked the face, geometry did not).
ScrfdPostprocess.decode assumed the export layout [stride 8 x2a, 16 x1a,
32 x1a] BY OUTPUT POSITION; scrfd_10g_bnkps actually carries TWO anchors
at EVERY stride (12800/3200/800 entries — the existing groupOutputs test
already modeled this!). The stride-16 tensor (3200 entries) was decoded
on a 40-grid with A=1: gy ran to 79, cy=(79.5)*16=1272 > 640 — boxes past
the frame bottom, r49's raw y1=806/y2=1106 explained. FIX: (stride,
anchors) now derived from each pair's ENTRY COUNT via resolveLayout
(8/16/32 smallest-first, anchors 1..8, null -> pair SKIPPED not guessed);
works for both the real 2-anchor layout and the old 1-anchor variant.

Owner patches 1-5 all in:
1. ONNX_OUTPUT_SHAPES log (proves the device export's layout).
2. Test `decode_centres_the_box_on_the_anchor_that_scored` (owner's exact
   values) + stride-16 regression pin + resolveLayout unit tests; the
   old `anchor counts` test (1600/400) asserted the WRONG constants and
   was rewritten to the corrected 12800/3200/800 fact set.
3. AI_INFER_SAMPLE now carries frame/rot/upright/pad/scale + RAW pre-clamp
   det box + score.
4. B1 reverted to INTRA_OP=2 (see above).
5. Child-log tail: read 40, keep last 2 heartbeats only, then last 24 —
   boot-time SCRFD_/ONNX_ lines survive past ~25 s of child life (they
   were the lines we needed most and they were the ones being flooded out).

### Ledger

| Round | Commit | CI run | Result | Artifact (vcam-studio-debug-apk) |
|---|---|---|---|---|
| r51.1 decode fix | 185abb5 | 36362602759 | GREEN 5m49s | id 10945914713 (21,276,092 B) |

### Round 52a — swap geometry foundation (owner mandate: 0 MB downloads; all math/plumbing/harnesses BEFORE 452 MB)

ALL FIVE ITEMS IN:
1. KEYPOINT DECODE: SCRFD kps tensors (last dim 10 = 5 landmarks x 2) are
   decoded with the SAME resolveLayout entry-count rule as boxes (r51.1
   lesson applied); kx = cx + v[2i]*stride, ky = cy + v[2i+1]*stride; order
   Leye, Reye, nose, Lmouth, Rmouth. Detection gains ADDITIVE landmarks
   field (zeros when no kps tensor; box/score semantics untouched).
   Landmarks ride an ADDITIVE binder callback onKps(frameId, kps,
   uprightW, uprightH) — the boxes ring stays 8x64 (owner constraint).
   Main normalizes to upright [0,1] and SCRFD_SECTION gains the additive
   FACE_KPS=[...] line (FACE_KPS follows FACE_BOX: cleared on no-face runs).
2. FaceAlign (engine-ai-face, pure, no Android): skimage
   SimilarityTransform semantics via complex least squares (scale+rotation+
   translation; reflection unrepresentable by construction). Templates
   VERIFIED: arcface_dst; 112 -> raw template; 128 -> x+8.0 (rule:
   size%112==0 -> ratio=size/112 diff 0 else ratio=size/128 diff=8*ratio).
   Returns 2x3 M + inverse (paste-back). Unit tests: identity warp 112+128
   within 0.5 px, synthetic similarity (1.8x, 17deg, +31,-12) recovered,
   inverse round-trip, template rules, degenerate -> null.
3. CROP DUMP (child-side debug): IAiDetector.dumpDebugCrops() -> worker
   writes 112/128 aligned crops of the next detected face to
   DCIM/VCamStudio/debug_crops/ via MediaStore (CrashLogger pattern),
   MODEL_CROP_DUMP=ok|fail in the child log. This settles diff_x BY EYE.
4. STUB MODELS (6,943 B total, app/src/debug/assets/stubs/ — bytes never in
   release): stub_w600k_r50 (input.1 [None,3,112,112] -> 683 [1,512]),
   stub_inswapper_128 (target+source -> output = target*0.5+mean(source),
   dual-dependency), probe_fp16_io (fp16 e2e), probe_fp16_inner (fp32 I/O +
   fp16 weights = inswapper pattern). Generated by scripts/make_stubs.py;
   generation-time validation with desktop ORT: contracts exact, inswapper
   stub output differs when only source changes. DEBUG actions in
   ModelsSheet: "Install stub models" (assets -> filesDir/models under REAL
   names + scanAdoptions) and "Run model probes" — probes run IN THE :ai
   CHILD (isolation law: no ORT in main) via additive IAiDetector
   .runModelProbes(); results log MODEL_PROBE_<name>=ok|fail:<msg> into
   AI_PROC_CHILD_LOG. JVM tests assert the committed bytes' contracts via a
   minimal proto walker (names/shapes/dtypes + static dual-dependency of
   the inswapper graph); behavior itself is proven on device.
5. MODEL SAFETY: (a) MODEL_ADOPT_SIZE_MISMATCH — an adopted file whose size
   differs from the catalogue is no longer silently READY (stops a stub or
   truncation masquerading; the w600k/inswapper stubs will trip this by
   design); (b) metered-network guard — download() refuses on
   isActiveNetworkMetered until the ModelsSheet confirm dialog (proceed/
   dismiss); (c) SAF "Back up models"/"Restore models" with
   takePersistableUriPermission (the owner has NO PC — the only way 452 MB
   survives an uninstall). Import re-checks size and re-scans adoptions.

NEW LOG LINES (all additive): MODEL_PROBE_*, MODEL_CROP_DUMP_*,
MODEL_ADOPT_SIZE_MISMATCH, MODEL_DOWNLOAD_METERED, MODEL_EXPORT_*/IMPORT_*,
AI_CROP_DUMP_REQUESTED. AIDL: 3 ADDITIVE methods (onKps callback;
runModelProbes/dumpDebugCrops detector) — legacy-aidl import rule already
satisfied; probe-wedge does not implement these interfaces (checked).

52B/52C QUEUED per the one-variable law: 52b (XNNPACK, freeze lifted for
session options only) and 52c (:ai foreground service, BIND_IMPORTANT,
AI_PROC_FG/IDLE_MS fields) each proceed after the previous round's device
checkpoint.

### Ledger

| Round | Commit | CI run | Result | Artifact (vcam-studio-debug-apk) |
|---|---|---|---|---|
| r52a | 8be4048 | 36376230376 | GREEN 5m53s | id 10950659797 (21,315,752 B) |

r52a CI debugging record (4 red runs, all named): (1) 35ceee3 — legacy aidl
rejected the new lines; (2) 2ec044a — ASCII-only comments did NOT fix it;
(3) 756b156 — explicit `in` direction tag on the float[] param FIXED the
aidl stage (CI-proven: classic aidl demands direction tags on array
params); then compileDebugKotlin caught ModelProbes guessing APIs —
(4) d03f977 — verified against ORT v1.17.1 sources: OnnxJavaType.FLOAT16
exists, fp16 tensors go through the PUBLIC
createTensor(env, ByteBuffer, long[], OnnxJavaType) overload, accessor is
t.info (as detectTop already uses); (5) 8be4048 GREEN — test index bugs:
kps tensor is n*10 floats (n anchors x 5 points x 2), matcher+tests
aligned; FaceAlignTest point loops 0 until 5 (were 0 until 10 with 2*i);
recovered-rotation assert uses -m.m[3] for the sin sign (theta = -17deg).
Test total now 112 (91 through r50 + 6 r51 + 8 FaceAlign + 3 net
ScrfdPostprocess + 4 StubContracts). AIDL law extended: array params need
explicit direction tags in classic aidl; comments must stay ASCII.

### Round 53 — TRANSPORT v0 (owner mandate: root AND no-root, universal injection, capability-driven, ZERO device-specific code)

NEW MODULE engine-transport (depends on core-common ONLY): TransportSink
contract, TransportRing (ONE SharedMemory parcelable carrying frame+meta —
the AI-ring fd-over-binder pattern with a SEQLOCK instead of slot states,
because the consumer is a hook in another app's process that cannot run our
release protocol; header 96 B: magic/version/format/w/h/rotation/tsNs/
frameBytes/seq/payloadBytes; EVEN seq = stable, ODD = writing, readers
validate before+after copy), HeaderCodec (pure, JVM-tested),
TransportCapabilities (pure Probes->route matrix, JVM-tested), caps line
format byte-for-byte per mandate.

ROUTE R (root hook): the VCam APK IS the Xposed module — compileOnly
de.robv.android.xposed:api:82 (the ONE mandated dependency; canonical host
api.xposed.info added to settings, verified reachable; NOT a CI change),
assets/xposed_init, xposedmodule/description/minversion meta-data.
VcamHook is SELF-CONTAINED (no Hilt/DI/VCam imports, android.util.Log):
Camera1 setPreviewTexture keeps the app's target and hands the real camera
a dummy SurfaceTexture (sensor keeps running), rendering ring frames into
the app's target via EGL14+GLES20 built in-process (pure framework APIs,
no NDK; 3-plane LUMINANCE I420 upload + BT.601 shader, TRIANGLES per house
style); setPreviewCallback swaps in a wrapper callback that fills the
app's byte[] (NV21) from the ring; startPreview/stopPreview/release
ordering handled; every hook body try/caught so a failure can NEVER crash
the host app. Frames reach the hook as a SharedMemory parcelable via
content://com.vcamstudio.app.transport (TransportProvider, exported,
serves exactly one thing).

ROUTE V (no root): VirtualDeviceTransport — reflection-only probing and
invocation (probing IS the capability detection): api>=34 + VDM service +
CDM association + CAMERA_INJECT_EXTERNAL_CAMERA + injectCamera API presence
each checked and reported; best-effort launch-through (association ->
VirtualDevice POLICY_TYPE_CAMERA -> virtual display -> setLaunchDisplayId).
HONEST SCOPE: app streaming — the target must be launched BY US.

CAPS/dump: TRANSPORT_CAPS line per mandate + TRANSPORT_SECTION
(FEED/FRAMES/TARGET/RING) additive only. Debug UI in ModelsSheet: Detect
caps, Target picker (installed apps holding CAMERA at RUNTIME, excludes
self, no hardcoded list), Start/Stop feed, Launch through VD. Feed source:
the EXISTING analyzer stream via a one-line TransportTap dispatch in
AiFrameAnalyzer (copy inside dispatch; AI ring untouched). No device
strings, no model strings, no hardcoded package list or resolutions
anywhere; if no route is available the UI/caps say so with the reason.

JVM tests (+8): route matrix (none/root-preferred/all-3-gates/debug
override), su classification, selinux parse, caps-line format, header
roundtrip, seqlock even/odd stability.

### Ledger

| Round | Commit | CI run | Result | Artifact (vcam-studio-debug-apk) |
|---|---|---|---|---|
| r53 transport v0 | 21d0a35 | 36394252085 | GREEN 7m10s | id 10958026516 (21,367,951 B) |

r53 CI debugging record (3 red runs, all named): (1) f9ce66f — execSu
Boolean==Int, AssociationInfo.id not on the public compile surface
(reflection now), ModelsSheet LocalContext declared after first use;
(2) 9cbff14 — AndroidAppHelper is NOT in the compileOnly api:82 jar
(ActivityThread.currentApplication reflection idiom now), and Renderer as
a NESTED class cannot see outer fields (reader/quad passed explicitly);
(3) 21d0a35 GREEN — JVM test bugs: createTempFile prefix >=3 chars, route
test used api=34 so the api<34 gate never fired (test now api=33).
New CI-proven facts: classic aidl array params need `in` (r52a);
AndroidAppHelper absent from xposed api:82 compile jar; AssociationInfo
public surface is unreliable at compileSdk 34 (reflection).

### Round 53.1 — testability fixes (owner: "implement what needs to be implemented so I can test at once")

(1) RING CAPACITY FIX: DEFAULT_PAYLOAD_BYTES 1,327,104 -> 1920*1080*3/2
(3,110,400) — the old guard silently REJECTED 720x1280 analysis frames
(1,382,400 B), so "Start feed" would have published nothing.
(2) FEED NO LONGER NEEDS THE AI TOGGLE: syncDetection attaches a
transport-only analyzer (no-op AI sink -> AI counters stay clean) when the
feed is on but the AI chain is off; transportDev collector re-runs the gate.
(3) ONE-TAP SELF-TEST: TransportManager.selfTest exercises the EXACT hook
path in-process (ContentResolver.call -> SharedMemory parcelable ->
read-only map -> seqlock header validation) — works on ANY device, no
root/Xposed; result in a toast AND TRANSPORT_SELFTEST=ok|fail:<detail> in
TRANSPORT_SECTION. "Self-test" button next to "Detect".

### Ledger

| Round | Commit | CI run | Result | Artifact (vcam-studio-debug-apk) |
|---|---|---|---|---|
| r53.1 testability | 5603ec5 | 36395529261 | GREEN 7m47s | id 10958615186 (21,370,532 B) |

### Round 54 — ONE consolidated push: A (crash-on-open), B (:ai survival), C (XNNPACK), D (fp16 Conv probe), E (crop button), F (runtime toggles)

A. CRASH-ON-OPEN HARDENING (TransportProvider/TransportManager/CrashLogger):
A1 onCreate is total — no context!!, runCatching attach, returns false on
failure (app opens with transport unavailable). A2 attach is capture-only;
probing/ring/exec moved OFF the startup path — capsLine() (dump path) now
returns the CACHED result or "not-probed-yet" and NEVER lazily probes; VM
Detect runs caps refresh on dispatchers.io. A3 verified unchanged: VDM
probe still SDK_INT>=34 && runCatching; VirtualDeviceTransport still
Class.forName. A4 CrashLogger gained ensureInstalled (idempotent) and is
installed FIRST in provider.onCreate — handler + full stack + RingLog
(400 lines >= the 200 mandate) land in DCIM/VCamStudio/crashes even for
provider-phase deaths; TRANSPORT_BOOT=provider_attached|provider_failed|
deferred logged from onCreate. A5 exported-without-permission DECISION
RECORDED in the provider KDoc (hook consumers share no signature; surface
is one read-only SharedMemory; grantUriPermissions stays false).

B. :ai SURVIVAL: B1 AiInferenceService starts FOREGROUND at child onCreate
(channel + Notification.Builder (framework, minSdk 26), API>=34 uses
startForeground(id, n, FOREGROUND_SERVICE_TYPE_SPECIAL_USE), manifest
gains the specialUse type + PROPERTY_SPECIAL_USE_FGS_SUBTYPE +
FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE permissions). B2
client binds BIND_IMPORTANT|BIND_AUTO_CREATE (gated by the ai_fg toggle at
bind time). B3 additive dump fields AI_PROC_FG=<child report fg>,
AI_PROC_IMPORTANCE=<childImportance>, AI_PROC_IDLE_MS=<ms since last
submit>; writeReport carries fg=. B4 every FGS call wrapped; failure logs
AI_PROC_FG_FAIL and degrades to the previous behaviour.

C. XNNPACK (owner mandate, freeze lifted for session options ONLY):
ScrfdDetector gains useXnnpack param; addXnnpack(emptyMap()) called BEFORE
addCPU(false) (EP preference = call order; CPU stays fallback; arena
already off; intra stays 2, inter 1). API verified in ORT v1.17.1 sources
(public addXnnpack(Map<String,String>)). SCRFD_XNNPACK=on|off logged.
Toggle read at session BUILD (DebugFlags file, child-readable); flipping
re-offers setModel (session rebuild, no app rebuild). KEEP/REVERT rule:
>=20% INFER improvement with no fps/dropped/p95/liveness regression, else
exact revert + negative result recorded (as r51 B1). C4: ONNX_INPUT_SHAPE
expected in the child log (r51.1 tail filter keeps boot lines).

D. FP16 PROBE REPLACED: probe_fp16_io/inner DELETED (Identity/Mul can't
fail like the real model). New probe_fp16_conv.onnx (514 B, debug assets):
fp32 [1,3,16,16] -> Cast(FLOAT16) -> Conv(weights FLOAT16 [4,3,3,3] +
bias [4]) -> Relu -> Cast(FLOAT) -> [1,4,14,14]. Generated via
scripts/make_stubs.py; desktop-ORT validation CAUGHT a real bug before CI
(weights not reshaped -> flat [108] Conv) — fixed, contract verified:
exact IO types/shapes, finite non-negative output. ModelProbes logs
MODEL_PROBE_fp16_conv=ok ops=Cast,Conv,Relu,Cast ran=true; JVM test pins
the op list + fp16 initializer dtype/dims + IO shapes.

E. CROP BUTTON PLACEMENT: "Dump align crops" moved NEXT TO "Run model
probes" (it existed but sat in the backup/restore row); path unchanged:
child writes 112/128 crops to DCIM/VCamStudio/debug_crops/, diff_x read by
eye (eyes level+centred = template right; ~8 px left = inverted).

F. DEBUG TOGGLES (mandatory): file-backed (filesDir/
transport_debug_flags.properties, child-readable) — transport_attach,
transport_probe, ai_fg, xnnpack, feed; ALL DEFAULT ON (absent file = all
on). UI: five on/off buttons under the Transport v0 block. Side effects:
xnnpack -> reofferSession (immediate); ai_fg -> next :ai start; attach ->
next app start (TRANSPORT_BOOT=deferred); probe -> Detect reports the
toggle; feed -> existing tap. CONFIG_EFFECTIVE=transport_attach=on,...
logged once at VM init AND included in TRANSPORT_SECTION.

**r54 ledger**: b98a01f (RED: attach block typed Unit — getOrDefault(false)
 poisoned the if-expression; @Volatile on a local var; local funs invoking
 TextButton) → e9646bf = HEAD, GREEN run 36401716854 (7m16s), artifact
 10960243343 (21,382,207 B). Items A–F reported to owner; device protocol
 steps 0–5 attached; C keep/revert + B idle-death verdicts PENDING owner
 device runs.

### Round 54.1 — MINIMAL CRASH-ONLY (owner: app OPENS, dies right AFTER camera/mic permission grant → NOT the provider path; prime suspect r53.1 transportDev initial-emission rebind; no Java stack ⇒ maybe native ⇒ breadcrumbs mandatory)

X1 syncDetection TOTAL+IDEMPOTENT (StudioViewModel): early-return unless
CAMERA granted (ContextCompat); @Volatile lastAnalyzerMode ("ai"|
"transport"|"none") — unchanged mode = no-op; whole body runCatching,
failure logs SYNC_DETECTION_FAIL mode=<m> and clears the guard (next sync
re-applies). X2 transportDev.drop(1).collect — the StateFlow initial
replay no longer re-triggers a bind at startup. X3 PhaseMark
(app/crash/PhaseMark.kt): filesDir/last_phase one-line breadcrumbs,
app-side only (engine untouched): permission_granted (PermissionsGate
callback) -> source_created (VM onExternalSourceReady, pre-bind) ->
analyzer_bound (post cameraSource.bind) -> ai_chain_up /
transport_attached (syncDetection branches) -> first_frame
(AiFrameAnalyzer.analyze, BEFORE sink, once-flag; analyzer ctor gained a
LEADING phaseCtx param so the two trailing-lambda sites compile). Read:
MainActivity.onCreate logs LAST_PHASE=<phase|none> before setContent
(before anything can re-mark). Clear: VM.onCleared only — survives every
crash. Crash file embeds last_phase= too. X4 CrashLogger: INTERNAL
filesDir/crashes/crash-<epoch>.txt is now the PRIMARY sink (permission-
free; DCIM was unreachable for launch crashes + Android/data gated on
11+); DCIM stays best-effort; Log.e("VCAM-CRASH", header+stack) for
logcat/bugreports; crashFiles() helper; DEV "Crash logs" block in
ModelsSheet (count + latest name/KB, COPY to clipboard, SHARE chooser,
Refresh). X5 verified: attach() already capture-only (r54-A2); ring
allocates ONLY in ensureRing() <- enableFeed()/selfTest (feed defaults
OFF, TransportTap inert); fixed enableFeed() calling refreshCaps (su
exec) synchronously on the caller (main) thread — now a daemon thread
(A2 law: probes never main).

Owner device protocol 0-3 attached (install+grant must not crash; else
relaunch + report LAST_PHASE verbatim; dialog=Java vs vanish=native;
device list).

**r54.1 ledger**: b8d321a = HEAD, GREEN run 36405977882 (6m6s) FIRST TRY,
artifact 10962307681 (21,389,154 B) — owner protocol 0-3 pending (LAST_PHASE / dialog-vs-vanish
/ devices). No B-F code was reverted; this delta is crash-fix only on top
of the green r54 base (r54 artifact was 10960243343).

### Round 54.2 — G1+G2 (owner read the exact failure chain off the branch)

G2a CHOICE = RAISE the cap (not clamp): FRAME_PAYLOAD_BYTES 1,382,400 →
3_110_400 (1920*1080*3/2) — the SAME treatment the transport ring got in
r53.1 (owner asked: yes, the AI ring needed it — it was at EXACTLY-720p,
zero headroom). Why not clamp ImageAnalysis: (a) R50 no-engine-capture
law; (b) ResolutionStrategy is advisory, not a guarantee — the ring must
tolerate oversize anyway; (c) raising is process-local config, zero
pipeline change, PRE baseline stays comparable. Frame ring file grows
2.6→6.2 MB (2 slots, one file, two MAP_SHARED mappings — same pages).
Actual analysis resolution reported: DebugFlags.noteAnalysis (first
frame) → CONFIG_EFFECTIVE ... analysis=<w>x<h>|unknown (VM init log +
TransportManager dump) + one-shot AI_ANALYSIS_RES=<w>x<h>
frameBytes=<n> cap=<n> from AiFrameAnalyzer.
G2b AiRing.writePayload: require() REMOVED — oversized/negative length
returns false, ring stays android-import-free so JVM tests keep running;
once-per-run AI_RING_DROP need= cap= w= h= log lives at the caller
(AiProcMonitor.noteRingDrop, Timber) + AI_RING_DROPS=<n> dump field;
client.submit on false → noteRingDrop + noteFrameDropped + return (picked
slot never published, stays FREE). G1 analyze() → try{analyzeInner}
catch(Throwable → ANALYZE_FAIL drops=<n>, first 3 + every 100th)
finally{close}; NOTE (honest correction): client.submit ALREADY caught
Throwable, so G1 closes the remaining unguarded surface (compaction,
TransportTap, breadcrumbs) rather than being the only barrier.
AiRingTest: new drop-contract test (oversize/negative → false, slot
untouched, exactly-cap → true). 113 tests total.

**r54.2 ledger**: e22f5ec = HEAD, GREEN run 36409227109 (6m2s), artifact
 10963911775 (21,391,519 B). G2a choice reported (raise, not clamp);
 owner device protocol unchanged (r54.1 steps 0-3 + this build).

### Round 54.3 — H1-H4: :ai-process observability (owner: crash probably NOT in the app process; the "keeps stopping" dialog is identical for both, and the 02:56 screenshot already showed :ai dying independently)

H1 :ai installs its OWN handler + breadcrumbs: AiInferenceService.onCreate
runs CrashLogger.ensureInstalled(this) + PhaseMark.markAi("proc_start")
FIRST (after the durable-log reset). KEY FACT recorded: the manifest
ContentProvider has NO android:process — it instantiates in EVERY process
of the package, so :ai has had the handler (and TransportManager.attach)
since r54; H1 makes it independent of that detail. Crash files: BOTH
processes write the SAME shared filesDir/crashes (no binder transport
needed — filesDir is shared, same uid); every crash header now carries
proc=main|:ai (CrashLogger.processLabel: Application.getProcessName on
28+, ActivityThread.currentProcessName reflection pre-P); DEV Crash-logs
block shows the latest file labelled proc= + last_phase= AND a separate
"latest :ai crash" line. H2 :ai breadcrumbs (PhaseMark second file
last_phase_ai, content "proc=:ai|<phase>"): proc_start (service onCreate)
-> MODEL_FILE_RESOLVE -> SESSION_CREATE (brackets the ScrfdDetector ctor:
file resolve + OrtSession create) -> FIRST_INFER (processFrame entry) ->
KPS_DECODE (once, before the r52a keypoint decode — PRIME SUSPECT per
owner) -> RESULT_PUBLISH (once, before box handoff). Read back: MainActivity
logs AI_LAST_PHASE= alongside LAST_PHASE; dump carries AI_CHILD_LAST_PHASE;
cleared on CLEAN service onDestroy only. H3 MODEL LOAD GUARD: every load
attempt logs MODEL_FILE=<path> MODEL_SIZE=<exists?bytes:-1> exists= BEFORE
construction; failure logs AI_MODEL_LOAD_FAIL path= size= (with the
OrtException) and the chain stays DOWN (pre-existing catch keeps :ai alive
— H3 enriches with file facts; dump gains AI_MODEL_FILE/AI_MODEL_SIZE,
captured main-side in setModelPath). H4 every durable child line prefixed
"proc=:ai " (AiChildLogTree head) + the child report gains proc=:ai line.
G1 kept (r54.2). NOTE: working tree has out-of-band uncommitted changes
(README.md, .github/, .gitignore) NOT from this branch's work — left
untouched, not committed here.

**r54.3 ledger**: 6acb5ef (RED: python heredoc collapsed \\\\S -> \S in
 Kotlin — unsupported escape, 3 lines) → 631f132 = HEAD, GREEN run
 36414759374 (7m13s), artifact 10965904276 (21,395,657 B). INCIDENT recorded: the local .git
 store was reset to 4581e06 (branch point) mid-turn — first commit landed
 on the initial commit and push was rejected; recovered via git reset
 b5bb034 + re-add of exactly the 8 r54.3 paths; remote history intact.

### Round 54.4 — SHOW THE OWNER THE CRASH (owner STOP-work order: no features; D1-D6 only). Note: local .git was re-clobbered to 4581e06 at turn start (second incident) — reset to 3650a6e before any work; tree verified clean vs remote tip.

D1 PUBLIC SINK: crash files renamed crash-<proc>-<epochMs>.txt (proc in
the NAME — the dialog cannot disambiguate main vs :ai, the filename can).
API 29+: MediaStore.Downloads, RELATIVE_PATH Download/VCamStudio/,
text/plain, IS_PENDING 1 -> write -> 0 (NO storage permission, visible in
any file manager) = the owner-findable sink. DCIM now the <=28 fallback
only. filesDir/crashes stays the always-sink. D2 was ALREADY satisfied
(r54-A provider runs in every process incl. :ai; r54.3-H1
AiInferenceService.onCreate ensureInstalled — verified, no change). D3
THE NEW PART: filesDir/last_launch_ok marker written at EVERY app start
BEFORE permission flow; newCrashesSince() = crash files newer than the
marker; MainActivity.onCreate startActivity(CrashReportActivity) BEFORE
setContent — camera/permission unreachable until CONTINUE. New
crash/CrashReportActivity (manifest-registered, exported=false): newest
first, per-crash Card = D6 summary (proc, exception class, message, first
3 com.vcamstudio frames, last phase — chosen from last_phase_main /
last_phase_ai headers by proc) + full monospace selectable text;
COPY TO CLIPBOARD / SHARE / CONTINUE. D4 ALREADY in place since r54.3
(MODEL_FILE_RESOLVE -> SESSION_CREATE -> FIRST_INFER -> KPS_DECODE ->
RESULT_PUBLISH in filesDir/last_phase_ai — split file so :ai cannot
clobber main's breadcrumbs; owner asked for one last_phase file: the
split is the deliberate improvement, contents tagged proc=:ai). D5
ALREADY present (Log.e("VCAM-CRASH", header+stack) both processes).
NO feature code touched this round: CrashLogger sink/marker, new activity,
MainActivity 8 lines, manifest 1 entry — nothing else.

**r54.4 ledger**: d926c91 (RED: layout.Card import + startActivity in a
 composable without a receiver) → bafcddd (RED: the LocalContext val was
 LOST from the working tree between edits — same gremlin class as the two
 .git clobbers) → 315b29f = HEAD, GREEN run 36431070195 (6m19s), artifact
 10973213280 (21,419,118 B). Owner protocol: install+open (opens), grant
 (may crash — expected), relaunch -> crash screen, COPY -> paste, else
 Files -> Downloads -> VCamStudio -> crash txt.

### Round 54.5 — ONE BUILD: F1-F4 (init-order fix, CRITICAL PATH) + the full round-54 set with toggles DEFAULT OFF (owner correction supersedes the r54.4 park order)

ROOT CAUSE (owner's pasted crash file, SM-S908N, android 36, proc=main):
NPE in StudioViewModel.<init> — viewModelScope uses
Dispatchers.Main.immediate, so collectors ran EAGERLY during construction;
transportDev (1218) / debugFlags (1229) are ~850 lines BELOW the old init
block (367) -> null at first emission. VM is constructed only after the
permission grant (StudioScreen sits inside PermissionsGate) -> exactly the
after-Allow crash. The fatal frame was drop(1)'s unguarded receiver; the
same NPE had already been CAUGHT by syncDetection's runCatching
(SYNC_DETECTION_FAIL mode=? in the engine log) — the guard worked, the
ordering was broken.
F1 drop(1) removed (plain collect; double-bind moot — syncDetection
guarded + idempotent since r54.1). F2b ALL collector launches (init@367,
262 lines + init@1255 CONFIG_EFFECTIVE block) moved into ONE
startCollectors(); invoked from a NEW LAST init block at 1693 — after
every property declaration; ordering can never break again (first surgery
attempt mis-matched the init brace and was DISCARDED via git checkout
before commit; redo asserted drop(1)+model-collector inside the captured
body). F3 verified by line numbers: readers (scrfdSessionDev 174,
_hasCameraLayer 144, transportDev 1218, debugFlags 1229) all < 1693. F4
VM_COLLECTORS_START first line / VM_READY last line of startCollectors.
TOGGLES (condition 1): defaults FLIPPED TO OFF for all seven keys
(transport_attach, transport_probe, ai_fg, xnnpack, feed + NEW
model_probes, crop_dump) — absent file/missing key = OFF; sheet shows 7
buttons; DebugFlags doc rewritten. D and E gained real gates:
runProbes refuses when model_probes off (MODEL_PROBES=off reason=toggle);
dumpDebugCrops refuses when crop_dump off (AI_CROP_DUMP=off). CONFIG_EFFECTIVE
(startup + dump) now lists all seven. DEVIATION (stated to owner): G1
analyzer catch is NOT toggle-gated — disabling a crash-catch can only
reintroduce the crash it fixes; it is passive instrumentation, same bucket
as H1-H4 (owner: default ON). B/C/H1-H4 code unchanged (already green
since r54/r54.3); D probe bytes unchanged (fp16-Conv, r54).

**r54.5 ledger**: b63078f = HEAD, GREEN run 36435440791 (7m41s), artifact
 10976350136 (21,420,091 B). Owner protocol: install -> open -> grant ->
 MUST reach studio (F1-F4 verdict); confirm camera+fps; paste dump
 (CONFIG_EFFECTIVE now lists 7 keys, all off); then flip B/C on one at a
 time, dump after each.

### Round 55 — ScrfdPreprocess.fill() returns null -> detection never runs (owner dump: letterbox NULL all 760 frames, infer=0ms, RUNNING throughout)

Owner diagnosis CONFIRMED against source: upright=0x0/pad=0.0/scale=0.000
are the ?: fallbacks at the AI_INFER_SAMPLE site (binder ~378); fill() has
EXACTLY three null paths, all before any pixel work — consistent with
pre=0ms. NOTE for next round: static math says all three guards SHOULD
pass for 640x480/640 (i420 cap 3,110,400 >= 460,800; tensor asFloatBuffer
capacity = 1,228,800 = 3*640*640 boundary-exact) — so the on-device
numbers must differ from assumption, or ENTER never logs (upstream gate).
The instrumentation below settles it either way.
1 WHICH GUARD: fill() logs SCRFD_FILL_ENTER once per run with REAL caps
and needs (size, i420Cap, i420Need, outCapF, outNeedF); before each return
null logs SCRFD_FILL_NULL reason=dims|i420|out once per reason per run
with the real numbers; android.util.Log via try/catch helpers (Log is not
mocked on the JVM — ScrfdPreprocessTest calls fill()). lastNullReason
sticky until a success clears it. 2 REACHED: SCRFD_FILL_ENTER answers it
(never appears -> upstream gate). 3 NOT SWALLOWED: binder counts
consecutive fill-null frames; SCRFD_FILL_FAIL reason= count= at most 1/s;
>=10 consecutive -> Phase.DEGRADED (enum + childState 4 -> VM map ->
SCRFD_STATE=DEGRADED in the dump), callback.onState(4, "fill_null:<r>"),
writeReport("degraded", ...), reason persisted to filesDir/
scrfd_fill_null.txt. 4 NOOP: SCRFD_SUSPECT_NOOP frames= after 20
consecutive infer==0 (once per run). CONFIG_EFFECTIVE (startup + dump)
now ends with fill_null=<reason|none> read from the file (cross-process
safe). NO model/ring/transport/stub changes (owner: nothing else).

**r55 ledger**: 243eed2 = HEAD, GREEN run 36443384258 (7m35s), artifact
 10978904691 (21,420,964 B). Owner protocol: install (with detection
 toggle on as before), 60 s with a face, paste dump — the dump must now
 contain SCRFD_FILL_ENTER (+caps/needs) or SCRFD_FILL_NULL reason=,
 SCRFD_FILL_FAIL count=, SCRFD_STATE=DEGRADED reason=fill_null after 10,
 SCRFD_SUSPECT_NOOP after 20 zero-infer frames, and CONFIG_EFFECTIVE ...
 fill_null=<reason>.

### Round 56 — XNNPACK abort guard (verdict: ABORTS on SM-S908N/Adreno 730 — item C CLOSED as negative result) + silent model-export fix

PART 1 (device evidence: died at env.createSession between SCRFD_THREADS
and ONNX_SESSION_OK, xnnpack=on, 3179 MB free = not memory): (a) self-heal
— noteChildDeath reads last_phase_ai; if it names SESSION_CREATE while
xnnpack=on -> DebugFlags.set(KEY_XNNPACK,false) + XNNPACK_ABORT=1 log +
xnnpackAbort/Phase fields; a native abort cannot be caught, next launch is
cpu-only, death loop broken. (b) dumpSection renders [XNNPACK_ABORT=1
phase=... xnnpack flag cleared] appended to AI_PROC_DETAIL + a
XNNPACK_ABORT=1 phase= line in the section. (c) SCRFD_CREATE_BEGIN
ep=xnnpack|cpu logged immediately BEFORE createSession — the boundary is
unambiguous (SCRFD_CREATE_BEGIN then silence == abort site). (d) toggle
kept, default OFF (r54.5). PART 2: exportModels passed the raw TREE uri
as createDocument parent -> null on the system provider -> silent skip ->
count=0. Now: getTreeDocumentId -> buildDocumentUriUsingTree(parent);
createDocument null/throw logs MODEL_EXPORT_SKIP name= reason=
create_document_null|create_document_throw. PART 3: importModels now
streaming-SHA-256 verifies against CatalogModel.sha256Hex
(MODEL_IMPORT_VERIFY_OK / MODEL_IMPORT_HASH_MISMATCH); .import temp
deleted on EVERY failure path; export dedupes same-named documents
(no "(1)" copies -> no stale last-wins restore); copyTo 1 MiB buffers
(export + import + hash helper). NIT: DebugFlags store header now says
absent=OFF (comment only, behaviour unchanged). No CI/dependency/
device-specific changes.

**r56 ledger**: d367e67 = HEAD, GREEN run 36452772735 (7m45s), artifact
 10984531770 (21,423,555 B) — owner device test 1: xnnpack flag must stay OFF, ONNX_SESSION_OK
 + SCRFD_STATE=RUNNING must appear, then SCRFD_FILL_NULL reason= (r55
 instrumentation finally gets its frame); test 2: backup 16.9 MB SCRFD ->
 MODEL_EXPORT_OK count=1 + visible file; clear data -> restore ->
 MODEL_IMPORT_OK count=1 (+VERIFY_OK) + SCRFD READY.
