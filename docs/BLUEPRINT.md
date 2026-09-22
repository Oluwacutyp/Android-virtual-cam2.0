# VCam Studio — Deep Research, Locked Architecture & Phased Build Plan

> **Product:** Free, pure-Android Virtual Camera Studio ("become anyone" + pro scene compositor + live voice/lip-sync + RTMP + optional system-wide camera injection).
> **Constraints locked by product owner:** 100% free, no watermark, no subscription, no cloud requirement for core features, works fully offline after model download, professional control density (not minimal).
> **Status:** Architecture LOCKED — implementation starts only after owner approval of this document. No code written yet.
> **Date:** 2026-09-22 · **Repo:** `Oluwacutyp/Android-virtual-cam2.0` (clean slate — single initial commit, MIT)

---

## Executive Summary

The real-time "digital mask" market has a hole exactly where we want to be. DeepFaceLive — the desktop king of free real-time face swap — was **archived November 13, 2024** and was Windows-only anyway. ManyCam is paid/watermarked and has no real AI face swap on mobile. Mobile "virtual camera" apps are either root-only toys that replace the feed with a static photo, or subscription-gated with watermarks. **Nobody ships a free, offline, professional scene studio + live face swap + voice-driven lip sync on Android.** That is the gap.

This document locks:

1. **What is technically proven** (research summary, with licensing facts that shape our design).
2. **A single, clean architecture** built around a resilient GPU render engine, an ONNX AI subsystem with performance tiers, a real-time audio graph, and a fan-out output stage (preview / recorder / RTMP / system injector).
3. **Four phases** with hard exit criteria — each phase ends with something real running on a device, never a "fake build."
4. **The failure-mode kill list** — every way previous attempts died (black preview, broken shaders, dependency hell, phantom features) mapped to a specific architectural counter-measure.

The two honest engineering judgments up front:

- **Face swap live on-device is feasible and proven** (SCRFD → ArcFace → INSwapper, all ONNX, runs on Android today). But the best open models carry **non-commercial research licenses**, so the app must **download models at user request** and never bundle them, with a roadmap to train and ship our own permissively-licensed model.
- **True generative lip-sync (MuseTalk-class) is not yet real-time on phone NPUs for the masses.** The correct product decision is a **two-tier lip system**: a deterministic, zero-latency **audio-driven viseme engine** (runs on every device, looks convincingly alive) shipping first, with an optional **neural lip-sync pack** as an experimental download for flagship devices.

---

# A. Deep Research Summary

## A.1 Competitive landscape

| Product | Model | Price | Face swap | Scenes/layers | Virtual camera | Voice | Lip-sync | Weakness we exploit |
|---|---|---|---|---|---|---|---|---|
| **ManyCam** | Commercial | Sub (~$40–60/yr) | ✗ | ✓ (desktop) | ✓ desktop only | Basic | ✗ | Watermark on free tier; no AI; weak mobile app |
| **GhostCam** | Commercial (Android) | Paid | Photo/video ghosting only | Minimal | Root/spoofer-style | ✗ | ✗ | Not a studio; no real-time AI |
| **VirtualCam.io** | Web/PC | Sub | ✗ | Basic | Browser-based | ✗ | ✗ | Not Android, not offline |
| **OBS Studio** | Open source (PC) | Free | ✗ (plugin-dependent) | Best-in-class | ✓ (desktop) | Plugins | ✗ | No Android build; PC required |
| **DeepFaceLive** | Open source (PC) | Free | ✓ real-time, GPU | ✗ | PC virtual cam | ✗ | ✗ | **Archived 2024-11-13**, Windows-only, needs GPU PC |
| **FaceFusion / inswapper tools** | Open source (PC) | Free | ✓ (file-based, some live) | ✗ | ✗ | ✗ | Partial | PC-only, CLI/Gradio UX |
| **android-face-fusion** (open, 2025) | Open (Android) | Free | ✓ **on-device, ONNX** | ✗ | ✗ | ✗ | ✗ | Single-image swaps; proves our core pipeline is feasible |
| **Foxcam / root spoofer apps** | Commercial (Android) | Paid | ✗ | ✗ | ✓ (root/Zygisk) | ✗ | ✗ | Root-only, static media, no studio, black-screen complaints |
| **Camera2Magic (LSPosed, open)** | Open (Android) | Free | ✗ | ✗ | ✓ per-app photo/video/RTSP | ✗ | ✗ | Reference for our injector; no studio attached |

**Takeaway:** No product combines (studio compositor) + (on-device real-time face swap) + (voice clone + lip-sync) + (system-wide injection) on Android at $0. Every capability exists somewhere open-source — our job is integration quality, not invention of new science.

## A.2 On-device real-time face swap

**Findings:**

- The proven pipeline is the InsightFace-style stack, all exportable to ONNX and demonstrably running on Android via ONNX Runtime (`android-face-fusion` does exactly this):
  1. **Detection + 5-point landmarks:** SCRFD `det_10g.onnx` (~16 MB, 640×640 input).
  2. **Identity embedding:** ArcFace `w600k_r50.onnx` (~166 MB, 112×112 aligned input, 512-dim output).
  3. **Swap:** `inswapper_128.onnx` (~553 MB fp32) — takes the aligned 128×128 target face + transformed source embedding (`latent = EMAPᵀ · embedding`, normalized) and outputs a 128×128 swapped face, pasted back via inverse similarity transform with mask erosion + feathered blending. The **EMAP matrix is extracted from the ONNX graph initializers** — a known trick, we keep `emap.bin` as a 1 MB asset.
  4. Optional enhancement: GFPGAN/CodeFormer — **too heavy for mobile live**; skipped in v1, revisit as an optional "enhance" pack.
- **Performance reality:** detection every frame is wasteful — run detection at 5–10 Hz and **track** between detections with MediaPipe Face Mesh (478 landmarks, blendshapes, Apache-2.0) at 30 fps. The 128 px swap model in **fp16** (~277 MB) on XNNPACK/NNAPI lands in the **25–50 ms/frame** range on recent Snapdragons/Dimensity flagships → 20–30 fps live swap on flagships, ~10–15 fps on upper-mid-tier with temporal smoothing (swap every 2nd frame, blend). These numbers are validated as a **Phase 2 exit spike**, not assumed silently.
- **Licensing is the critical constraint:** InsightFace's pretrained models (SCRFD, ArcFace, INSwapper) are **non-commercial research licensed**. The established, defensible pattern (used by android-face-fusion) is **user-initiated runtime download from a mirror (e.g., HuggingFace), zero bundling, zero redistribution**. Our long-term fix: train/release our own swap + detection + embedding models under Apache-2.0 (Phase 4 track), and structure the app around a **Model Manager** so models are pluggable files, never hard-wired assets.
- MediaPipe gives us free, permissively-licensed **face detection, mesh tracking, blendshapes (jawOpen, mouthSmile, eyeBlink…)** — the backbone of tracking, avatar rigging, and lip animation.

**Decision:** INSwapper-architecture swap model via ONNX Runtime Mobile (XNNPACK primary, NNAPI opt-in), MediaPipe for per-frame mesh tracking, runtime model downloads, fp16 conversion pipeline with optional int8 later.

## A.3 Lip-sync / talking head from live mic

**Findings (model survey):**

| Model | Method | Res | Speed | License | Mobile-live verdict |
|---|---|---|---|---|---|
| **Wav2Lip** | GAN | 96×96 | Fast | **Non-commercial (research)** | ✗ license blocked + too soft |
| **MuseTalk 1.5** | 1-step latent inpainting (VAE+UNet) | 256×256 | 30 fps+ on **V100 GPU** | **MIT** | ✗ too heavy for phone real-time today; experimental tier only |
| **LatentSync** | Latent diffusion | 256–512 | Seconds/clip | Apache-2.0 | ✗ diffusion = no |
| **SadTalker** | 3DMM from still photo | med | Medium | Apache-2.0 | Offline render only; informs our photo-avatar rigging |

**Decision — Tiered Lip Engine (this is a core architectural commitment):**

- **Tier 1 (ships in Phase 3, all devices): Audio-Driven Viseme Engine.** Mic → 20–30 ms frames → VAD (sherpa-onnx Silero, Apache-2.0) → mel/MFCC + energy + spectral-centroid features → lightweight viseme classifier (tiny ONNX or rule-based formant mapping; A/I/U/E/O + M/B/P closed + rest) → **viseme timeline with 100–150 ms smoothing window** → GPU mouth-region warp/blend applied to the avatar's face crop using its own landmark rig. Cost: **< 2 ms/frame on CPU**. This is the same class of technique VTuber/Live2D pipelines use; it reads as convincingly "talking" on camera, at zero thermal cost. Honest labeling in UI: **"Lip Sync (Fast)"**.
- **Tier 2 (Phase 4 experimental download, flagship tier): Neural Lip Pack.** Distilled MuseTalk-class model (MIT license keeps us clean) targeting 256×256 mouth-crop inpainting, fp16 + int8, running at reduced fps with temporal blending and only on devices passing a benchmark gate. Honest labeling: **"Lip Sync (AI, Beta)"**.
- **Photo Avatar mode** ("single photo becomes a talking head"): photo → MediaPipe mesh → rig (jaw, lips, brows, blink planes) → viseme timeline drives the rig + subtle head sway/breathing noise. Achievable, robust, no diffusion.

## A.4 Voice changer + voice cloning

**Findings:**

- **OpenVoice v2** (MyShell) is now **MIT licensed** including v2 checkpoints, and community ONNX ports exist (e.g., `tone_extract.onnx` → 256-dim voice fingerprint from ~10 s reference audio; `tone_color.onnx` → spectrogram-domain tone transfer, 22.05 kHz). Two models, a few hundred MB total, spectrogram-domain inference is tractable on-device → **instant voice-clone profiles with no training wait**. This is our voice-clone backbone.
- **RVC** gives the best neural real-time conversion (90–170 ms on PC GPUs) but on Android via ONNX it is still **emerging/heavy** → optional "Neural Voice Pack (Beta)" in Phase 4, never a core dependency.
- **sherpa-onnx** (Apache-2.0) provides on-device **VAD, speech enhancement (GTCRN), TTS (Piper/matched voices), speaker ID** — all offline, Android-native AARs. Our audio utility layer.
- **Baseline voice changer must be pure DSP** (pitch/formant shift, robot, chorus, reverb, EQ, phone-radio) — zero-latency, works on 100% of devices, no models. This is the always-works fallback that keeps the product honest on low-end phones.

**Decision:** DSP chain always available; OpenVoice tone-converter ONNX as "Voice Clone Profiles" (record 10 s → profile → applies live to mic and to TTS lines); optional RVC pack later. TTS for scripted "voice lines" via sherpa-onnx Piper voices (MIT models).

## A.5 Android virtual camera injection

**Findings:**

- **Non-root, system-wide camera replacement is impossible on stock Android** — camera HAL paths and `/dev/video` nodes (DeviceAsWebcam, Android 14+) are SELinux-locked to system apps. Any app claiming otherwise is either lying or using dangerous exploits. We will not pretend otherwise.
- **Root path is proven and mature:** LSPosed/Zygisk modules hooking `Camera1`/`Camera2`/`ImageReader`/WebRTC at the framework level work on Android 11–15. **Camera2Magic** (open, libxposed API 102) does per-app photo/video/RTSP-stream replacement — this is our reference implementation. `CameraInterceptor` (open) is a second reference for API hook points. Commercial Foxcam proves demand (and its "black screen" support forum proves the pitfall).
- **Non-root middle ground:** **LSPatch** — patch a *copy* of a target APK with our hook module embedded, sideload it (works without root, per-app, user opts in per app). Offered as "Advanced: patch an app copy" with clear guidance. Also: our **in-app camera** means every scene/source is available inside the Studio and to anything that consumes our recordings/streams — the honest default.
- **Injection transport (Studio → hooked app):** the module inside the target process needs frames from Studio. Design: Studio runs a **foreground service publishing a shared-memory ring buffer (ashmem/SharedMemory + Binder AIDL handshake)** carrying NV12 frames + timestamps; the module maps it and swaps `ImageReader`/`onCaptureCompleted` buffers. Fallback: file-less broadcast control channel (`Settings.Global`/ContentProvider for config like Camera2Magic does). If Studio isn't running → module passes real camera through (fail-open, never black).

**Decision:** Ship the **LSPosed module as a separate APK in-repo** (`:vcam-module`) in Phase 3, supporting Camera2 preview + ImageReader stills + WebRTC paths first, Camera1 next. Fail-open design, per-app scope, Android 11–15 target matrix. No root required claims inside the main app.

## A.6 Camera compositing without broken GL shaders

**Findings / hard-won lessons baked into design:**

- Never use `GLSurfaceView` for the core pipeline (its lifecycle/context management is the #1 cause of black previews). We run a **single dedicated render thread with our own EGL context, persistent across activity lifecycle**, using `SurfaceView`/`TextureView` + `SurfaceTexture` we own.
- **Render at scene resolution into FBOs, then scale to whatever output surface exists** — decouples pipeline from surface size/aspect churn (rotation, PiP, resize) → no aspect glitches, no black frames on surface recreation (we keep presenting the last composited frame while the new surface attaches).
- Effects authored in **GLSL ES 3.0** (universal) with optional **AGSL RuntimeShader ports on API 33+**; every shader goes through a validation harness (golden-frame tests) before merge. No hand-copied shader soup from abandoned libraries (GPUImage is effectively unmaintained — we do not adopt it).
- MediaPipe and camera produce **YUV**; we standardize a single, tested YUV→RGB shader path (including legacy `YUV_420_888` quirks across vendors) with golden tests per layout family.
- CameraX for capture (binding `Preview`/`ImageAnalysis` to our GL via `SurfaceTexture` interop is well-trodden), with Camera2-level controls exposed through CameraX Camera2Interop (manual exposure, ISO, WB, focus) for the "pro" panel.

**Decision:** Own GL engine (`:engine-render`), scene-graph compositing, ping-pong FBOs, Canvas→texture bridge for text overlays, `HardwareBuffer`/shared-EGL handoff to encoder. A **frame watchdog** (no frame for N ms → controlled re-init + last-good-frame hold) makes "black preview" a monitored, recoverable state — not a user-visible bug class.

## A.7 Recording + RTMP streaming

**Findings:**

- **StreamPack 3.x** (Apache-2.0, Maven Central, actively maintained fork `dimadesu/StreamPack`): RTMP/RTMPS/SRT, TS/FLV/MP4/fMP4, **custom video sources accepted** (we feed our composited GL output), record-while-stream, adaptive bitrate for SRT. Primary choice.
- **RootEncoder** (Apache-2.0) is the fallback RTMP/RTSP engine if StreamPack integration fights us (it accepts surface input + has proven RTMP client).
- Encoding: hardware `MediaCodec` (H.264 default; HEVC opt-in), fed from our GL via a shared-EGL input surface. AAC via `MediaCodec`, sourced from our **audio mixer output** (so mic + voice FX + video audio + music all land in the recording/stream consistently).

**Decision:** `:engine-output` exposes three sinks off one composited program bus: (1) Preview, (2) MP4 recorder (MediaMuxer), (3) StreamPack RTMP(S)/SRT with auto-reconnect + stats UI (bitrate graph, dropped frames, RTT).

## A.8 Ten research take-aways that shape the architecture

1. Everything needed exists open-source; **integration quality is the moat**.
2. Face swap on-device: **proven**; the problem is licensing discipline + performance engineering.
3. Generative lip-sync is not phone-real-time for the masses → **viseme engine first, neural later, labeled honestly**.
4. Voice cloning: OpenVoice tone-transfer ONNX = **instant profiles**, MIT-clean; RVC optional later.
5. Model licensing means **runtime download via Model Manager**, never bundling, pluggable model files.
6. System-wide virtual camera = **root/LSPosed reality**; LSPatch per-app as non-root advanced path; no fake promises in UI.
7. `GLSurfaceView` is banned; **one EGL context, one render thread, FBO-native scene graph**.
8. StreamPack solves RTMP/SRT; don't write protocol code.
9. sherpa-onnx = offline VAD/enhancement/TTS; don't write audio ML plumbing.
10. Every phase must end **on-device**: golden-frame GL tests, camera matrix smoke tests, and a real demo — the anti-"fake build" doctrine.

---

# B. Recommended Final Architecture

## B.1 System diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              VCam Studio (app)                              │
│                                                                             │
│  ┌─────────────── UI: Compose Material 3 "Studio" ───────────────────────┐  │
│  │  Stage (preview) · Scene bin · Layer inspector · Audio mixer · Docks  │  │
│  └──────────────────────────────┬───────────────────────────────────────┘  │
│                                 │ state (Kotlin Flow)                       │
│  ┌──────────────────────────────▼───────────────────────────────────────┐  │
│  │                        DIRECTOR (scene controller)                   │  │
│  │  scene graph · transitions · program bus · snapshots · presets       │  │
│  └───┬───────────────┬───────────────┬───────────────┬──────────────────┘  │
│      │               │               │               │                     │
│  ┌───▼────┐   ┌──────▼─────┐   ┌─────▼──────┐   ┌────▼───────────────┐    │
│  │ SOURCES │   │ RENDER     │   │ AI        │   │ AUDIO GRAPH        │    │
│  │ Camera2 │──▶│ engine     │◀──│ face swap │   │ mic→AEC/NS→DSP→VC  │    │
│  │ Image   │   │ 1 EGL ctx  │   │ mesh track│   │ OpenVoice clone    │    │
│  │ Video   │   │ FBO graph  │   │ viseme lip│   │ VAD · enhancer     │    │
│  │ Text    │   │ GLSL/AGSL  │   │ (tiered)  │   │ mixer (4 buses)    │    │
│  │ Color   │   │ watchdog   │   │ perf tiers│   └────┬───────────────┘    │
│  └────────┘   └──────┬─────┘   └───────────┘        │                     │
│                      │ composited program frames (GL tex + ts)            │
│  ┌───────────────────▼────────────────────────────────▼────────────────┐   │
│  │                        OUTPUT FAN-OUT                               │   │
│  │  Preview Surface │ MP4 Recorder │ RTMP(S)/SRT (StreamPack)          │   │
│  │                  │ (MediaMuxer)  │ hardware H.264/HEVC + AAC        │   │
│  │  ┌───────────────▼───────────────▼───────────────────────────────┐  │   │
│  │  │ VCam Publisher: ashmem ring buffer + AIDL (NV12 frames+meta)  │  │   │
│  │  └───────────────────────────┬───────────────────────────────────┘  │   │
│  └──────────────────────────────┼───────────────────────────────────────┘   │
└─────────────────────────────────┼───────────────────────────────────────────┘
                                  ▼
              ┌────────────────────────────────────────────┐
              │  :vcam-module (LSPosed APK, optional)      │
              │  hooks Camera2/Camera1/ImageReader/WebRTC  │
              │  in target apps · per-app scope            │
              │  fail-open → real camera if Studio absent  │
              └────────────────────────────────────────────┘
   External (user-triggered downloads): HuggingFace model mirror → Model Manager
```

## B.2 Gradle module map

| Module | Purpose | Key deps |
|---|---|---|
| `:app` | Compose UI, DI (Hilt), navigation, settings, onboarding | Compose BOM, Material3, Hilt, Coil |
| `:core-common` | Result types, dispatchers, logging, units, geometry | kotlinx, timber |
| `:engine-render` | GL render thread, EGL, FBO scene graph, shaders (GLSL ES3 + AGSL 33+), watchdog | (no GL libs — own code) |
| `:engine-capture` | CameraX capture + Camera2Interop pro controls, YUV→GL | CameraX, Camera2 |
| `:engine-media` | Image decode, video layers (loop/mute/speed/volume) via ExoPlayer→GL | Media3 ExoPlayer |
| `:engine-ai-face` | ONNX sessions, SCRFD detect, mesh track (MediaPipe), ArcFace, INSwapper, paste-back, masks | onnxruntime-android, MediaPipe Tasks Vision |
| `:engine-ai-audio` | VAD, speech enhancement, viseme engine, OpenVoice tone clone/convert, TTS lines | sherpa-onnx, onnxruntime |
| `:engine-audio` | AAudio/Oboe capture+playback, DSP chain (pitch/formant/robot/reverb/EQ), 4-bus mixer, latency meter | Oboe (or AAudio direct) |
| `:engine-output` | Recorder (MediaCodec+MediaMuxer), StreamPack RTMP(S)/SRT, stats, reconnect, VCam publisher service | streampack-core/rtmp/srt, Media3 |
| `:vcam-module` | Separate APK: LSPosed module (xposed API), ashmem client, per-app config provider | libxposed api |
| `:tools-dev` | Shader golden-frame harness, camera matrix logger (debug builds only) | — |

Rule: **downward dependencies only**; `:app` depends on engines via interfaces defined in `:core-common` + DI. No engine depends on Compose.

## B.3 Render engine (the part that must never break)

- **One render thread** owns: EGL context, all GL objects, scene FBO graph, texture uploads. All other threads talk to it via command queue (lock-free mailbox). Context is created **once per process** and survives Activity recreate; output surfaces attach/detach without pipeline restart.
- **Scene resolution pipeline:** sources → per-layer effect chains → compositor FBO at chosen scene resolution (720p/1080p/2160p setting) → present-scale to output. Output surface churn (rotate/resize/background) never recompiles or re-allocates the graph.
- **Node types:** `SourceNode` (camera/video/image/color), `EffectNode` (chainable, parameterized), `MaskNode` (rect/circle/luma/chroma), `TransformNode` (2.5D: position/scale/rotation/corner-pin), `BlendNode` (13 Porter-Duff + screen/multiply/overlay/soft-light).
- **Watchdog:** heartbeat per source + per output; stall > 500 ms → auto re-init that surface while **continuing to present the last composited frame**; incidents logged to an in-app Diagnostics screen (frame times histogram, GPU renderer string, recovery events) — this is how we guarantee "never black" *and prove it*.
- **Canvas bridge:** text/lower-thirds/shapes rendered via Android `Canvas` into a bitmap → `GL_TEXTURE_2D` upload on the render thread (texture atlas cached). Fonts/emoji correct by construction; no text shaders.

## B.4 Scene & layer model (OBS-grade, mobile-shaped)

- **Scenes:** ordered collection; global transitions (cut/fade/slide/wipe/stinger-with-video); per-scene audio bus assignments; scene snapshots (A/B compare), import/export JSON.
- **Layers (bottom→top):** color background → media (image/video) → camera → avatar (photo/video with face engine attached) → overlays (text/shapes/lower-thirds/counter) → filter LUT (full-frame) → mask/vignette. Per-layer: visibility, lock, solo, opacity, blend mode, transform (incl. fit/fill/stretch modes), rounded corners, drop shadow, chroma key (with spill suppression + tolerance/shadow/highlight controls), speed/volume (video), in/out trim (video), loop mode (off/loop/bounce), z-reorder gestures.
- **Avatar attachment:** face-engine profile (source photo/identity + alignment + mouth rig) attaches to any media/camera layer; engine runs detection/track on that layer's frames only.

## B.5 AI subsystem — scheduling & performance tiers

```
per camera frame (30fps):
  mesh track (MediaPipe, ~2-4ms) ──▶ transform update
  every Nth frame (150-200ms): SCRFD redetect (recovery/quality check)
  swap scheduler: if tier allows and face stable:
      align 128px ─▶ INSwapper fp16 (~25-50ms, own executor thread)
      paste-back (mask erode+feather) ─▶ compositor (temporal blend if rate-limited)
```

- **Device tiers** (benchmark on first run + thermal listener): `FLAGSHIP` (30fps swap target), `MID` (15fps swap + temporal blend), `LITE` (photo avatar + tracking only). User override in Settings with live FPS counter.
- Executors: ONNX sessions pinned to a dedicated bounded thread pool; NNAPI/XNNPACK selectable per model in Developer settings; fp16 preferred, int8 optional per model file.
- All models loaded **lazily** and evicted on scene change; GPU memory budget displayed in Diagnostics.

## B.6 Audio graph

```
Mic (AAudio, 10-20ms bursts) ─▶ AEC/NS (platform) ─▶ 3-band EQ + gate
   ─▶ Voice FX DSP chain (pitch ±12st, formant ±50%, robot/radio/reverb/chorus)
   ─▶ [optional] OpenVoice tone-convert (clone profile)  ─▶ [optional] RVC pack
   ─▶ Mixer bus A
Video layer audio ───────────────────────────────────────▶ Mixer bus B (per-layer vol/pan/duck)
Music file layers ───────────────────────────────────────▶ Mixer bus C
TTS voice lines (sherpa-onnx Piper) ──────────────────────▶ Mixer bus D
Mixer master ─▶ limiter ─▶ { monitor out, encoder AAC, recorder }
Side chain: mic VAD ─▶ lip viseme extractor ─▶ face engine (timeline, 120ms smoothing)
```

Latency budget displayed live (capture→mix→monitor). Latency compensation offsets for video-layer audio.

## B.7 Output subsystem

- **Program bus:** the composited final FBO + mixed master audio.
- **Recorder:** H.264 (or HEVC) MediaCodec from shared-EGL input surface + AAC → MediaMuxer → MP4 (avc1+aac, faststart on finalize). Records at scene resolution; independent of preview surface state (recording survives rotation/背景 recreate).
- **Streamer:** StreamPack RTMP/RTMPS/SRT sink consuming the same surfaces; auto-reconnect w/ exponential backoff; stats panel (bitrate graph, fps, dropped, RTT); stream + record simultaneously (StreamPack supports it).
- **VCam publisher:** foreground service; NV12 readback of program frames (PBO async) → `SharedMemory` ring buffer; AIDL control (start/stop/resolution/fps; per-target enable). The LSPosed module consumes it. Frame drop policy: publisher drops to 24fps under load rather than stalling.

## B.8 Model Manager & licensing strategy

| Model | Size (fp16) | Role | License | Distribution |
|---|---|---|---|---|
| MediaPipe face detector/mesh/blendshapes | ~5 MB | tracking, rigging, viseme input | Apache-2.0 | **Bundled** |
| SCRFD det_10g | ~16 MB | robust redetect | Non-commercial (InsightFace) | User download |
| ArcFace w600k_r50 | ~166/83 MB | identity embedding | Non-commercial | User download |
| INSwapper-arch swap | ~553/277 MB | face swap | Non-commercial | User download |
| OpenVoice tone_extract + tone_color | ~100–700 MB | voice clone profiles | MIT (v2) | User download |
| Piper TTS voices | 20–100 MB | voice lines | MIT | User download |
| Silero VAD, GTCRN enhancer | small | VAD, denoise | MIT/Apache | Bundled via sherpa-onnx |
| (Phase 4) our own swap/detect/embed | TBD | replace non-commercial stack | Apache-2.0 (ours) | Bundled |

Rules: downloads only over user-tapped "Get model" (explicit, sized, licensed — license text shown in-app), checksums verified, models stored in app-private storage, importable/exportable as files (`.vcm` zips) for offline sharing. **The app is and stays free; models are community-mirrored files the user fetches — the same pattern established open-source projects use.**

## B.9 Persistence & DI

- Room DB: scenes, layers, presets, voice profiles (metadata; embeddings as blobs), stream targets, model registry.
- DataStore: settings (theme, perf tier, audio devices).
- Hilt everywhere; engines exposed as interfaces; every engine has a `FakeEngine` for Compose preview/tests (this is also our anti-"fake build" irony handled correctly: fakes live in tests, never in release).

## B.10 Tech stack (pinned at Phase 1 start)

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin (app/engines), C++ (audio DSP, frame publisher) | ecosystem + control |
| Min SDK | 26 (Android 8) · target latest | ONNX/AAudio coverage; 97%+ devices |
| UI | Jetpack Compose + Material 3 (custom dark studio theme) | velocity, control density |
| Camera | CameraX + Camera2Interop | reliability + pro controls |
| Video decode | Media3 ExoPlayer | loop/trim/speed built-in |
| Graphics | own GL ES 3.0 engine; AGSL on 33+ | no broken-shader dependencies |
| ML runtime | ONNX Runtime Mobile (XNNPACK/NNAPI) + MediaPipe Tasks | proven on-device swap |
| Audio | AAudio/Oboe, platform AEC/NS, own DSP | latency + zero-dep fallback |
| Speech utils | sherpa-onnx (VAD/enhance/TTS) | Apache-2.0, offline |
| Voice clone | OpenVoice v2 ONNX (tone extract/convert) | MIT, instant profiles |
| Streaming | StreamPack 3.x (RTMP/RTMPS/SRT) | maintained, custom sources |
| Recording | MediaCodec + MediaMuxer | no extra deps |
| Injection | libxposed (LSPosed) module APK; LSPatch guide | the only real path |
| DI/async | Hilt, Coroutines/Flow, Room, DataStore | standard, stable |

---

# C. Phased Build Plan

> Each phase has **hard exit criteria** — a phase is not "done" because code exists; it's done when the demo checklist passes on the device matrix (Pixel 6/8-class flagship, Samsung A-class mid, Android 10 & 15). CI runs unit + Robolectric + shader golden tests; every PR includes a real-device smoke log.

## Phase 1 — "Solid Foundation" (the studio that never dies) · ~3–4 weeks

**Goal:** a beautiful, stable scene compositor with camera/image/video sources, full recording, zero black frames.

Deliverables:
1. Project scaffold: modules, pinned BOM, CI (build + unit + lint), app icon/branding, onboarding (permissions explained).
2. `:engine-render` v1: render thread + EGL + FBO graph + watchdog + Diagnostics screen (FPS, frame-time histogram, recovery log).
3. Camera source: front/back, 720p/1080p, mirror, orientation lock; pro panel v1 (exposure/ISO/WB/focus via Camera2Interop).
4. Image layer (pan/zoom/fit modes), video layer (loop/mute/volume/speed/trim), color layer, text layer (Canvas bridge).
5. Scene system: multi-scene, multi-layer, reorder, opacity/blend, per-layer transforms, cut + fade transitions.
6. Effects v1: brightness/contrast/saturation/gamma, temperature/tint, sharpen, vignette, blur (background blur ready), 3 LUTs + custom LUT import (.cube).
7. Audio: mic capture + level meter + monitor toggle; video-layer audio mixing; master limiter.
8. Recorder v1: MP4 (H.264+AAC) at scene resolution; library of recordings; share intent.
9. Settings: scene resolution, perf tier seed, theme, about (licenses screen — MIT-grade compliance from day one).

**Exit criteria:** 30-minute continuous session on mid-tier device: record 5 scenes × 3 sources with zero black frames across 20 background/rotate/intent stress cycles; golden-frame shader tests green; crash-free ANR-free run.

## Phase 2 — "Become Anyone" (face engine + streaming) · ~4 weeks

**Goal:** real-time face swap from one photo on camera and video layers; RTMP live.

Deliverables:
1. Model Manager UI + downloader (SCRFD/ArcFace/INSwapper fp16; checksums; license display; import/export).
2. Face pipeline: detect → mesh track → align → swap → mask paste-back (erode/feather/color-match); identity profile manager (multiple faces, thumbnails).
3. Swap applied to: camera layer, video layer, photo avatar (single photo "speaks" with rig-driven motion — jaw/blink/sway; no mic yet).
4. Perf tiers auto-detect + manual override; live FPS/latency HUD; thermal state listener auto-downshifts tier (and upshifts back).
5. Face engine params panel: swap strength, mask feather/erode, color match on/off, redetect rate, tracking smoothing.
6. Streaming: StreamPack RTMP/RTMPS + stream targets (save targets, stream key vault, reconnect policy, stats panel). Record + stream simultaneously.
7. Chroma key (tolerance/spill/shadow/highlight) + PiP preset (instant "streamer layout").

**Exit criteria:** ≥15 fps sustained swap on mid-tier, ≥24 fps flagship at 1080p scene, 10-min stream to a local MediaMTX server with zero dropped-connection errors, thermal curve verified (no unbounded temp rise), A/B swap quality approved by owner on 5 test identities.

## Phase 3 — "Voice & Lips" + system injection · ~4 weeks

**Goal:** the full "become anyone, sound like anyone, lips move with my voice" loop; optional root virtual camera.

Deliverables:
1. Voice changer DSP rack (pitch/formant/robot/radio/reverb/chorus/EQ/gate) with per-scene presets and A/B bypass.
2. Voice Clone Profiles: record 10 s reference → OpenVoice tone extract → profile applies live to mic (and to TTS lines); profile manager (rename, mix strength, export/import).
3. Viseme Lip Engine (Tier 1): mic-driven mouth animation on photo avatars + swapped video layers; sliders (opening scale, smoothing, sensitivity, latency trim ~±150 ms); labeled "Lip Sync (Fast)".
4. **Loop Avatar mode (the flagship workflow):** uploaded video (loop + mute) + face swap + live mic + voice profile + lip-sync = the complete "speak as someone else" scene, one tap from a template.
5. TTS voice lines: type text → Piper voice → plays into master mix (teleprompter-style list).
6. `:vcam-module` v1: LSPosed APK; Camera2 preview + ImageReader + WebRTC paths; per-app scope config; ashmem publisher protocol; fail-open passthrough; install guide (root users) + LSPatch per-app guide (non-root users).
7. Recording upgrades: record with all voice effects baked; pause/resume.

**Exit criteria:** lip movement visibly matches speech on 3 test voices (subjective owner sign-off + energy-correlation sanity metric); full workflow demo (photo→avatar→voice→lips→record→stream→inject into a video-call app on rooted test device); module fail-open verified when Studio killed.

## Phase 4 — "Pro Polish" (the studio people stay for) · ~4+ weeks

Deliverables:
1. Transitions pro set (slide/wipe/stinger video), scene snapshot A/B, scene export/import (share presets).
2. SRT output + adaptive bitrate; WebRTC/WHIP sink (experimental); multi-bitrate fallback.
3. Neural lip pack (MuseTalk-class distilled, MIT) — beta gate behind benchmark; "Lip Sync (AI, Beta)".
4. Optional RVC neural voice pack (beta); GPU/NPU delegate toggles.
5. Performance hardening: sustained-performance mode integration, per-tier quality scaler, battery/thermal HUD.
6. Our own Apache-2.0 face models track begins (data plan, training pipeline, eval harness) — goal: replace non-commercial stack long-term.
7. F-Droid + GitHub Releases distribution (reproducible builds), full docs site, in-app user guide, crash reporting optional/opt-in (privacy-first: no default telemetry).
8. Accessibility pass (TalkBack on all controls), localization scaffold (EN + 2 languages).

**Exit criteria:** 7-day dogfood by owner (daily streaming/recording use), <1% crash-free-session loss, store-ready metadata, reproducible release build from CI.

---

# D. Feature List (priority-ranked)

**P0 — Product-defining (must ship before any public release)**
1. Rock-solid camera preview (watchdog, last-good-frame, lifecycle-proof) 
2. Scene system with layers, transforms, blend modes, transitions (cut/fade)
3. Image + video sources (loop/mute/speed/trim) + color/text layers
4. MP4 recording of the composite with mixed audio
5. Real-time face swap from one photo (camera + video layers) with quality controls
6. Model Manager (download/import/export, licenses shown)
7. Beautiful pro UI + Diagnostics screen
8. Licenses/about compliance screen

**P1 — Core differentiation**
9. RTMP/RTMPS streaming (+record-while-stream, stats, reconnect)
10. Voice changer DSP rack with presets
11. Photo avatar mode (talking photo, rig-driven)
12. Viseme lip sync (mic-driven) on avatars
13. Voice clone profiles (OpenVoice) live on mic + TTS lines
14. Loop Avatar mode template (video + mic + lips + voice = one-tap workflow)
15. Chroma key + PiP presets
16. Perf tiers + thermal autonomy

**P2 — Power users**
17. LSPosed virtual camera module (+LSPatch guide) 
18. SRT streaming, adaptive bitrate
19. Stinger transitions, snapshots, scene import/export
20. Pro camera controls (ISO/shutter/WB/focus/zoom curves)
21. Audio mixer pro (per-bus EQ, ducking, latency compensation)
22. Custom LUT import, mask layers

**P3 — Frontier**
23. Neural lip-sync pack (beta) · 24. RVC voice pack (beta) · 25. WHIP/WebRTC · 26. Own Apache-2.0 face models · 27. Plugin-style shader packs · 28. Multi-device collab (protocol TBD)

---

# E. UI/UX Design Direction — "Broadcast console in your pocket"

**Feel:** dark, dense, precise — like OBS met a video-editing suite, designed for one-handed reach. Custom Material 3 theme: near-black `#0E1116` surfaces, single accent (electric cyan `#22D3EE`) + amber for "LIVE/REC" states, tabular numerals for meters, 44dp min touch targets, everything glare-safe.

**Layout (portrait default):**

```
┌────────────────────────────────┐
│ Top bar: REC ● 00:12  LIVE ●  FPS 42°  ⚙︎ │  ← status + thermal/fps chips
│ ┌────────────────────────────┐ │
│ │      STAGE (program)       │ │  ← composited preview, pinch=layer
│ │  [layer chips float here]  │ │     scale, tap=select
│ └────────────────────────────┘ │
│ Transition [CUT|FADE]  A|B snap │
├────────────────────────────────┤
│ SCENES strip (thumbnails, + )  │  ← horizontal bin, drag-reorder
├────────────────────────────────┤
│ LAYERS rail (drag, eye, lock)  │
├────────────────────────────────┤
│ TOOL DOCK: 📷 🖼 🎬 🎤 🧑‍🎤 T ▦ ✨ │  ← source add + studio tools
└────────────────────────────────┘
```

**Inspector (bottom sheet, per selected layer — the "many controls" promise):** Transform (x/y/scale/rot sliders + numeric entry, fit/fill/stretch, corner radius, shadow) · Opacity + blend mode (13) · Effects chain list (+ add: color wheels, temperature/tint, brightness/contrast/sat/gamma, sharpen, blur, vignette, LUT) · Chroma key (key color pipette, tolerance/spill/shadow/highlight sliders + live scope) · Mask (shape/feather/invert) · Face engine (identity picker, strength, feather, color-match, redetect rate) · Lip sync (engine tier, sensitivity, smoothing, offset trim) · Audio (volume, pan, duck mic by X dB, mute) · Timing (speed, trim in/out, loop mode).

**Audio Mixer sheet:** 4 bus strips (Mic / Media / Music / TTS) + Master — each with fader, pan, meter (peak hold), FX button, mute/solo; global latency meter; voice FX rack as horizontal card carousel with big A/B bypass.

**Avatar Studio (guided flow):** pick source (photo/video) → auto face detect with confidence badges → identity crop/align editor → mouth rig preview (drag sliders, live mouth test with "say ahh" calibration) → voice profile record (10 s) → done → drops template scene.

**Stream panel:** target picker, key vault (biometric unlock), bitrate/res/fps presets + custom, live stats graph (bitrate/fps/dropped/RTT), reconnect policy toggle.

**Diagnostics:** FPS + frame-time histogram, GPU string, tier, thermal state, model load states, watchdog recovery log, audio latency readout — pro transparency builds trust and is our bug-triage channel.

**Principles:** every destructive action undoable (undo stack per scene); gestures mirrored on rails; no modal dead-ends; recording/streaming state visually unmissable; no watermarks anywhere, ever; empty states teach (e.g., "Add a camera layer →").

---

# F. Risks & How We Avoid Previous Failures

| # | Failure mode (seen in prior attempts / market) | Root cause | Our counter-measure (architectural, not hopeful) |
|---|---|---|---|
| 1 | **Black preview** | GLSurfaceView lifecycle, context loss, surface churn, missing transform matrices | Own EGL/render thread persistent per process; FBO-native scene graph independent of output surface; last-good-frame hold; 500 ms watchdog with auto-recovery; Diagnostics log; 20-cycle stress test as CI gate (Phase 1 exit) |
| 2 | **Broken shaders / color looks wrong** | Copy-pasted unmaintained shader code (GPUImage-era), untested YUV paths | One validated YUV→RGB path w/ golden tests per layout family; every effect shader merged only with golden-frame diff test (`:tools-dev`); AGSL ports gated on API 33+ device tests |
| 3 | **Fake builds / phantom features** ("phase complete" but nothing runs) | Progress measured in code, not on-device | Every phase ends with a **device demo checklist** + owner sign-off; CI fails on stub classes reaching release; `FakeEngine`s confined to test source sets; demos recorded and archived per phase |
| 4 | **Dependency hell** | Abandoned libs (GPUImage, old RTMP clients), version conflicts, heavy transitive deps | Tiny pinned stack (section B.10), single version BOM, downward-only module deps, no engine may add a dependency without an ADR note; protocols (RTMP) come from maintained StreamPack, never hand-rolled |
| 5 | **Model licensing blowup** | Bundling InsightFace/Wav2Lip weights = non-commercial violation | Model Manager: user-initiated download, license text shown per model, checksums, no redistribution in APK/F-Droid; MIT/Apache models bundled only; Phase 4 begins our own Apache-2.0 model track |
| 6 | **Thermal death spiral** (app kills battery, OS throttles into slideshow) | Unbounded inference at max fps | Device tiers + thermal listener auto-downshift; swap scheduler with temporal blending; sustained-performance mode; battery/thermal HUD; LITE tier keeps core features alive on weak devices |
| 7 | **Injection module breaks target apps** | Over-broad hooks, no fail-open | Per-app scope, hook allowlist, fail-open passthrough when Studio absent, module APK versioned separately from app, test matrix on Camera1/Camera2/ImageReader/WebRTC sample apps |
| 8 | **"Lip-sync" that's actually fake** | Marketing faster than tech | Honest tiering shipped in UI ("Fast" vs "AI, Beta"); owner sign-off on visible lip-mic correlation before Phase 3 closes; no claims in docs beyond what's demonstrable |
| 9 | **Play Store / policy exposure** (deepfake misuse) | No guardrails | Consent-first UX (avatar creation shows a one-time "I have permission to use this likeness/voice" acknowledgment stored locally), no celebrity preset packs, clear docs; recordings/streaming carry optional visible badge the *user* controls; no cloud, no data exfil, privacy-first telemetry (opt-in only) |
| 10 | **Scope explosion → death by 1000 features** | Building everything before anything | Locked phase gates with exit criteria; P0–P3 ranking is the contract; new ideas go to Phase 5 backlog, not into open phases |
| 11 | **Memory blowups from AI models** (OOM crashes) | Lazy load without eviction, fp32 everywhere | Model registry with LRU eviction + budget display; fp16 default; ONNX arenas sized; scene-change eviction; heap canary tests in CI |
| 12 | **RTMP edge cases** (handshake failures, key errors) | Silent failures | Stats panel surfaces every protocol event; reconnect policy with backoff; explicit error taxonomy in UI (auth vs network vs encoder) |

**Doctrine:** *Nothing ships because it compiles. It ships because it runs on the device matrix and the demo checklist passes.* Every phase inherits the previous phase's tests — regressions in the "never black" guarantee fail CI forever after Phase 1.

---

## Appendix A — Reference open-source projects we study (not vendored blindly)

- `Parasaran-Python/android-face-fusion` — proof of on-device InsightFace ONNX pipeline + EMAP extraction trick.
- `wudixxqq/Camera2Magic`, `theGoodB0rg/camera_hook` — LSPosed camera hook surfaces and per-app config patterns.
- `dimadesu/StreamPack` (fork of `ThibaultBee/StreamPack`) — streaming engine.
- `k2-fsa/sherpa-onnx` — on-device speech tooling.
- `myshell-ai/OpenVoice` + community ONNX ports — voice cloning.
- `iperov/DeepFaceLive` (archived) — UX cautionary tale + real-time pipeline concepts.
- `OpenMMLab`/`Tencent-Music` MuseTalk — Tier-2 lip model lineage (MIT).

## Appendix B — Performance budget (1080p30, FLAGSHIP tier, targets to verify in spikes)

| Stage | Budget |
|---|---|
| Camera capture → GL upload | ≤ 4 ms |
| Per-layer effects (≤4 layers) | ≤ 4 ms |
| Compositor + present | ≤ 2 ms |
| Mesh tracking | ≤ 3 ms |
| SCRFD redetect (amortized) | ≤ 3 ms/frame |
| INSwapper fp16 (20–30 fps scheduler) | 25–50 ms on worker thread |
| Viseme engine | ≤ 2 ms/frame (CPU) |
| Audio graph round-trip (mic→mix) | ≤ 40 ms |
| Encode (HW 1080p30) | async, ≤ 8 ms submit |

MID tier: swap at 15 fps + blend, scene 720p default. LITE: tracking + avatar rig only.

## Appendix C — LSPosed module protocol (sketch, Phase 3)

```
Studio foreground service (uid: app)
  └─ AIDL: IVCamPublisher.aidl { start(w,h,fps): RingHandle, stop(), getStatus() }
  └─ RingHandle → SharedMemory NV12 ring (n=4 slots) + AtomicLong sequence + fence fd
Module (in target app process, uid: target)
  ├─ binds (with signature check of Studio package)
  ├─ hooks CameraDeviceImpl.createCaptureSession → wraps ImageReader OnImageAvailable
  ├─ replaces YUV_420_888/JPEG buffers with ring slots (color-converted NV12→YUV per format)
  └─ no Studio / bind failure → passthrough (fail-open), logged
Config channel: ContentProvider in module APK, per-app scope JSON, write-protected to Studio signature
```

---

*End of blueprint. Awaiting owner approval to begin Phase 1 implementation.*
