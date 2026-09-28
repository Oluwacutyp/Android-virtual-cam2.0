package com.vcamstudio.transport.hook

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Bundle
import android.os.SharedMemory
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * r53 Route R (ROOT hook) — runs inside OTHER apps' processes via
 * Xposed/LSPosed. SELF-CONTAINED by owner mandate: no Hilt, no DI, no
 * VCam/engine imports, logging only via android.util.Log.
 *
 * Camera1 scope this round:
 *  - setPreviewTexture/setPreviewDisplay: the app's target is KEPT; the
 *    real camera receives a dummy SurfaceTexture so the sensor keeps
 *    running, and our frames are RENDERED into the app's target with an
 *    EGL14+GLES context built here (pure framework APIs, no NDK).
 *  - setPreviewCallback(WithBuffer): our feeder fills the app's byte[]
 *    (NV21) from the ring and invokes the app's callback.
 *  - startPreview/stopPreview/release ordering handled; everything
 *    released on stop.
 *
 * Frames come from the VCam transport ring, fetched as a SharedMemory
 * parcelable over the binder call content://<authority> (getRing) — the
 * same fd-over-binder pattern as the AI ring. Seqlock-validated reads.
 */
class VcamHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "VCamHook"
        // Our own application ids (config constants, NOT device-specific).
        private val SELF = setOf("com.vcamstudio.app", "com.vcamstudio.app.debug")
        // Provider authorities probed in order.
        private val AUTHORITIES = listOf(
            "com.vcamstudio.app.transport",
            "com.vcamstudio.app.debug.transport",
        )
        private const val HEADER_BYTES = 96
        private const val MAGIC = 0x56435430
    }

    // ---- ring reader (self-contained; mirrors engine-transport's codec) ---

    private class RingReader(private val context: Context) {
        private var map: ByteBuffer? = null
        private var lastSeq = 0

        fun connect(): Boolean {
            if (map != null) return true
            for (auth in AUTHORITIES) {
                try {
                    val b: Bundle? = context.contentResolver.call(
                        Uri.parse("content://$auth"), "getRing", null, null,
                    )
                    val sm: SharedMemory? = b?.getParcelable("ring")
                    if (sm != null) {
                        map = sm.mapReadOnly().order(ByteOrder.nativeOrder())
                        Log.i(TAG, "ring connected via $auth")
                        return true
                    }
                } catch (t: Throwable) {
                    Log.i(TAG, "connect fail $auth: ${t.message}")
                }
            }
            return false
        }

        /**
         * Seqlock read: returns false unless seq was EVEN and unchanged
         * across the copy (torn frames are skipped, never rendered).
         */
        fun readLatest(dst: ByteBuffer, meta: IntArray): Boolean {
            val m = map ?: return false
            var s0 = m.getInt(36)
            if (s0 == lastSeq || s0 % 2 != 0 || m.getInt(0) != MAGIC) return false
            val w = m.getInt(12)
            val h = m.getInt(16)
            val rot = m.getInt(20)
            val fmt = m.getInt(8)
            val frameBytes = m.getInt(32)
            if (frameBytes <= 0 || dst.capacity() < frameBytes) return false
            m.position(HEADER_BYTES)
            m.limit(HEADER_BYTES + frameBytes)
            dst.position(0)
            dst.put(m)
            dst.position(0)
            m.clear()
            val s1 = m.getInt(36)
            if (s1 != s0) return false
            lastSeq = s0
            meta[0] = w; meta[1] = h; meta[2] = rot; meta[3] = fmt
            return true
        }
    }

    // ---- per-camera state --------------------------------------------------

    private class CameraState(val camera: Camera) {
        var appTexture: SurfaceTexture? = null
        var holder: SurfaceHolder? = null
        var callback: Camera.PreviewCallback? = null
        var lastBuffer: ByteArray? = null
        var renderer: Renderer? = null
        var feeder: Thread? = null
        val active = AtomicBoolean(false)
    }

    private val states = WeakHashMap<Camera, CameraState>()
    private var ringReader: RingReader? = null

    // ---- entry --------------------------------------------------------------

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName in SELF) return
        try {
            hookCamera1(lpparam)
        } catch (t: Throwable) {
            // A hook must NEVER take the host app down.
            Log.e(TAG, "init fail in ${lpparam.packageName}", t)
        }
    }

    private fun context(): Context? = try {
        // AndroidAppHelper is not in the compileOnly api:82 jar; the
        // framework idiom works everywhere.
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Context
    } catch (t: Throwable) {
        null
    }

    private fun ensureReader(): RingReader? {
        if (ringReader != null) return ringReader
        val ctx = context() ?: return null
        val r = RingReader(ctx)
        if (r.connect()) {
            ringReader = r
            return r
        }
        return null
    }

    private fun hookCamera1(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader

        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", cl, "setPreviewTexture",
            SurfaceTexture::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as Camera
                    val st = state(camera)
                    st.appTexture = param.args[0] as SurfaceTexture?
                    // The real camera gets a dummy so the sensor keeps
                    // running; the app's target gets OUR render.
                    if (st.appTexture != null) {
                        param.args[0] = SurfaceTexture(false)
                    }
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", cl, "setPreviewDisplay",
            SurfaceHolder::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as Camera
                    state(camera).holder = param.args[0] as SurfaceHolder?
                    // Surface-display apps keep the real target (v0 renders
                    // only into SurfaceTexture consumers; documented scope).
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", cl, "setPreviewCallback",
            Camera.PreviewCallback::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val st = state(param.thisObject as Camera)
                    val app = param.args[0] as? Camera.PreviewCallback ?: return
                    st.callback = app
                    // Swap in OUR wrapper before the original registers it:
                    // the framework stores the wrapper, no hidden methods.
                    param.args[0] = feederCallback(st)
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", cl, "addCallbackBuffer",
            ByteArray::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    state(param.thisObject as Camera).lastBuffer = param.args[0] as ByteArray
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", cl, "startPreview",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    startTransport(param.thisObject as Camera)
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", cl, "stopPreview",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    stopTransport(param.thisObject as Camera)
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera", cl, "release",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    stopTransport(param.thisObject as Camera)
                }
            },
        )

        Log.i(TAG, "camera1 hooks installed for ${lpparam.packageName}")
    }

    private fun state(camera: Camera): CameraState =
        states[camera] ?: CameraState(camera).also { states[camera] = it }

    private fun feederCallback(st: CameraState): Camera.PreviewCallback =
        Camera.PreviewCallback { data, camera ->
            val cb = st.callback
            if (cb != null) {
                val buf = st.lastBuffer?.takeIf { it.size >= data.size } ?: data
                if (fillNv21(buf)) {
                    cb.onPreviewFrame(buf, camera)
                } else {
                    cb.onPreviewFrame(data, camera)
                }
            }
        }

    private fun startTransport(camera: Camera) {
        val st = state(camera)
        st.active.set(true)
        val target = st.appTexture
        if (target != null) {
            stopRenderer(st)
            val r = Renderer(st, ensureReader() ?: return, QUAD)
            st.renderer = r
            r.start()
        }
        if (st.callback != null) {
            stopFeeder(st)
            val t = Thread({
                while (st.active.get()) {
                    if (!fillNv21(st.lastBuffer ?: ByteArray(640 * 480 * 3 / 2))) {
                        try {
                            Thread.sleep(50)
                        } catch (e: InterruptedException) {
                            return@Thread
                        }
                    }
                }
            }, "VCamHook-feeder")
            t.isDaemon = true
            st.feeder = t
            t.start()
        }
        Log.i(TAG, "transport started (texture=${target != null}, cb=${st.callback != null})")
    }

    private fun stopTransport(camera: Camera) {
        val st = states[camera] ?: return
        st.active.set(false)
        stopRenderer(st)
        stopFeeder(st)
    }

    private fun stopRenderer(st: CameraState) {
        st.renderer?.stop()
        st.renderer = null
    }

    private fun stopFeeder(st: CameraState) {
        st.feeder?.interrupt()
        st.feeder = null
    }

    private val scratch = ByteBuffer.allocateDirect(4 * 1920 * 1080 * 3 / 2)
    private val meta = IntArray(4)

    /** Ring (I420) -> NV21 into [out]. False when no fresh frame. */
    private fun fillNv21(out: ByteArray?): Boolean {
        if (out == null) return false
        val r = ensureReader() ?: return false
        if (!r.readLatest(scratch, meta)) return false
        val w = meta[0]
        val h = meta[1]
        if (w * h * 3 / 2 > out.size) return false
        val ySize = w * h
        val cSize = ySize / 4
        val dup = scratch.duplicate().order(ByteOrder.nativeOrder())
        for (i in 0 until ySize) out[i] = dup.get(i)
        var o = ySize
        var vi = ySize + cSize
        var ui = ySize
        for (i in 0 until cSize) {
            out[o++] = dup.get(vi + i)
            out[o++] = dup.get(ui + i)
        }
        return true
    }

    // ---- GL renderer (pure framework EGL14/GLES20) -------------------------

    private class Renderer(
        private val st: CameraState,
        private val reader: RingReader,
        private val quad: java.nio.FloatBuffer,
    ) {
        private var thread: Thread? = null
        private val running = AtomicBoolean(false)

        fun start() {
            running.set(true)
            thread = Thread({
                try {
                    loop()
                } catch (t: Throwable) {
                    Log.e(TAG, "renderer died", t)
                } finally {
                    running.set(false)
                }
            }, "VCamHook-render")
            thread!!.isDaemon = true
            thread!!.start()
        }

        fun stop() {
            running.set(false)
            thread?.interrupt()
        }

        private fun loop() {
            val target = st.appTexture ?: return
            val surface = Surface(target)
            val dpy: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "eglInitialize" }
            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE, 0, 0,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(dpy, cfgAttr, 0, configs, 0, 1, num, 0) && num[0] > 0) {
                "eglChooseConfig"
            }
            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE, 0, 0)
            val ctx: EGLContext = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            check(ctx !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            val surf: EGLSurface = EGL14.eglCreateWindowSurface(dpy, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(surf !== EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "eglMakeCurrent" }

            val prog = buildProgram()
            val texY = IntArray(1); val texU = IntArray(1); val texV = IntArray(1)
            GLES20.glGenTextures(1, texY, 0)
            GLES20.glGenTextures(1, texU, 0)
            GLES20.glGenTextures(1, texV, 0)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            val frame = ByteBuffer.allocateDirect(4 * 1920 * 1080 * 3 / 2).order(ByteOrder.nativeOrder())
            val meta = IntArray(4)
            var publishedSeq = -1

            GLES20.glUseProgram(prog)
            val posLoc = GLES20.glGetAttribLocation(prog, "aPos")
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uY"), 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uU"), 1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uV"), 2)

            while (running.get()) {
                if (reader == null || !reader.readLatest(frame, meta)) {
                    try {
                        Thread.sleep(33)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                val w = meta[0]
                val h = meta[1]
                if (w <= 0 || h <= 0) continue
                frame.position(0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY[0])
                upload(GLES20.GL_TEXTURE_2D, w, h, frame, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texU[0])
                upload(GLES20.GL_TEXTURE_2D, w / 2, h / 2, frame, w * h)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texV[0])
                upload(GLES20.GL_TEXTURE_2D, w / 2, h / 2, frame, w * h + (w * h) / 4)
                GLES20.glViewport(0, 0, w, h)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
                EGL14.eglSwapBuffers(dpy, surf)
                publishedSeq++
                if (publishedSeq == 0) Log.i(TAG, "first frame rendered ${w}x$h")
            }
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
            surface.release()
        }

        private fun upload(target: Int, w: Int, h: Int, src: ByteBuffer, offset: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            src.position(offset)
            GLES20.glTexImage2D(target, 0, GLES20.GL_LUMINANCE, w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, src.slice())
        }

        private fun buildProgram(): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                return s
            }
            val vs = shader(
                GLES20.GL_VERTEX_SHADER,
                """
                attribute vec2 aPos;
                varying vec2 vUV;
                void main() {
                    vUV = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
                    gl_Position = vec4(aPos, 0.0, 1.0);
                }
                """.trimIndent(),
            )
            // YUV(BT.601) -> RGB; texture samplers 2D LUMINANCE (I420 planes).
            val fs = shader(
                GLES20.GL_FRAGMENT_SHADER,
                """
                precision mediump float;
                varying vec2 vUV;
                uniform sampler2D uY;
                uniform sampler2D uU;
                uniform sampler2D uV;
                void main() {
                    float y = texture2D(uY, vUV).r;
                    float u = texture2D(uU, vUV).r - 0.5;
                    float v = texture2D(uV, vUV).r - 0.5;
                    float r = y + 1.402 * v;
                    float g = y - 0.344 * u - 0.714 * v;
                    float b = y + 1.772 * u;
                    gl_FragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);
                }
                """.trimIndent(),
            )
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }
    }

    private val QUAD = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).apply {
        val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)
        asFloatBuffer().put(v)
        position(0)
    }.asFloatBuffer()
}
