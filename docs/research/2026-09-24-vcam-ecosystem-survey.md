# Open-Source Android Virtual-Camera Ecosystem Survey

**Round: RESEARCH (no code, no build). Date 2026-09-24.**
Sources: GitHub repo search "android virtual camera" (+ xposed/fake-camera variants), full source
reads of Atomos-X/Camera2Magic @ api93, w2016561536/android_virtual_cam, Yaahua/vcam,
aimardcr/ReCam, smithluke874/Android-VirtualCam-Manager, f1sherb0y/Android-CamSwap-OpenSource
(README), wrlu/VirtCam (README hook list), VCAMSX lineage (search-level), issue trackers of
android_virtual_cam (60 most recent) and Camera2Magic (all 11).
Purpose: feed Phase 3 (`:vcam-module`) with what is already proven on real devices.

Repo shorthand: **VCAM** = w2016561536/android_virtual_cam (the ★2059 original) · **YVCAM** =
Yaahua/vcam (modernized MIT VCAM fork) · **C2M** = Atomos-X/Camera2Magic · **ReCam** =
aimardcr/ReCam · **MGR** = smithluke874/Android-VirtualCam-Manager · **CamSwap** =
f1sherb0y/Android-CamSwap-OpenSource · **wrlu** = wrlu/VirtCam.

---

## 1. Hook surface (the allowlist)

Nobody needs to invent methods — the union below is what every working device-proven module
intercepts. "Block" = suppress original (param.result = null) and synthesize.

### Camera1 — android.hardware.Camera (all API 9+, deprecated 21 but universal)

| Method | Hooked by | Notes |
|---|---|---|
| `open(int)` / `open()` | C2M, MGR | capture instance ref (after); VCAM skips it and keys per-instance later |
| `setPreviewTexture(SurfaceTexture)` | VCAM, YVCAM, CamSwap, C2M, MGR, wrlu | THE swap point: app gets fake ST fed by our frames, real camera gets a dummy |
| `setPreviewDisplay(SurfaceHolder)` | VCAM, YVCAM, C2M, wrlu | SurfaceView-path equivalent of the above |
| `setParameters(Parameters)` | C2M | read preview/picture sizes → resolution negotiation |
| `setDisplayOrientation(int)` | C2M | capture the app's own rotation decision, feed the renderer |
| `startPreview()` | VCAM (block), C2M (block + start renderer) | video pipeline trigger |
| `stopPreview()` / `release()` | C2M, (wrlu implied) | lifecycle: stop decoder, release surface (TikTok leak lesson) |
| `setPreviewCallback` / `setPreviewCallbackWithBuffer` / `setOneShotPreviewCallback` | VCAM, YVCAM, C2M, wrlu | steal consumer callback, block original |
| `addCallbackBuffer(byte[])` | VCAM, C2M | block/swallow (we own the buffer now) |
| `onPreviewFrame(byte[], Camera)` (callback impl) | VCAM, YVCAM | inject NV21 into the app's own callback |
| `takePicture(4 args)` | VCAM/YVCAM (after→swap JPEG), C2M (block + JPEG synth from NV21) | photo path |
| `onPictureTaken(byte[], Camera)` (callback impl) | VCAM, YVCAM | byte-level photo replacement |
| `MediaRecorder.setCamera(Camera)` | VCAM | video-recording path (gap in others!) |

### Camera2 — open variants (API 21+)

| Method | Hooked by | Notes |
|---|---|---|
| `CameraManager.openCamera(String, StateCallback, Handler)` | VCAM, YVCAM, CamSwap | capture CameraDeviceImpl from callback arg, then hook its concrete class |
| `openCamera(String, Executor, StateCallback)` | VCAM, YVCAM, CamSwap | API 28+ overload — hook BOTH or modern apps fall through |

### Camera2 — session creation (hook the concrete impl class obtained at open)

| Method | Hooked by | Min API |
|---|---|---|
| `createCaptureSession(List, StateCallback, Handler)` | VCAM, YVCAM, C2M, wrlu | 21 (deprecated 28, still alive) |
| `createCaptureSessionByOutputConfigurations(List, …)` | VCAM, YVCAM | 23 |
| `createConstrainedHighSpeedCaptureSession(List, …)` | VCAM, YVCAM | 23 |
| `createReprocessableCaptureSession(InputConfiguration, List, …)` | VCAM, YVCAM | 23 |
| `createReprocessableCaptureSessionByConfigurations(…)` | VCAM, YVCAM | 23 |
| `createCaptureSession(SessionConfiguration)` | VCAM, YVCAM, C2M | 28 |
| `CameraCaptureSessionImpl.setRepeatingRequest(…)` | wrlu | swap targets at repeating level, 21+ |
| `CameraDeviceImpl.close()` | C2M, wrlu | lifecycle stop; impl-class hook = brittle, re-verify per API |

### Camera2 — target add/remove + callbacks

| Method | Hooked by | Min API |
|---|---|---|
| `CaptureRequest.Builder.addTarget(Surface)` | VCAM, YVCAM, CamSwap, wrlu | 21 — swap real surface → virtual |
| `CaptureRequest.Builder.removeTarget(Surface)` | VCAM, YVCAM | 21 |
| StateCallback `onOpened` / `onError` / `onDisconnected` | VCAM, YVCAM | 21 — instance capture, error suppression |
| Session StateCallback `onConfigured` / `onConfigureFailed` / `onClosed` | VCAM, YVCAM | 21 |

### ImageReader / ImageWriter

| Method | Hooked by | Min API | Notes |
|---|---|---|---|
| `ImageReader.newInstance(w,h,fmt,max)` | VCAM, YVCAM, wrlu | 19 (camera 21+) | swap reader for virtual frames (analysis/still streams) |
| `acquireNextImage` + `SurfaceImage.getPlanes` | wrlu | 21+ | content-level plane rewrite (keeps reader identity) |
| `ImageReader.close` | wrlu | 21+ | cleanup |
| `ImageWriter` | **nobody** | 23+ | accepted gap; reprocess-input apps are rare in social/attendance space |

### WebRTC / third-party consumers

| Finding | Evidence |
|---|---|
| No repo hooks `org.webrtc` directly — **not needed** | TikTok, WhatsApp, Telegram, LINE, Messenger, WeChat all reached via Camera1/2 hooks; libwebrtc enumerators call public Camera APIs underneath |
| CameraX = Camera2 under the hood; only the **still-photo** path needed explicit help | CamSwap generates JPEG from the current replacement frame for CameraX capture (memory / file / MediaStore) |
| CameraX hooks live in app-bundled (non-framework) classes | hookable via the app classloader after init — CamSwap proves it works |

### Legacy / bootstrap / other layers

| Method | Hooked by | Notes |
|---|---|---|
| `Instrumentation.callApplicationOnCreate(Application)` | VCAM, YVCAM | earliest reliable bootstrap: dir redirect, permission probe, config prewarm (fixes cold-start race) |
| `Application.onCreate` | C2M | bootstrap + native init + Kotlin hook install |
| `Activity.onResume` | C2M | config re-read + floating panel toggle per resume |
| `AudioRecord.*` (mic) | YVCAM (MicrophoneHandler, NativeAudioHook) | mute / replace / sync-to-video modes; the `catch(Throwable)` lesson came from here (QQ signature change) |
| `cameraserver` inline hook: `Camera3OutputStream::returnBufferCheckedLocked` | ReCam | API 30+; gralloc lock + YUV plane overwrite; **metadata never touched**; pass-through when layout unknown |
| Zygisk native: JNI `RegisterNatives` intercept + ShadowHook `glBindTexture` fallback | MGR | Camera1-only today, device-unverified |

---

## 2. Transport (frames from source to the hooked process)

| Project | Mechanism | Source → frames | Per-consumer formats | Accepted tradeoffs |
|---|---|---|---|---|
| VCAM / YVCAM / MGR | **MediaPlayer looped onto the stolen app surface** (in target process) | `virtual.mp4` from a filesystem convention | EGL/OES draw for preview; CPU NV21 for Camera1 byte[]; BMP→JPEG photos | least code, hw codecs free; color/rotation/sync quirks (90° + B/W reports); resolution must match or garble |
| YVCAM extras | additional player backends | RTSP/RTMP/HLS network sources | same | more surface area; still per-app file semantics |
| CamSwap | ExoPlayer-based backend | RTSP/HLS/DASH/RTMP/RTP + local | same | heavier deps (GPL tree) |
| C2M | **fully in-process native pipeline**: FFmpeg demux → AMediaCodec hw decode (4K60 HEVC proven) → OES → RGBA → EGL to app surface; async GPU NV21 via triple PBO; AAudio audio | MediaStore fd resolved **inside the target process** (requires target media-read permission) | RGBA draw (preview), NV21 (byte[] callbacks), JPEG synth (photos) | max control incl. aspect-correct crop + rotation matrix; requires target permission; native .so per ABI |
| ReCam | **shared-memory ring** (4 size-keyed lanes), feed process external to cameraserver | file / H.264-over-TCP (adb forward) / RTMP listener / webcam | writes into each buffer's **actual gralloc layout** (IMPLEMENTATION_DEFINED read from driver) — zero format guessing | root+ptrace only; but decode never runs in the server; reversible without restart |
| CamSwap config side | **ContentProvider PFD streaming** module→target | — | — | works WITHOUT target storage permission on A11+ scoped storage; file fallback + cold-start prewarm |

Key insight: **nobody streams pixels cross-process into app-level modules** — app-level modules
decode in the target process (file/fd/ContentProvider-PFD as source). Cross-process pixel transport
(shmem ring) appears only in the server-injection architecture (ReCam).

---

## 3. Per-app scoping

| Project | Scope decision | Convention / notes |
|---|---|---|
| VCAM | LSPosed per-app scope (explicitly "no system framework needed") | dir `/DCIM/Camera1/` global; per-app override `/Android/data/<pkg>/files/Camera1/`; `private_dir.jpg` forces private mode; `disable.jpg` global kill; `no-silent.jpg`, `no_toast.jpg`, `force_show.jpg` flags |
| YVCAM | LSPosed scope; **LSPatch = scope by construction** (module embedded in target APK) | same file convention + config watcher (ContentObserver URI / FileObserver 200ms / broadcast) |
| CamSwap | per-app filter in module UI | ContentProvider config (no target storage permission needed) + file fallback + Application cold-start prewarm |
| C2M | LSPosed scope selection | XSharedPreferences (`virtual_camera_x_prefs`) read by hooked process; video via MediaStore ID; per-app quirks hardcoded (Bilibili) |
| MGR | Magisk flag files, no framework | `/data/adb/virtualcam/` control dir + `DCIM/Camera1/.vcam_*` status mirrors; Manager APK via libsu |
| ReCam | **none — global substitution** | every camera client on the device sees the feed |

**Without-IPC fallback (LSPatch-relevant):** pure file-presence signaling (VCAM convention) works
under LSPatch because the module runs inside the target and reads its own storage — no provider,
no broadcast, no prefs provider needed. Sentinel-file flags are also runtime-live without any
notification channel when each hook operation re-checks the filesystem.

---

## 4. Failure modes observed in the wild (design-around list)

| # | Symptom | Source | Lesson for `:vcam-module` |
|---|---|---|---|
| 1 | Rotation: IG/FB show portrait video rotated (C2M); WhatsApp 90°+B/W (VCAM user) | C2M, VCAM | rotation is THE recurring defect community-wide; our comp()/golden-256 work is the differentiator — port it into the module |
| 2 | Black screen in all apps / ColorOS16 photo black | C2M issues | safe-degrade: any hook failure must pass the REAL camera through, never black |
| 3 | OneUI 6.1 **system Watchdog reboot** exiting landscape apps | VCAM issue | lifecycle hooks (stopPreview/release/close) must not hold locks/block — stop fast, never join decoder threads on the hooked thread |
| 4 | Not working on Android 15/16 | VCAM (stale since 2024) | impl-class hooks (`CameraDeviceImpl`, `CameraCaptureSessionImpl`) drift per API — resolve lazily, verify per API level |
| 5 | "Triggered recording, cannot intercept" | VCAM | recording path is the known gap: hook `MediaRecorder.setCamera` (C1); Camera2 recording surfaces need addTarget-swap coverage |
| 6 | Module detected by target app | VCAM issue | no toasts/bubbles/floating buttons by default; keep frame cadence + sizes exactly as requested |
| 7 | System camera + mini-programs (小程序) can't be replaced | VCAM closed | system-camera/WebView-intent paths are out of hook reach — declare non-goal |
| 8 | Dual-app (工作空间) sandboxes rename `virtual.mp4` → path breaks | VCAM issue | config must re-resolve by content ID, not absolute path; normalize absolute-vs-relative matching (YVCAM pitfall 1) |
| 9 | Nested `Camera1/Camera1` user error → black screen | VCAM FAQ | deterministic dir resolution + logged effective path |
| 10 | Resolution mismatch → garbled/black; "match the toast resolution" UX | VCAM FAQ | negotiate sizes from `setParameters`/session surfaces and scale/crop decode output — never demand user-side matching |
| 11 | Bilibili passes a 1×1 SurfaceTexture | C2M | per-app quirk table; `setDefaultBufferSize` fix, data-driven not hardcoded |
| 12 | TikTok menu-switch misses the close signal → decoder thread leak | C2M fixed | hook `close()` AND defensive idleness reaping |
| 13 | Runtime camera permission + 4K → audio desync/pixelation | C2M open | cap decode load; ping-pong buffers; test at target-requested sizes |
| 14 | QQ `AudioRecord.read` signature change → `NoSuchMethodError` killed the WHOLE hook chain | YVCAM pitfall 5 | `catch (Throwable)` everywhere; per-submodule init isolation — one handler's failure must not skip the rest of `handleLoadPackage` |
| 15 | Config save from UI silently no-op'd (Context-less notify); disable switch left players running; 3 config channels all dead | YVCAM pitfalls 2–4 | single canonical notify path with context; disable must `stopAllPlayers()` at 3 layers |
| 16 | WeChat attendance: photo replaced but overlaps real photo when camera scope checked | C2M issue | photo path needs its own mode detection, not scope-driven |
| 17 | Qualcomm/Exynos gralloc untested (ReCam) → **passes real frames through when layout unknown** | ReCam | safe-degrade principle again; feature-probe, then degrade |
| 18 | Cold start: config not ready before first openCamera | CamSwap (fixed) | prewarm config at `Instrumentation.callApplicationOnCreate` |
| 19 | MTK codec issues | VCAM issue | fall back MediaPlayer↔MediaCodec per vendor |
| 20 | LSPatch repack breaks integrity-checked apps (banking) | community common | document per-app compatibility expectations, don't promise |

---

## 5. License + build posture

| Repo | License | APKs published | Framework | Non-root path | Min Android | Active | Notes |
|---|---|---|---|---|---|---|---|
| w2016561536/android_virtual_cam ★2059 | **MIT** | yes (releases `42-4.4` etc.) | Xposed/LSPosed | no | 5.0 | code stale 2024-06; issues live | the canonical reference; 109 open issues |
| Yaahua/vcam ★28 | **MIT** (VCAM copyright) | none (Actions) | Xposed/LSPosed/**LSPatch**/EdXposed | **yes (LSPatch)** | 5.0 | pushed 2026-09-22 | mic hooks, network streams, Compose UI, pitfall log |
| Atomos-X/Camera2Magic ★138 | **NONE** | yes (v1.1.2 arm64+v7) | LSPosed (root) | no | 10 | pushed today | **native .so sources not in repo**; api93/api101/magisk branches |
| aimardcr/ReCam ★25 | **MIT** | CLI/desktop zips (native tool, no APK) | none — ptrace into cameraserver | no (root+adb) | 11 (arm64) | pushed 2026-09-22 | verified on ONE device (A16 MTK/PowerVR); QC/Exynos untested |
| smithluke874/…-Manager ★10 | none | CI artifacts only | Magisk+Zygisk native | root-only by design | — | active | Camera1 partial, "not verified on device" per own README |
| f1sherb0y/…-CamSwap ★1 | **GPL-3.0** | none | LSPosed | no | 8.0 (rec 11+) | 2026-07 | CameraX photo path; ContentProvider config; ExoPlayer streams |
| wrlu/VirtCam ★2 | none | no | Xposed | no | — | 2026-03 | tiny; cleanest hook list doc |
| VCAMSX lineage (DeepMakerAi, VCAMPRO, VirtuCam, …) | mixed/unclear | some sell it | Xposed-family | — | — | active today | original iiheng/VCAMSX deleted; provenance murky, paywalled forks |

---

## 6. What NOT to copy

| Item | Why |
|---|---|
| Camera2Magic code (any of it — incl. its Kotlin) | **no license = all rights reserved**; its native half isn't even published. Harvest only facts (hook list, quirks, workflow diagram) |
| CamSwap code | GPL-3.0 — viral. Design ideas fine, zero code |
| VCAMSX binaries/forks | provenance unclear, several sell access; original repo deleted |
| MediaPlayer-on-stolen-surface as the PRIMARY mechanism | community's #1 source of rotation/color/sync bugs; keep only as a fallback backend |
| Hardcoded per-app dirty fixes (Bilibili if, DingDing if) | make it a data-driven quirk table instead |
| Toast/bubble UX announcing replacement (VCAM's 分辨率 toasts) | detection surface + annoyance |
| ReCam's global no-scope posture, ptrace + sepolicy writes | out of scope for an LSPosed/LSPatch module; per-app scoping is a requirement for us |
| VCAM's "user must pre-rotate/pre-resolve video" model | we own rotation via comp(); don't inherit the gap |
| `catch (Exception)` in hook code | provably fatal (YVCAM pitfall 5) — `Throwable` + submodule isolation only |

---

## 7. Amendments to our B.7 module sketch (only clear improvements)

1. **Adopt the hook allowlist as unioned in §1** — in particular the SIX session-creation
   variants (VCAM/YVCAM), `addTarget/removeTarget`, `ImageReader.newInstance` (+ plane rewrite as
   stretch), Camera1 callback trio + `takePicture`/`onPictureTaken`, `MediaRecorder.setCamera`,
   `setParameters` (size negotiation), `setDisplayOrientation`, and BOTH
   `CameraManager.openCamera` overloads.
2. **Bootstrap at `Instrumentation.callApplicationOnCreate`** (earliest, fixes cold-start config
   race — VCAM + CamSwap both converge here), with C2M-style `Application.onCreate` +
   `Activity.onResume` config re-reads.
3. **`catch (Throwable)` + per-submodule init isolation** in the module entry (YVCAM pitfall 5) —
   one failing handler must never kill the rest.
4. **Config layering:** primary ContentProvider (works without target storage permission —
   CamSwap's scoped-storage answer) → XSharedPreferences/file fallback → sentinel-file flags as
   the zero-IPC LSPatch fallback; normalize absolute/relative path matching (pitfall 1); one
   canonical notify path that always has a Context (pitfall 2).
5. **Lifecycle correctness:** hook `stopPreview`/`release`/`CameraDeviceImpl.close` and stop
   decoders without blocking the hooked thread (OneUI Watchdog lesson #3); disable path calls
   stop-all at three layers (pitfall 3); defensive idle-reaping for missed close signals (TikTok).
6. **Safe-degrade rule:** every hook failure path re-enables the REAL camera — never black, never
   crash (C2M/ReCam evidence #2, #17).
7. **Rotation stays in-module** (our comp() + golden-256): the ecosystem's #1 defect (#1) is our
   differentiator; do NOT adopt VCAM's user-pre-rotates-video model.
8. **Resolution negotiation in-module** (read requested sizes from `setParameters`/session
   surfaces; scale/crop decode output) — kills VCAM failure class #10.
9. **Per-app quirk TABLE** (data-driven), seeded with the two proven entries:
   1×1-SurfaceTexture → `setDefaultBufferSize` (Bilibili), and photo-overlap mode detection
   (WeChat attendance).
10. **Mic hooks (AudioRecord) as an isolated optional submodule** with its own kill switch —
    proven demand (YVCAM), proven fragility (QQ signature change).
11. **Recording is declared limited v1 scope:** C1 via `MediaRecorder.setCamera`; C2 recording
    surfaces via addTarget-swap; anything beyond = documented gap (#5).
12. **Anti-detection defaults:** silent, toastless, no floating UI; deliver frames at exactly the
    app-requested size/cadence.
13. **Transport:** decode in-process (MediaPlayer backend first, hw-decode pipeline later for
    4K/streams) — matches every app-level module and keeps LSPatch compatibility; shmem-ring
    transport belongs to the ReCam-style root tier we are NOT building now.

---

## 8. Duplication flags

| Repo | Overlap with our Phase 3 | Verdict |
|---|---|---|
| **Yaahua/vcam** | ~feature-for-feature (C1+C2+mic+photo, LSPatch, streams) | MIT + active + broader than we'd ship in v1. Harvest design/API freely (license clean); differentiate on rotation correctness + our engine-render GL reuse. Its 5 documented config pitfalls are exactly what we should not repeat |
| **f1sherb0y/CamSwap** | duplicates core + adds CameraX photos + provider config | GPL — ideas only. Its ContentProvider-PFD transport and cold-start prewarm are design validations, adopt the ideas (amendments 4, 2) |
| **VCAMSX family** | duplicates everything, commercialized | avoid entirely (provenance) |
| **wrlu/VirtCam** | hook-list reference only | keep as a clean allowlist cross-check |
| **ReCam** | different layer (cameraserver) | complementary, not competing; MIT; revisit only if we ever add a root-native tier |
| **MGR** | duplicates the no-LSPosed ambition | pre-verification, Camera1-only — watch, don't adopt |

*Recon only — no promises for Phase 3 made or implied. Studio rotation verification continues on
device in parallel.*
