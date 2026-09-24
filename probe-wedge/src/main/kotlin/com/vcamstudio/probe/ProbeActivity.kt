package com.vcamstudio.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30

/**
 * :probe-wedge — round-20 MANDATE 1.
 *
 * Minimal wedge isolation: ONE activity, ONE EGL context, ONE render thread,
 * ONE fullscreen green quad drawn with positions hardcoded as a const array
 * IN THE VERTEX SHADER (gl_VertexID indexing — NO attribute buffers at all),
 * presented with eglSwapBuffers in a plain loop.
 *
 * Zero dependency on the studio engine modules. No camera, no layers, no FBO,
 * no diagnostics toggles. Every frame checks glGetError; everything notable
 * (EGL vendor/renderer/version, surface dims, shader log, GL errors) is
 * appended to a log file AND mirrored on screen.
 *
 * Verdict protocol:
 *  - green fills the screen, no wedge  => the wedge lives in the studio engine
 *  - wedge visible HERE TOO            => the bug is below our code (Adreno
 *     driver / EGL window composition) — stop engine work, move to
 *     SurfaceView/EGL configuration.
 */
class ProbeActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var activeLoop: RenderLoop? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        surfaceView = SurfaceView(this)
        statusText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(16, 16, 16, 16)
        }
        root.addView(
            surfaceView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            statusText,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(root)

        val log = ProbeLog(this)
        log.clear()
        log.add("PROBE start")

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                log.add("SURFACE_CREATED")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                log.add("SURFACE_CHANGED dims=${width}x$height fmt=0x${format.toString(16)}")
                startRenderLoop(holder.surface, width, height, log)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                log.add("SURFACE_DESTROYED")
                stopRenderLoop()
            }
        })
    }

    private fun startRenderLoop(surface: Surface, width: Int, height: Int, log: ProbeLog) {
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
            log.add("EGL_CONFIG chosen")

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

            // MANDATE: positions as const array IN-SHADER, no attribute upload.
            val vs = """
                #version 300 es
                const vec2 POS[4]=vec2[4](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,1));
                void main(){ gl_Position=vec4(POS[gl_VertexID],0.0,1.0); }
            """.trimIndent()
            val fs = """
                #version 300 es
                precision mediump float;
                out vec4 fragColor;
                void main(){ fragColor=vec4(0.2,0.7,0.3,1.0); }
            """.trimIndent()

            val program = compile(vs, fs, log) ?: run {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                return
            }
            log.add("SHADER_COMPILED prog=$program")

            if (!EGL14.eglMakeCurrent(display, winSurface, winSurface, context)) {
                log.add("EGL re-current failed")
            }

            var frames = 0L
            var errors = 0
            var firstError = ""
            var swapFailures = 0
            val err = IntArray(1)

            while (!stopped) {
                GLES30.glViewport(0, 0, width, height)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                err[0] = GLES30.glGetError()
                if (err[0] != 0) {
                    errors++
                    if (firstError.isEmpty()) firstError = "0x${Integer.toHexString(err[0])}"
                    log.add("GL_ERROR code=0x${Integer.toHexString(err[0])} at draw frame=$frames")
                }
                if (!EGL14.eglSwapBuffers(display, winSurface)) {
                    swapFailures++
                    log.add("SWAP_FAILED err=0x${Integer.toHexString(EGL14.eglGetError())} frame=$frames")
                }
                frames++
                if (frames % 60L == 0L) {
                    log.flush()
                    publish(
                        "$renderer\n" +
                            "frames=$frames errors=$errors swapFail=$swapFailures\n" +
                            "verdict: GREEN NO WEDGE = bug is in studio engine; WEDGE = bug is below us (driver/EGL)\n" +
                            log.tail(12) + "\nfull log: filesDir/probe-wedge-log.txt",
                    )
                }
                if (frames == 1L) {
                    val line = "FIRST_PRESENT frames=1 errors=$errors swapFail=$swapFailures"
                    log.add(line)
                    publish(
                        "$renderer\nFIRST_PRESENT ok — watch the screen: GREEN NO WEDGE or WEDGE?\n" +
                            "full log: filesDir/probe-wedge-log.txt",
                    )
                }
                try {
                    Thread.sleep(16)
                } catch (_: InterruptedException) {
                    break
                }
            }

            log.add("LOOP_END frames=$frames errors=$errors firstErr=$firstError swapFailures=$swapFailures")
            log.flush()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, winSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        private fun compile(vsSrc: String, fsSrc: String, log: ProbeLog): Int? {
            val status = IntArray(1)
            val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
            GLES30.glShaderSource(vs, vsSrc)
            GLES30.glCompileShader(vs)
            val vsLog = GLES30.glGetShaderInfoLog(vs)
            GLES30.glGetShaderiv(vs, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                log.add("VS_COMPILE_FAILED log=$vsLog"); log.flush(); return null
            }
            val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
            GLES30.glShaderSource(fs, fsSrc)
            GLES30.glCompileShader(fs)
            val fsLog = GLES30.glGetShaderInfoLog(fs)
            GLES30.glGetShaderiv(fs, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                log.add("FS_COMPILE_FAILED log=$fsLog"); log.flush(); return null
            }
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
            val linkLog = GLES30.glGetProgramInfoLog(prog)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                log.add("LINK_FAILED log=$linkLog"); log.flush(); return null
            }
            if (vsLog.isNotBlank() || fsLog.isNotBlank() || linkLog.isNotBlank()) {
                log.add("SHADER_LOGS vs='$vsLog' fs='$fsLog' link='$linkLog'")
            }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            GLES30.glUseProgram(prog)
            return prog
        }

        @SuppressLint("SetTextI18n")
        private fun publish(text: String) {
            activity.runOnUiThread { activity.statusText.text = text }
        }

        fun stop() {
            stopped = true
        }
    }
}
