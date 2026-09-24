# Open-Source Android Virtual-Camera Ecosystem — Code-Level Survey (Round 2)

**RESEARCH ROUND — no code, no build. Date 2026-09-24.** Companion to
`2026-09-24-vcam-ecosystem-survey.md` (map → this is the terrain).
Method: full source reads at HEAD (default branches; Camera2Magic @ `api93`), line-numbered.
Shorthands: **VCAM** w2016561536/android_virtual_cam · **YVCAM** Yaahua/vcam · **C2M**
Atomos-X/Camera2Magic (branch api93) · **CamSwap** f1sherb0y/Android-CamSwap-OpenSource ·
**wrlu** wrlu/VirtCam · **ReCam** aimardcr/ReCam · **MGR** smithluke874/Android-VirtualCam-Manager.
License caution: C2M unlicensed, CamSwap GPL-3.0 — snippets below are quoted for analysis only,
in <10-line fragments; no code reuse implied.

Line numbers below refer to the files as read on 2026-09-24 (depth-1 clones).

---

## 1. Hook signatures (concrete classes, before/after, original disposition)

### 1.1 Camera1 (android.hardware.Camera)

| Hook (signature) | Concrete target | Mode | Original | Who / cite |
|---|---|---|---|---|
| `open(int)` → Camera | public API | **after** | call through; capture instance | C2M `hook/Camera1Hook.kt:35-42` |
| `setParameters(Camera.Parameters)` | public API | before | call through; read preview/picture sizes | C2M `Camera1Hook.kt:45-61` |
| `setPreviewTexture(SurfaceTexture)` | public API | before | **replace arg** `param.args[0] = fake_SurfaceTexture` (app keeps rendering its own ST — video is played INTO it) | VCAM `HookMain.java:98-146` (esp. :126,:133); YVCAM `Camera1Handler.java:39-88`; wrlu `LegacyCameraHooker` |
| `setPreviewDisplay(SurfaceHolder)` | public API | before | **replace + block**: `mcamera1.setPreviewTexture(c1_fake_texture); param.setResult(null)` | VCAM `HookMain.java:463-505` |
| `setDisplayOrientation(int)` | public API | before | call through; forward value to native renderer | C2M `Camera1Hook.kt:85-96` |
| `startPreview()` | public API | before | **block** (`param.result = null`) + start own pipeline | VCAM `HookMain.java:359-461` (plays video, doesn't block — real camera never started from app's texture); C2M blocks at `Camera1Hook.kt:99-122` |
| `stopPreview()` | public API | before | stop decoder/players — **commented out in VCAM** (leak; cleanup happens on next `onOpened`) | VCAM `HookMain.java:639-659` (commented); C2M `Camera1Hook.kt:124-133` active |
| `release()` | public API | before | stop + release surface | C2M `Camera1Hook.kt:135-146` |
| `setPreviewCallback / …WithBuffer / setOneShotPreviewCallback(Camera.PreviewCallback)` | public API | before | steal: store callback, `param.result = null` (C2M) or hook the **callback's class** `onPreviewFrame(byte[], Camera)` and overwrite `args[0]` in-place (VCAM) | C2M `Camera1Hook.kt:148-176`; VCAM `HookMain.java:1093-1155`; YVCAM `Camera1Handler.java:239-290` |
| `addCallbackBuffer(byte[])` | public API | before | **swap arg** for fresh `new byte[same length]` (VCAM) or block (C2M) | VCAM `HookMain.java:229-236`; C2M `Camera1Hook.kt:178-185` |
| `takePicture(ShutterCallback, PictureCallback, PictureCallback, PictureCallback)` | public API | after (VCAM) / **before + block + synthesize** (C2M) | VCAM hooks the callback class `onPictureTaken(byte[], Camera)` and replaces `args[0]`; C2M nulls result and posts JPEG from NV21 snapshot on main looper | VCAM `HookMain.java:256-268`, `1013-1091`; C2M `Camera1Hook.kt:187-218` |
| `MediaRecorder.setCamera(Camera)` | public API | before | call through + **admits defeat via toast** "触发了录像，但目前无法拦截" | VCAM `HookMain.java:270-285` |

### 1.2 Camera2 — open

| Hook | Concrete target | Mode | Original | Who / cite |
|---|---|---|---|---|
| `openCamera(String, CameraDevice.StateCallback, Handler)` | `android.hardware.camera2.CameraManager` | **before** (VCAM) | call through; capture the **app's concrete StateCallback class** (`param.args[1].getClass()`) for follow-up hooks | VCAM `HookMain.java:148-180`; YVCAM `Camera2Handler.java:35-66` |
| `openCamera(String, Executor, CameraDevice.StateCallback)` | CameraManager | **after** (VCAM) | same; SDK-gated `>= P` | VCAM `HookMain.java:183-217`; YVCAM `Camera2Handler.java:68-100` |
| Follow-up: `onOpened/onError/onDisconnected` | the **app's callback class** (`findAndHookMethod(param.args[0].getClass(), …)`) | before | onOpened = pipeline (re)build point; onError/onDisconnected = log-only in VCAM | VCAM `HookMain.java:813-1008`; YVCAM `Camera2SessionHook.java:29-63`, `:198-215` |

### 1.3 Camera2 — session creation (all hooked on the concrete impl class captured at open)

| Signature | Mode | Original surfaces | Who / cite |
|---|---|---|---|
| `createCaptureSession(List<Surface>, CameraCaptureSession.StateCallback, Handler)` | before | **replace** `args[0] = Arrays.asList(c2_virtual_surface)` | VCAM `HookMain.java:860-871`; YVCAM `Camera2SessionHook.java:81-94` |
| `createCaptureSessionByOutputConfigurations(List<OutputConfiguration>, …)` | before | replace with `List(new OutputConfiguration(virtual))` | VCAM `HookMain.java:903-917` |
| `createConstrainedHighSpeedCaptureSession(List, …)` | before | replace with virtual | VCAM `HookMain.java:921-933` |
| `createReprocessableCaptureSession(InputConfiguration, List, …)` | before | replace `args[1]` | VCAM `HookMain.java:937-949` |
| `createReprocessableCaptureSessionByConfigurations(InputConfiguration, List<OutputConfiguration>, …)` | before | replace `args[0]` | VCAM `HookMain.java:954-967` |
| `createCaptureSession(SessionConfiguration)` | before | **rebuild** a fake `SessionConfiguration(type, [OutputConfiguration(virtual)], same executor, same callback)` and replace `args[0]` | VCAM `HookMain.java:971-987` |
| same set | before | C2M does **NOT** modify args: picks ONE target surface (format 0x22 IMPLEMENTATION_DEFINED, largest area, else format-1 fallback) and renders onto it concurrently (see §4) | C2M `hook/Camera2Hook.kt:104-155` (`getSurfaceListFrom` :66-83, `getTargetFrom` :86-110) |
| `setRepeatingRequest(…)` | before | target-level swap | wrlu README (hook list) — `CameraCaptureSessionImpl`; code in `hook/Camera2Hooker.java` (impl class strings at :136,:171) |
| `CameraDeviceImpl.close()` | before | stop renderer + release | C2M `Camera2Hook.kt:118-131`; commented out in VCAM `HookMain.java:873-900`; wrlu README |
| Session callbacks `onConfigured/onConfigureFailed/onClosed` | app callback class | log-only | VCAM `HookMain.java:1157-1182` |

### 1.4 Camera2 — targets

| Hook | Mode | Behavior | Who / cite |
|---|---|---|---|
| `CaptureRequest.Builder.addTarget(Surface)` | before | classify surface by `toString()` (**reader = "Surface(name=null)"**, else preview), store up to 2 of each, **replace `args[0]` with virtual surface** | VCAM `HookMain.java:507-559`; YVCAM `Camera2Handler.java:102-151` |
| `CaptureRequest.Builder.removeTarget(Surface)` | before | untrack stored surface (call through) | VCAM `HookMain.java:561-604` |
| `CaptureRequest.Builder.build()` | before | call through + trigger `process_camera2_play()` (deferred start once targets known) | VCAM `HookMain.java:606-637`; YVCAM `Camera2Handler.java:190-219` |

### 1.5 ImageReader / ImageWriter

| Hook | Mode | Behavior | Who / cite |
|---|---|---|---|
| `ImageReader.newInstance(int,int,int,int)` (+5-arg) | before (VCAM: record w/h/format + toast) / after (wrlu: swap reader behavior) | VCAM only records dims (format 256=JPEG else NV21 for frame pump); wrlu wraps instances | VCAM `HookMain.java:661-678`; wrlu `Camera2Hooker.java:78-100` |
| `acquireNextImage` + `$SurfaceImage.getPlanes` | — | plane-level rewrite (keeps reader identity — untracked surfaces keep working) | wrlu README + `hook/ImageHookResource.java` |
| `ImageReader.close` | before | cleanup | wrlu `Camera2Hooker.java:104-108` |
| CamSwap's approach | — | **keep reader**, pump YUV into it: `registerImageReaderSurface()` + per-reader `YuvCallbackPump` (incl. a WhatsApp-specific map), `shouldSkipImageReaderTracking()` for CameraX-style RGBA readers | CamSwap `Camera2SessionHook.java:105, :398, :469, :559-580` |
| `ImageWriter` | — | **nobody hooks it** (unchanged from survey 1) | — |

### 1.6 Bootstrap / lifecycle / mic

| Hook | Mode | Purpose | Who / cite |
|---|---|---|---|
| `Instrumentation.callApplicationOnCreate(Application)` | after | context capture, permission probe, **video_path resolution** (DCIM vs app-private), toast plumbing — earliest stable point | VCAM `HookMain.java:287-357`; YVCAM `HookMain.java` (delegates) |
| `Application.onCreate` | after | context + native init + hook install | C2M `MagicEntry.kt:44-58` |
| `Activity.onResume` | after | config re-read (`updateVideoSource()`) + float-window toggle | C2M `MagicEntry.kt:61-71` |
| `AudioRecord.read(byte[],int,int)` / `read(byte[],int)` / `read(short[],int,int)` | before | mic mute/replace; each overload separately wrapped, failures isolated | YVCAM `MicrophoneHandler.java:39-98`, `:130-133` |
| native: `Camera3OutputStream::returnBufferCheckedLocked` (`_ZN7android7camera319Camera3OutputStream25returnBufferCheckedLocked…`, `elfsym.cpp:21`) | inline hook | overwrite gralloc buffer contents, then run original | ReCam `payload/framehook.cpp:1-50`, `:115-181` |
| native: ART `RegisterNatives`/`jniRegisterNativeMethods` table swap (match by `name` + JNI sig) | replace fnPtr | Camera1 natives: `setPreviewTexture` (ST swap), `setPreviewDisplay` (sig-matched `Landroid/view/Surface;`), start/stopPreview | MGR `zygisk-native/jni/jni_camera_hooks.cpp:4-5, :182-208, :245-251, :308` |

---

## 2. Rotation — what each repo actually does (the differentiator map)

| Repo | Where rotation is computed | Mechanism | Front mirror | Device rotation | Known-broken (issues) |
|---|---|---|---|---|---|
| VCAM | **Nowhere** | none — user bakes rotation into `virtual.mp4` | user bakes flip ("水平翻转并右旋90度", README Q1) | none | WhatsApp 90° + B/W (issue); resolution mismatch garbles |
| YVCAM | none automatic | **manual**: notification `ACTION_ROTATE` ±90° | same manual | none (config-level only) | inherits VCAM issues; pitfall log has none on rotation (still manual) |
| CamSwap | video metadata rotation read at render | **GLES matrix at draw time** (`GLVideoRenderer`/`GLHelper`) + same manual rotate action | not modeled | display not folded | (closed, 0 issues — young repo) |
| C2M | shader stage: "Apply Video Rotation Matrix — sensor / display / metadata" (workflow.md §3) | GL transform in RGBA pipeline; `setDisplayOrientation` hook + `manually_rotate` pref feed native | forced NV21 "visual upright" conversion for Camera1 byte[] (README progress) | `getDisplayOrientation()` read per session (`Camera2Hook.kt:46-63`) — **only at session create, no config-change re-read** | open: "Camera2 target preview rotates portrait image in Instagram/Facebook" |
| wrlu | none visible in hook layer | — | — | — | n/a (study project) |
| ReCam | **not needed by design** — replaces pixels at sensor-output level; capture metadata stays real so apps apply their own transforms | gralloc YUV overwrite; feed is expected to look like sensor output (operator bakes orientation) | n/a (sensor plane) | n/a | none reported; untested QC/Exynos gralloc |
| MGR | none | MediaPlayer plays file as-is | none | none | pre-verification |
| **ours** | `StOrientation.classify` (corner-probe ST matrix read) → `compensate(cls, desiredMirrorH, display)` | **UV/geometry in our engine**; rot+mirror closed form; display fold re-read per config change | `desiredMirrorH` XOR in formula | folded into `desired_net.rot`, re-run on every config change | r24/r25 residuals fixed in r26; golden-256 holds laws |

**Provable deltas vs community:** (1) nobody classifies the ST **matrix** — VCAM family never
reads it (user bakes), C2M trusts `SENSOR_ORIENTATION` + metadata, so any app-side crop/flip ST
defeats them silently; (2) nobody folds display rotation **per config change** (C2M reads it once
per session — its IG/FB portrait bug is exactly that); (3) nobody has a frame-free law set or a
256-case harness; (4) front-mirror handling is either absent (VCAM/YVCAM/CamSwap manual) or
hardcoded (C2M "force visual upright").

---

## 3. Transport internals (classes, formats, pacing)

| Repo | Decode | Output per consumer | Conversion | Pacing/backpressure |
|---|---|---|---|---|
| VCAM | `MediaPlayer` per surface (loop, `prepare()`, volume-0 unless `no-silent.jpg`): up to **4 instances** — `mplayer1` (SurfaceView), `mMediaPlayer` (app ST), `c2_player`, `c2_player_1` | preview surfaces: EGL/compositor via MediaPlayer; **reader surfaces: separate `VideoToFrames` MediaCodec decode per reader**, output JPEG (reader fmt 256) or NV21 | `VideoToFrames`: `MediaCodec.createDecoderByType` + `COLOR_FormatYUV420Flexible` → `Image` plane extraction to NV21 (`VideoToFrames.java:30-33, 214, 254-298`); photo path `Bitmap→rgb2YCbCr420` BT.601 (`HookMain.java:1191-1216`) | none — `onPreviewFrame` **busy-spins** `while (data_buffer == null) {}` on the camera callback thread (`HookMain.java:1119, 1147`); no queue, decode restart per consumer per open |
| YVCAM | `SurfacePlayerBackend` impls: `AndroidMediaPlayerBackend` (file) / `ExoPlayerBackend` (RTSP/RTMP/HLS) + `MediaCodecYuvDecoder` (stub, 12 lines) | same shape as VCAM via `SharedState` + `SurfaceRelay` (`SurfaceRelay.java`, 43 lines) | inherits `VideoToFrames` (ported) | `MediaPlayerManager` + `HookGuards.isDisabled()` three-layer stop (pitfall 3 fix); no frame-queue backpressure |
| CamSwap | same skeleton + `ExoPlayerBackend`, `BytePool`, `OverlayControlService` | **deferred playback** (build-before-addTarget ordering fixed, `Camera2SessionHook.java:119, 367, 568`); WhatsApp YUV pump per reader | plane-rewrite pump keeps reader format | per-reader pumps; still no global pacing |
| C2M | **native**: FFmpeg demux → `AMediaCodec` hw decode → OES SurfaceTexture → shader → RGBA → `eglSwapBuffers` to app surface; separate NV21 transcode thread | RGBA (preview), NV21 (`cachedBuffer` + `onFrameDataUpdated` → `camera1Callback.onPreviewFrame` + `previewCallback` for C2, `MagicNative.kt:66-88`), JPEG synth (`nv21ToJpegByteArray`, `:107`) | GPU: triple-PBO async `glReadPixels` → NV21 (workflow.md §4); CPU: NV21→Bitmap preview **throttled 200 ms** (`PreviewNV21Helper.kt:17-27`) | 5 threads (demuxer/video/audio/render/NV21) + `ThreadSafeQueue` with blocking-when-full backpressure (workflow.md §§1-2); double-buffer ping-pong decode |
| ReCam | **outside cameraserver**: `recam_feed` process — FFmpeg/h264 → fixed-point bilinear resample to each lane size (`feed/scale.cpp:9-25+`) → shm ring | writes **actual gralloc layout** per buffer (`bufferlock_acquire` ANW lock or handle lock; IMPL_DEFINED only if driver can describe — `framehook.cpp:21-25, 162-170`) | YUV plane memcpy into locked buffer; JPEG never (sensor-level) | 4 size-keyed lanes, linear lane lookup per HAL callback, race-safe claim, **>2 s without frame → pass-through fallback** (`shmsource.cpp:87-143`) |
| MGR | `MediaPlayer` on stolen app surface (v2.1 primary); AMediaCodec+OES upload = fallback; Zygisk `gl_hooks.cpp` `glBindTexture` re-draw = deeper fallback | NV21 n/a yet | — | none (status-file state machine instead) |

---

## 4. C2M native pipeline (highest-value target)

**Binary .so NOT obtained** — release-asset host egress-blocked from this sandbox (6× EOF on
`CAM2Magic-1.1.2-arm64-v8a.apk`); repo ships no `cpp/`/`CMakeLists` (`find` = 0 native files).
Everything below is from the Kotlin JNI surface + `document/workflow.md` + README claims —
**inferred where marked**.

Verified JNI surface (`hook/MagicNative.kt` `external fun`s — these ARE the .so exports):
`registerSurface(apiLevel:Int, cameraId:String, sensorOrientation:Int, pictureWidth:Int,
pictureHeight:Int, packageName:String, displayOrientation:Int, surface:Surface)` (:104-105) ·
`setDisplayOrientation(Int)` (:106) · `getSurfaceInfo(Surface):IntArray` (:108 — returns
`[w,h,format]`, used by the format-34 picker `Camera2Hook.kt:90-108`) · `resetVideoSource()` (:110) ·
`processVideo(fd:Int, offset:Long, length:Long):Boolean` (:112) · `needStopRenderer()` (:113) ·
`needStartRenderer()` (:114) · `nv21ToJpegByteArray(NV21, w, h, quality=90)` (:115) ·
`updateNativeConfig(playSound, enableLog, manuallyRotate)` (:102).

Pipeline (workflow.md, all stages): demuxer thread (`AMediaExtractor`/FFmpeg) →
`ThreadSafeQueue<VideoPacket>` w/ blocking backpressure → `AMediaCodec` decode into
SurfaceTexture/OES → "Texture Copier" shader → RGBA texture + rotation matrix →
render thread `draw_frame_rgba` → `eglSwapBuffers` on the app surface; NV21 thread: frame N-1 →
**triple-buffered PBO** → async `glReadPixels` → map → NV21 out; audio: `AAudio/AudioTrack`
separate thread. README: 4K60 HEVC smooth on SM8250; "green lines on video edges" fixed
(buffer-stride bug, inferred).

**Architecture fact that matters (from code, not inference):** C2M does NOT remove the camera
from the session — `camera2Hook` calls through with original surfaces and registers the chosen
preview surface for **concurrent GL rendering** (`Camera2Hook.kt:117-155`: no arg mutation,
`registerSurfaceIfNew` + `needStartRenderer`). Two producers drive one BufferQueue; the visible
frame is whichever producer wrote last — plausibly the root of its "all apps black screen" and
"ColorOS16 photo black" issues (two open issues say exactly that). VCAM/YVCAM instead **replace**
the session surfaces, so the real camera HAL writes into a discarded private SurfaceTexture
("垃圾场", `HookMain.java:800-810`) — cleaner isolation, at the cost of decoding video 1× per
consumer surface.

---

## 5. Per-app quirk table (proven entries, with sources)

| Package/app | Symptom | Root cause | Fix in source | Cite |
|---|---|---|---|---|
| `tv.danmaku.bili` (Bilibili) | camera broken: ANativeWindow gets 1×1 buffer | app passes 1×1 SurfaceTexture | `st.setDefaultBufferSize(previewW, previewH)` in `setPreviewTexture` hook | C2M `Camera1Hook.kt:71-83` (hardcoded) |
| WeChat-family attendance (打卡) apps | photo replaced but real photo ALSO appears (overlap) when camera scope on | photo path runs beside real still pipeline | unresolved upstream; needs photo-mode detection | C2M issue "某个打卡APP不能替换照片，勾选相机的话会出现重叠" |
| DingTalk (钉钉) | attendance 打卡 works, 签到 doesn't | different in-app camera path per feature | none (user report) | VCAM issue "钉钉打卡能用 钉钉签到不能用" |
| TikTok | decoder threads keep running after menu switch (POST↔TEMPLATES) | missed `close()` signal on session switch | hook `CameraDeviceImpl.close` + renderer stop | C2M README known-issues (fixed); `Camera2Hook.kt:118-131` |
| QQ | whole hook chain dies: ConfigWatcher never init | `AudioRecord.read` signature change → `NoSuchMethodError` (an `Error`) escaped `catch(Exception)`, aborted `handleLoadPackage` | per-submodule `try/catch(Throwable)` isolation | YVCAM `PITFALL_LOG.md` pitfall 5; `MicrophoneHandler.java:130-133` |
| WhatsApp | preview fine but YUV ImageReader frames stale | CameraX-style reader needs explicit frame pump | per-reader `YuvCallbackPump` + `whatsappYuvPumpMap` | CamSwap `Camera2SessionHook.java:105, :432` |
| Instagram/Facebook | portrait video rendered rotated (C2 target) | display orientation read once at session create, not on config change | unresolved | C2M issue "Camera2 target preview rotates portrait image in Instagram/Facebook" + `Camera2Hook.kt:46-63` |
| Dual-app/sandbox clones | `virtual.mp4` auto-renamed with numeric suffix → path lost | clone sandboxes rewrite external files | re-resolve by name/content, not absolute path (recommendation) | VCAM issue "每次双开启动…随机添加数字重命名" |
| System camera / WebView mini-programs | never replaced | out-of-process camera entry points | declared non-goal | VCAM closed issues |
| Any app w/o storage permission | video not found | scoped storage hides DCIM | private-dir redirect `/Android/data/<pkg>/files/Camera1/` | VCAM `HookMain.java:319-344`; YVCAM `HookGuards.getVideoFile` |

---

## 6. Failure modes → code patterns (survey-1 §4 mapping)

| Failure (survey §4 #) | Preventing pattern in code | Quote/cite |
|---|---|---|
| #14 signature drift kills chain | per-submodule isolation around every init step | YVCAM `HookMain.java:139-142`: `try { MicrophoneHandler.init(lpparam) } catch (e) { log }` (comment: 失败不影响) — pattern, GPL-sourced repo is CamSwap; YVCAM MIT |
| #3 lifecycle stall / watchdog | teardown hooks are short, non-blocking; but VCAM shipped the bug: stopPreview commented out, players released only on next `onOpened` (`HookMain.java:815-845`) — matches the OneUI watchdog report | VCAM `HookMain.java:639-659, 815-845` |
| #2/#17 safe-degrade | ReCam: unsupported format/lock failure ⇒ `skip` + pass real frame; un-fed hook shows color bars, not black (`g_bars` fallback). C2M: `isReadyForHook()` gate = real camera when no video (`MagicNative.kt:120-122`) | ReCam `framehook.cpp:76, 154-181`; C2M gate |
| #18 cold-start config race | bootstrap-time config resolution at `callApplicationOnCreate` + CamSwap provider preload | VCAM `HookMain.java:287-357`; CamSwap `ConfigManager.java:158-216` (provider query → file fallback chain) |
| #4 impl-class drift on new APIs | concrete-class capture at open (`param.args[1].getClass()`), SDK-gated variants — still broke on A15/16 (VCAM stale); C2M avoids impl drift by NOT swapping surfaces | VCAM `HookMain.java:176-178, 902, 936, 953, 970` |
| #6 detection | VCAM's toasts are a detection surface (every hook toasts dims/paths) — suppressible only via `no_toast.jpg` (`HookMain.java:135-143`); C2M logs gated behind `enableLog` pref | cited lines |
| #9/#10 user-side resolution/paths | all four Java-lineage repos demand user-side matching (toast "需要视频分辨率与其完全相同", `HookMain.java:1133`); only C2M (aspect-crop + rotation in shader) and ReCam (resample to lane size) remove the user burden | cited |
| #15 config notify dead channels | YVCAM: 3 channels (ContentObserver on `IpcContract.URI_CONFIG` `ConfigWatcher.java:45-60`; `FileObserver` on config file; broadcast `ACTION_UPDATE_CONFIG` `IpcContract.java:17`) + **mtime polling fallback** on every `HookGuards.getConfig()` call (`HookGuards.java:56-61`) | cited |
| — (new) consumer multiplicity | VCAM decodes once per consumer surface (2 players + 2 frame-pumps); C2M decodes once, renders N (its RGBA is fan-out) — the scalability argument for a single-decode engine | §3 table |

---

## 7. Configuration & scope mechanics (runtime)

| Repo | Scope at runtime | Config store | Change propagation (no restart) |
|---|---|---|---|
| VCAM | LSPosed scope; per-app dir override (private `Camera1/`) | **file presence only**: `virtual.mp4`, `1000.bmp`, `disable.jpg`, `no-silent.jpg`, `no_toast.jpg`, `force_show.jpg`, `private_dir.jpg` | re-`File.exists()` on every hook call — zero IPC, inherently live |
| YVCAM | + LSPatch (scope by construction) | JSON config in `ConfigManager.DEFAULT_CONFIG_DIR` + sentinel files | ContentObserver(`URI_CONFIG`) → FileObserver(200 ms-degraded) → broadcast; mtime poll each `getConfig()` |
| CamSwap | per-app filter in module UI | `VideoProvider` ContentProvider (`IpcContract`-style) **streaming config + video PFD without target storage permission**; file fallback | `notifyChange(URI_CONFIG)` (`ConfigManager.java:464`) + prewarm at cold start |
| C2M | LSPosed scope | `XSharedPreferences("com.nothing.camera2magic","virtual_camera_x_prefs")` keys `video_id/module_enabled/play_sound/enable_log/inject_menu/manually_rotate` (`MagicNative.kt:18-25`) | `prefs.reload()` in `refreshPrefs()` + `updateNativeConfig` JNI, driven by `Activity.onResume` → `updateVideoSource()`; video fd re-resolved via MediaStore ID → `openAssetFileDescriptor` **inside target** (`MagicNative.kt:199-200`) |
| MGR | Magisk flags (root) | `/data/adb/virtualcam/` control dir; `DCIM/Camera1/.vcam_*` status mirrors | libsu shell from Manager APK; status files polled by UI |
| ReCam | none (global) | CLI + status socket (`injector/status.cpp`) | `recam_stop` unhook; feed switchover via ring |

---

## 8. What nobody has (Phase 3 differentiation list)

1. **ST-matrix classification** — corner-probe the actual SurfaceTexture matrix (ours) vs
   sensor-orientation trust (C2M) vs nothing (VCAM family). Every crop/flip-producing app path
   silently defeats the community; ours classifies per bind/ST-change.
2. **Display-rotation fold on every config change** — C2M reads rotation only at session create
   (its IG/FB bug); VCAM family never folds. Ours re-reads per config change + re-runs comp().
3. **A proven closed-form rot+mirror compensation with a law harness** — nobody else has any
   frame-free law, let alone 256 cases. Our golden harness is the differentiator to advertise.
4. **Single-decode multi-consumer fan-out in-process** — C2M has it natively (RGBA fan-out +
   PBO NV21); VCAM family re-decodes per consumer. Our engine-render already has the
   single-GL-context multi-layer shape; porting = reuse, not rebuild.
5. **Photo/still path done cleanly** — VCAM: BMP file swapped into callbacks (no metadata, wrong
   EXIF, no size match); C2M: JPEG from NV21 snapshot (better); CamSwap: CameraX ImageProxy
   synthesis via reflection (best coverage, GPL). A MIT-clean CameraX still path remains a gap.
6. **Recording** — literally one toast upstream (VCAM :279). Any real `MediaRecorder.setCamera`
   handling would already lead the field.
7. **Graceful "hook lived but frame not ready" pacing** — nobody has backpressure into the
   consumer's cadence; VCAM busy-spins, C2M double-buffers only internally.
8. **Data-driven quirk registry** — quirks are hardcoded `if` (Bilibili) or GitHub issue lore;
   nobody ships a table.

### B.7 amendments — round 2 (extends/supersedes survey-1 §7)

- **A1 (supersedes amend-1 detail):** session-variant replacement should follow VCAM's exact
  arg-surgery per variant (incl. rebuilding `SessionConfiguration` preserving
  type/executor/callback, `HookMain.java:977-983`), on the concrete impl class captured at
  `openCamera` — and keep C2M's lesson in mind: do NOT double-produce onto the app surface.
- **A2:** adopt `CaptureRequest.Builder.build()` deferred-start ordering (CamSwap's
  build-before-addTarget fix) — not just addTarget tracking.
- **A3:** reader handling: prefer CamSwap's keep-reader + YUV pump (identity-preserving) over
  VCAM's blind replace; gate per-package (their `shouldSkipImageReaderTracking`).
- **A4:** mic submodule: hook all three `AudioRecord.read` overloads, each in its own
  `catch(Throwable)`, isolated init (YVCAM signatures at `MicrophoneHandler.java:39-98`).
- **A5:** config: sentinel files stay the LSPatch fallback; primary = provider with mtime-poll
  fallback on every config read (YVCAM `HookGuards.java:56-61`) — poll makes notify loss
  non-fatal.
- **A6:** quirk registry seeded from §5 (10 entries above), data-driven, first two rows =
  Bilibili 1×1 ST and WeChat photo-overlap detection.
- **A7:** NOT adopted: concurrent-producer rendering (C2M), busy-wait frame sync (VCAM
  `:1119`), user-baked rotation (all), toasts (all).

*Inference labels: §4 C2M native internals = source-surface + workflow-doc inference (binary
unobtainable, egress-blocked); ReCam device coverage claims = README-sourced, single-device.*
