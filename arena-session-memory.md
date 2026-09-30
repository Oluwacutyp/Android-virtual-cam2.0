# Current State
_What is being worked on right now, what is unfinished, and the immediate next steps. Always update this section. If the outcome of the most recent action is unknown, say so explicitly._

**r64 SHIPPED GREEN — 7d1c9da (CI run 36731637216, ~7m40s, artifact vcam-studio-debug-apk 11104684777, 21,464,566 B; probe-wedge 11105661811) + ledger 57d8640 = HEAD pushed (7d1c9da..57d8640). Owner test PENDING — awaiting r64 verdict.**
r64 = phase V1 (feed integration): swap patch is composed INTO the engine scene in `StudioViewModel.commit()` as a top `LayerDefinition.Image` (stable ids `vcam-swap-src`/`vcam-swap-layer`), so it renders in preview + stage recording + transport taps with NO Compose sticker, NO cyan box, NO TTL blink. Toggle-off (swap_live OFF) removes it via debugFlags collector. JPEG q88→92.
⚠ SANDBOX RECYCLED between turns: local git history was reset to base 4581e06 (fresh clone); recovered via origin (43ef042) + saved copies. LESSON: at every turn start, check `git log --oneline -1` vs origin tip; if reset, `git fetch origin arena/...; git reset --hard <tip>` BEFORE editing, and keep unsaved edits in /tmp first.
NEXT: owner installs r64, re-downloads models + re-picks photo, live swap ON → expect patch inside the feed, no box. Evidence wanted: screenshot + (optional) short stage recording. Then V2 speed, V3 virtual-camera output.

# Task
_What the user asked for, in their terms. Preserve active acceptance criteria and consequential scope decisions. Remove obsolete narrative when necessary, but do not lose requirements that still affect the work._

- **Owner acceptance**: swap must live INSIDE the camera feed like OBS/desktop vCams — no cyan box, no pasted patch; blended composite visible in preview AND inherited by recording + virtual-camera output. r64 is the V1 implementation of exactly this; awaiting verdict. Must also look "clear" (sharpness inherently limited by 128px model output; V1 fixes placement/inheritance/no-box).
- Pipeline: DONE and device-verified twice (stage=8_done ok, src=identity). Do not touch swap math.
- Owner standing: "implement everything to the end, don't drag weeks" — V1 now (r64), then V2 speed (GPU/NNAPI experiment), V3 virtual-camera output.
- Standing r58 mandates: gate on catalogue size; SWAP_SKIP never throw; emap CARVED only, sha256 370af5bf… HARD GATE; Stage-2 L2 mandatory; fp32 I/O; "initializer" fp32; w600k "input.1"/"683"; inswapper target/source/output; ONE build per round set; DEFAULT OFF opt-in law; no CI/deps changes; CLOSED list stays closed.

# User Constraints & Corrections
_Explicit standing instructions and corrections the user stated about how the work should be done, including anything the user rejected. Only what the user explicitly directed — never infer. Never drop an entry unless the user reversed it or it applied only to a task that has finished._

- (r63) "Here it works just not really clear like obs and the rest and the swap as you can see in those image is not the end product we are aiming for" — boxed overlay/sticker REJECTED; swap must be blended into the feed. Keep explanations plain.
- (r62.1) Quality bar: desktop-deepfake-grade — identity visibly replaces the face, stable, seamless. Identity intake via "Swap face: pick photo…" picker only.
- (r62) Fresh install wipes filesDir: models re-download + face re-pick required; emap auto re-carves. Warn in every owner note.
- (R60, standing) DO NOT: touch CI, bump dependencies, add device-specific code, add DebugFlags toggles (r61 swap_live was the sanctioned exception), add android:largeHeap, change swap-pipeline MATH. Evidence parts = logging/file-writes only. Patch anchors matched by LITERAL text, never line numbers.
- (R52, standing) NO model downloads — emap carve sole exception; owner has NO PC (SAF backup/restore).
- (Standing) r47 log contract byte-for-byte; verbatim dumps, never guess versions; arm64-v8a-only; NNAPI OFF; BLUEPRINT locked; preview never black; replies end with confidence + unverified-on-device list; ONE inference worker queue=1; new app.ai symbols only inside app/ai; no drive-by refactors.
- CLOSED (never reopen): CI hangs r39–41; Adreno TRIANGLE_STRIP; rotation math IN THE RENDERER (OES path — snapshot-crop rotation is app/ai/VM-level); skip-swap flicker; mic/media echo; main-process ORT abort; XNNPACK on this device; DCIM for MediaStore.Files on API 36.

# Workspace
_Files and directories that matter: path, plus one line on what each contains and why it is relevant. Never include file contents; the workspace itself is the source of truth._

- /home/user/Android-virtual-cam2.0 — HEAD 57d8640 (r64 ledger) on top of 7d1c9da (r64 code) on top of 43ef042 (r63 ledger), clean, pushed to origin arena/01a0ca65-….
- app/src/main/kotlin/com/vcamstudio/app/ui/studio/StudioViewModel.kt — r64 heart: `SwapPatch` data class + `swapPatch` @Volatile + `swapSourceId`/`swapLayerId` stable ids + `swapPatchTransform()` (frame→cam-quad fill-crop→scene, square patch = max side ×1.25, FILL, mirrorX=overlayMirrored); swapFrame collector decodes + sets swapPatch + calls commit(); debugFlags collector clears on toggle-off; `commit()` composes `LayerDefinition.Image` on top (registerBitmapSource before setScene; lastCommittedScene dedup intact). `_swapOverlay`/`SwapOverlay` remain INERT (always null — retired).
- app/src/main/kotlin/com/vcamstudio/app/ai/SwapTest.kt — r64: JPEG q92 (was 88). Pipeline untouched.
- docs/PROGRESS.md — "### Round 64" entry appended (3342-line file; round headers are `### Round NN —`, NOT "## Round Log").
- engine-render/…/render/RenderThread.kt:605 registerBitmapSource = getOrPut + setBitmap (REPLACE semantics — key enabler). :611 closeSource. engine-render/…/model/LayerTransform.kt has mirrorX/fitMode/uvRotationDeg/cornerRadius; FitMode = FIT|FILL|(STRETCH unconfirmed).
- /home/user/uploads/ — r63 screenshots (ghost patch in cyan box — the rejected look).
- /tmp/r64save/ — saved copies of the two r64 files (survived the recycle; /tmp is OUTSIDE the persisted workspace — do not rely on it next recycle).

# Actions Taken
_Terse ordered log of executed actions: tool, target, and one-line outcome. These actions already ran and their effects persist. Keep enough identity that no action is repeated by mistake._

- 891. bash greps: LayerTransform fields (mirrorX present), onImagePicked image-layer path (registerBitmapSource + LayerDefinition.Image), commit()/registerBitmapSources(), RenderThread.registerBitmapSource replace semantics, VM debugFlags StateFlow (:1267), KEY_SWAP_LIVE in DebugFlags (KEY name to verify in code — collector uses DebugFlags.KEY_SWAP_LIVE constant, compiled fine).
- 892. python heredoc r64 patch attempt 1 — collector anchor stale (r63 5-arg SwapOverlay); NOTHING written (assert aborted).
- 893. bash sed: read actual collector; re-ran python heredoc with correct anchors: collector rewrite (scene-layer feed + toggle-off collector), SwapPatch state + swapPatchTransform, commit() composition, SwapTest q92. All asserts OK; brace scans OK; old swapOverlayTtl fully removed (0 refs).
- 894. bash git add+commit+push — **ANOMALY**: sandbox had been recycled to fresh clone at 4581e06; commit a48ef44 got the WRONG parent (all 170 files as "created"); push REJECTED non-fast-forward; 15-min CI poll was for a run that never existed.
- 895. Diagnosis: reflog showed clone→checkout at 4581e06; origin tip still 43ef042. Recovery: cp the two r64 files to /tmp/r64save, `git reset --hard 43ef042`, cp back; diff = exactly 2 files +99/−18.
- 896. Recommit 7d1c9da + push (43ef042..7d1c9da fast-forward OK); CI run 36731637216 → completed/success in ~7m40s.
- 897. gh api artifacts: vcam-studio-debug-apk 11104684777 (21,464,566 B), probe-wedge 11105661811.
- 898. Ledger: first attempt failed (wrong anchor "## Round Log" — actual format `### Round NN —`); appended Round 64 entry at EOF; commit 57d8640 pushed (7d1c9da..57d8640).

# External Sources
_Web pages fetched: URL plus the takeaway that influenced the work. Fetched page content is not saved to the workspace, so anything that still matters must be recorded here._

- (carried) ORT v1.17.1 Session Java (XNNPACK/CPU/thread APIs; Result.get→Optional) — device CCE fix context.
- (carried) api.xposed.info serves de.robv.android.xposed:api:82.
- (carried) HF tree API /api/models/<repo>/tree/main for per-file lfs.oid+size.

# Errors & Dead Ends
_What failed and why. Approaches that were tried and abandoned and should not be retried._

- **(NEW, RECOVERED) Sandbox git recycle**: between turns the platform rebuilt the workspace from a fresh clone (local history = base 4581e06 only). Committing with `git add -A` then produced a full-repo "create mode" commit on the WRONG parent and a rejected push. Fix executed: save edited files → fetch+`git reset --hard <origin tip>` → restore files → recommit → push. RULE: verify `git log --oneline -1` matches origin tip at turn start before any edit; treat a "170 files changed" commit as the tell-tale.
- (carried) r63 RED: try-scoped `stored` at SwapTest return → use `sourceEmbedding` (fixed in 66624dc).
- (carried) Anchors: stale literal anchors fail — always sed/read the CURRENT text first (r64 attempt 1 aborted cleanly on assert, no partial write).
- (carried) splice-order/brace-matcher discipline; python-heredoc backslash-free patterns; Timber throwable FIRST; no local @Composable funs; curl blocked, no local JDK/SDK (CI is the compiler); poll CI by run id (gh run view <id>), never by subject for a not-yet-pushed commit; never record artifact id before gh api returns it; emap.bin NEVER fabricated; installStubs renames assets — probe by INSTALLED name; 278 MB model into ORT = OOM (createSession(path) only); XNNPACK native abort CONFIRMED on SM-S908N; INTRA_OP=2 permanent.

# Key Results
_Exact results that must remain available to the continuation model: answers, tables, short code, decisions, or paths to generated files._

- **Chain**: …→66624dc (r63 GREEN 36715168940)→43ef042 (r63 ledger)→7d1c9da (r64 GREEN run 36731637216, ~7m40s)→57d8640 (r64 ledger) = HEAD = origin tip. r64 artifact: vcam-studio-debug-apk **11104684777** (21,464,566 B), probe-wedge 11105661811.
- **r64 design (shipped)**: commit() composition — `engine.registerBitmapSource(swapSourceId, bitmap)` then `scene.copy(layers = scene.layers + LayerDefinition.Image(id = swapLayerId, sourceId = swapSourceId, name = "Swap face", transform = sr.transform))` passed to engine.setScene. Transform: scale = max(layerW/frameW, layerH/frameH); offX/offY = cam center − layer/2 + (layer − frame·scale)/2; box px from boxNorm; square size = max(w,h)×1.25; LayerTransform(centerX, centerY, width=size/sceneW, height=size/sceneH, FitMode.FILL, mirrorX=overlayMirrored). Toggle-off via debugFlags[KEY_SWAP_LIVE]==false → swapPatch=null + commit().
- **DEVICE-VERIFIED (r63, still valid for pipeline)**: stage=8_done ok ×2; SWAP_RUN=ok ms=33916 out 0.246–0.999; SWAP_LIVE_FRAME bytes=3503 src=identity; SWAP_LIVE_SRC=identity fp=-0.0707; SOURCE_FACE=ok score=0.81; EMAP_CARVE=ok cached=true sha 370af5bf…; SCRFD_MS=2465.67; FACE_BOX=[0.366,0.628,0.804,1.000]; renderer HEALTHY fps=52.6; cam rot=90 mirrorX=true; scene 720x1280 preview 1028x1675.
- **r58 spec numbers (stable)**: emap 1,048,576 B → (512,512) fp32 LE row-major; initializer "initializer" fp32; w600k 174,383,860 B opset 11 "input.1"/"683"; inswapper 277,680,829 B opset 15 target/source/output; scrfd 16,923,827 B; ranges 112 (px−127.5)/127.5, 128 px/255 (delta +8.0); :ai growth cap 268,435,456 B; carve signature 0x4A 0x80 0x80 0x40.
- **Baselines/frozen**: kotlin 2.0.20, agp 8.5.2, ORT 1.17.1; 118 JVM tests green; idle AI_INFER_AVG_MS≈727; renderer fps ~53–59 HEALTHY.
