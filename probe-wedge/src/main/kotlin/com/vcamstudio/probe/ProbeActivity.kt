package com.vcamstudio.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30

/**
 * :probe-wedge — round-20 MANDATE 1 + round-21 owner mandate (variants).
 *
 * BASE: the original minimal case — one EGL context, GL_TRIANGLE_STRIP,
 * 4 in-shader const vertices (gl_VertexID), solid green, no attributes.
 * RESULT ON DEVICE (Adreno 730): WEDGE PRESENT (screenshot r21).
 *
 * Round-21 variants (owner mandate, probe-only — the studio engine is
 * FROZEN this round):
 *  P1  GL_TRIANGLES, 6 explicit in-shader vertices (two triangles).
 *  P2  Same strip through an FBO (RGBA8, size == surface) with explicit
 *      glViewport at draw time, then blit FBO->surface via a plain
 *      textured strip quad.
 *  P3  Oversized triangle (-1,-1)(3,-1)(-1,3), fragment discards outside
 *      0..1 UV — bypasses strip rasterization entirely.
 *
 * No camera permission, no storage permission — the manifest declares no
 * permissions at all. Every line (EGL config attribs, renderer/version,
 * shader compile results, GL errors, per-second VIEWPORT/SCISSOR_BOX) is
 * appended to filesDir/probe-wedge-log.txt, mirrored on screen, and
 * copyable via the COPY LOG button.
 */
class ProbeActivity : Activity() {

    enum class Mode { BASE, P1, P2, P3 }

    @Volatile
    var mode: Mode = Mode.BASE

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var activeLoop: RenderLoop? = null
    private lateinit var log: ProbeLog

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        log = ProbeLog(this)
        log.clear()
        log.add("PROBE_BUILD r21-variants (BASE,P1,P2,P3)")

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- mode buttons (top, always tappable) ---
        fun modeButton(label: String, m: Mode): Button = Button(this).apply {
            text = label
            setOnClickListener {
                mode = m
                log.add("MODE_SET $m from UI")
            }
        }
        val modesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modesRow.addView(modeButton("BASE", Mode.BASE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modesRow.addView(modeButton("P1 TRIS", Mode.P1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modesRow.addView(modeButton("P2 FBO", Mode.P2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modesRow.addView(modeButton("P3 BIGTRI", Mode.P3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // --- surface ---
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                log.add("SURFACE_CREATED")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                log.add("SURFACE_CHANGED dims=${width}x$height fmt=0x${format.toString(16)}")
                startRenderLoop(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                log.add("SURFACE_DESTROYED")
                stopRenderLoop()
            }
        })

        // --- bottom panel: status + on-screen full log + copy ---
        statusText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
        }
        logText = TextView(this).apply {
            setTextColor(0xFF9FE0AF.toInt())
            textSize = 9f
            setTextIsSelectable(true)
        }
        val logScroll = ScrollView(this).apply { addView(logText) }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val copyBtn = Button(this).apply {
            text = "COPY LOG"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("probe-wedge-log", log.all()))
                Toast.makeText(this@ProbeActivity, "log copied", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(modesRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            surfaceView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            bottom,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 340, 0f),
        )
        root.addView(copyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        statusText.gravity = Gravity.START
    }

    private fun startRenderLoop(surface: Surface, width: Int, height: Int) {
        stopRenderLoop()
        val thread = HandlerThread("probe-render")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)
        val loop = RenderLoop(surface, width, height, log, this)
        activeLoop = loop
        renderHandler!!.post(loop)
    }

    private fun stopRenderLoop() {
        activeLoop?.stop()
        activeLoop = null
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
    }

    private class RenderLoop(
        private val surface: Surface,
        private val width: Int,
        private val height: Int,
        private val log: ProbeLog,
        private val activity: ProbeActivity,
    ) : Runnable {

        @Volatile
        private var stopped = false

        override fun run() {
            try {
                runEgl()
            } catch (t: Throwable) {
                log.add("FATAL ${t.javaClass.simpleName}: ${t.message}")
                log.flush()
            }
        }

        private fun runEgl() {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) { log.add("EGL display=NO_DISPLAY"); log.flush(); return }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                log.add("EGL initialize failed err=0x${Integer.toHexString(EGL14.eglGetError())}")
                log.flush(); return
            }
            log.add("EGL_VERSION ${version[0]}.${version[1]}")

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) {
                log.add("EGL chooseConfig failed err=0x${Integer.toHexString(EGL14.eglGetError())}")
                log.flush(); return
            }
            val config = configs[0]!!

            // Full chosen-config attribs — owner asked for the EGL config in the file.
            fun attrib(name: Int): Int = intArrayOf(0).also {
                EGL14.eglGetConfigAttrib(display, config, name, it, 0)
            }[0]
            log.add(
                "EGL_CONFIG rgb=${attrib(EGL14.EGL_RED_SIZE)}-${attrib(EGL14.EGL_GREEN_SIZE)}-${attrib(EGL14.EGL_BLUE_SIZE)}" +
                    " a=${attrib(EGL14.EGL_ALPHA_SIZE)} depth=${attrib(EGL14.EGL_DEPTH_SIZE)}" +
                    " stencil=${attrib(EGL14.EGL_STENCIL_SIZE)} samples=${attrib(EGL14.EGL_SAMPLES)}" +
                    " renderable=0x${Integer.toHexString(attrib(EGL14.EGL_RENDERABLE_TYPE))}" +
                    " surface=0x${Integer.toHexString(attrib(EGL14.EGL_SURFACE_TYPE))}",
            )

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                log.add("EGL createContext failed err=0x${Integer.toHexString(EGL14.eglGetError())}")
                log.flush(); return
            }
            if (!EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
                log.add("EGL uncurrent failed")
            }

            val winSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (winSurface == EGL14.EGL_NO_SURFACE) {
                log.add("EGL_WINDOW_CREATED failed err=0x${Integer.toHexString(EGL14.eglGetError())}")
                log.flush(); return
            }
            log.add("EGL_WINDOW_CREATED dims=${width}x$height")

            if (!EGL14.eglMakeCurrent(display, winSurface, winSurface, context)) {
                log.add("EGL makeCurrent(window) failed err=0x${Integer.toHexString(EGL14.eglGetError())}")
                log.flush(); return
            }

            val vendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "?"
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "?"
            val glVer = GLES30.glGetString(GLES30.GL_VERSION) ?: "?"
            log.add("EGL_CTX vendor=$vendor")
            log.add("EGL_CTX renderer=$renderer")
            log.add("EGL_CTX version=$glVer")

            // ---- all programs compiled up-front, every compile logged ----

            // BASE strip: 4 in-shader const vertices, gl_VertexID indexed.
            val vsStrip = """
                #version 300 es
                const vec2 POS[4]=vec2[4](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,1));
                void main(){ gl_Position=vec4(POS[gl_VertexID],0.0,1.0); }
            """.trimIndent()

            // P1: GL_TRIANGLES with SIX explicit vertices (two triangles).
            val vsTris = """
                #version 300 es
                const vec2 P6[6]=vec2[6](vec2(-1,-1),vec2(1,-1),vec2(1,1),
                                         vec2(-1,-1),vec2(1,1),vec2(-1,1));
                void main(){ gl_Position=vec4(P6[gl_VertexID],0.0,1.0); }
            """.trimIndent()

            // P2 blit: plain textured quad (strip), samples the FBO texture.
            val vsBlit = """
                #version 300 es
                const vec2 POS[4]=vec2[4](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,1));
                out vec2 vUV;
                void main(){ vUV=POS[gl_VertexID]*0.5+0.5; gl_Position=vec4(POS[gl_VertexID],0.0,1.0); }
            """.trimIndent()
            val fsBlit = """
                #version 300 es
                precision mediump float;
                in vec2 vUV;
                out vec4 fragColor;
                uniform sampler2D uTex;
                void main(){ fragColor=texture(uTex,vUV); }
            """.trimIndent()

            // P3: oversized triangle, FS discards outside 0..1 UV.
            val vsBig = """
                #version 300 es
                const vec2 BIG[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
                out vec2 vUV;
                void main(){ vUV=BIG[gl_VertexID]*0.5+0.5; gl_Position=vec4(BIG[gl_VertexID],0.0,1.0); }
            """.trimIndent()
            val fsBig = """
                #version 300 es
                precision mediump float;
                in vec2 vUV;
                out vec4 fragColor;
                void main(){
                  if(vUV.x<0.0||vUV.x>1.0||vUV.y<0.0||vUV.y>1.0) discard;
                  fragColor=vec4(0.2,0.7,0.3,1.0);
                }
            """.trimIndent()

            val fsGreen = """
                #version 300 es
                precision mediump float;
                out vec4 fragColor;
                void main(){ fragColor=vec4(0.2,0.7,0.3,1.0); }
            """.trimIndent()

            val progStrip = compile(vsStrip, fsGreen, log, "strip")
            val progTris = compile(vsTris, fsGreen, log, "tris6")
            val progBlit = compile(vsBlit, fsBlit, log, "blit")
            val progBig = compile(vsBig, fsBig, log, "bigtri")
            if (progStrip == null || progTris == null || progBlit == null || progBig == null) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                return
            }

            if (!EGL14.eglMakeCurrent(display, winSurface, winSurface, context)) {
                log.add("EGL re-current failed")
            }

            // ---- P2 FBO (RGBA8, size == surface), created lazily ----
            var fboHandle = 0
            var fboTex = 0
            var fboW = 0
            var fboH = 0
            fun ensureFbo(): Boolean {
                if (fboHandle != 0 && fboW == width && fboH == height) return true
                if (fboHandle != 0) {
                    GLES30.glDeleteFramebuffers(1, intArrayOf(fboHandle), 0)
                    GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
                    fboHandle = 0; fboTex = 0
                }
                val tex = intArrayOf(0)
                GLES30.glGenTextures(1, tex, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
                )
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                val fb = intArrayOf(0)
                GLES30.glGenFramebuffers(1, fb, 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, tex[0], 0,
                )
                val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                log.add("P2_FBO_CREATED ${width}x$height handle=${fb[0]} tex=${tex[0]} status=0x${Integer.toHexString(st)}")
                if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) return false
                fboHandle = fb[0]; fboTex = tex[0]; fboW = width; fboH = height
                return true
            }

            var frames = 0L
            var errors = 0
            var firstError = ""
            var swapFailures = 0
            val err = IntArray(1)
            val intBuf = IntArray(4)
            var lastMode: Mode? = null
            val presentedModes = HashSet<Mode>()

            while (!stopped) {
                val m = activity.mode
                if (m != lastMode) {
                    log.add("MODE_ACTIVE $m frame=$frames")
                    lastMode = m
                }

                when (m) {
                    Mode.BASE -> {
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                        GLES30.glViewport(0, 0, width, height)
                        GLES30.glUseProgram(progStrip)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    }
                    Mode.P1 -> {
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                        GLES30.glViewport(0, 0, width, height)
                        GLES30.glUseProgram(progTris)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
                    }
                    Mode.P2 -> {
                        if (!ensureFbo()) break
                        // pass 1: strip into the FBO, explicit viewport
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboHandle)
                        GLES30.glViewport(0, 0, width, height)
                        GLES30.glUseProgram(progStrip)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                        // pass 2: blit FBO -> surface, plain textured quad
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                        GLES30.glViewport(0, 0, width, height)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(progBlit, "uTex"), 0)
                        GLES30.glUseProgram(progBlit)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    }
                    Mode.P3 -> {
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                        GLES30.glViewport(0, 0, width, height)
                        GLES30.glUseProgram(progBig)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                    }
                }

                err[0] = GLES30.glGetError()
                if (err[0] != 0) {
                    errors++
                    if (firstError.isEmpty()) firstError = "0x${Integer.toHexString(err[0])}"
                    log.add("GL_ERROR code=0x${Integer.toHexString(err[0])} at draw mode=$m frame=$frames")
                }
                if (!EGL14.eglSwapBuffers(display, winSurface)) {
                    swapFailures++
                    log.add("SWAP_FAILED err=0x${Integer.toHexString(EGL14.eglGetError())} frame=$frames")
                }
                frames++
                if (presentedModes.add(m)) {
                    log.add("FIRST_PRESENT mode=$m frame=$frames")
                }

                if (frames % 60L == 0L) {
                    // Owner mandate: VIEWPORT + SCISSOR_BOX once per second.
                    GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, intBuf, 0)
                    val vp = "${intBuf[0]},${intBuf[1]},${intBuf[2]},${intBuf[3]}"
                    GLES30.glGetIntegerv(GLES30.GL_SCISSOR_BOX, intBuf, 0)
                    val sc = "${intBuf[0]},${intBuf[1]},${intBuf[2]},${intBuf[3]}"
                    log.add("GL_STATE t=${frames / 60}s mode=$m VIEWPORT=[$vp] SCISSOR_BOX=[$sc]")
                    publish("$renderer\nmode=$m frames=$frames errors=$errors swapFail=$swapFailures\nverdict per mode: CLEAN = workaround class; WEDGE = still broken")
                    refreshLogTail()
                }
                try {
                    Thread.sleep(16)
                } catch (_: InterruptedException) {
                    break
                }
            }

            log.add("LOOP_END mode=$lastMode frames=$frames errors=$errors firstErr=$firstError swapFailures=$swapFailures")
            log.flush()
            if (fboHandle != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fboHandle), 0)
                GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, winSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        private fun compile(vsSrc: String, fsSrc: String, log: ProbeLog, name: String): Int? {
            val status = IntArray(1)
            val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
            GLES30.glShaderSource(vs, vsSrc)
            GLES30.glCompileShader(vs)
            val vsLog = GLES30.glGetShaderInfoLog(vs)
            GLES30.glGetShaderiv(vs, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                log.add("VS_COMPILE_FAILED name=$name log=$vsLog"); log.flush(); return null
            }
            val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
            GLES30.glShaderSource(fs, fsSrc)
            GLES30.glCompileShader(fs)
            val fsLog = GLES30.glGetShaderInfoLog(fs)
            GLES30.glGetShaderiv(fs, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                log.add("FS_COMPILE_FAILED name=$name log=$fsLog"); log.flush(); return null
            }
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
            val linkLog = GLES30.glGetProgramInfoLog(prog)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                log.add("LINK_FAILED name=$name log=$linkLog"); log.flush(); return null
            }
            log.add("SHADER_COMPILED prog=$prog name=$name logs=${(vsLog + fsLog + linkLog).trim().ifEmpty { "-" }}")
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return prog
        }

        @SuppressLint("SetTextI18n")
        private fun publish(text: String) {
            activity.runOnUiThread { activity.statusText.text = text + "\nfull log below + COPY LOG button" }
        }

        @SuppressLint("SetTextI18n")
        private fun refreshLogTail() {
            val text = log.all()
            activity.runOnUiThread { activity.logText.text = text }
        }

        fun stop() {
            stopped = true
        }
    }
}
