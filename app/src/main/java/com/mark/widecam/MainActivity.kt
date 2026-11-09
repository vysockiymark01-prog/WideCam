package com.mark.widecam

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.service.quicksettings.TileService
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Колесо зума: тянешь пальцем влево-вправо. */
class ZoomWheel(ctx: Context) : View(ctx) {
    var zMin = 1f
    var zMax = 8f
    var value = 1f
    var factor = 1f
    var mmBase = 0f
    var onChange: ((Float) -> Unit)? = null
    private val d = ctx.resources.displayMetrics.density
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.5f * d }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); strokeWidth = 3f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt(); textSize = 15f * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private var lastX = 0f
    private fun pxPerLog() = width / 2.2f

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val cx = width / 2f
        val base = height - 6 * d
        val lv = ln(value)
        val shownMax = zMax * factor
        var z = ceil(zMin * factor * 10f) / 10f
        while (z <= shownMax + 0.001f) {
            val x = cx + (ln(z / factor) - lv) * pxPerLog()
            if (x >= 0f && x <= width) {
                val major = abs(z - z.roundToInt()) < 0.02f || abs(z - 0.5f) < 0.02f
                val h = if (major) 16 * d else 8 * d
                tick.alpha = (255 * (1f - abs(x - cx) / cx).coerceIn(0.15f, 1f)).toInt()
                c.drawLine(x, base, x, base - h, tick)
            }
            z += if (z < 1.95f) 0.1f else if (z < 4.9f) 0.25f else 0.5f
        }
        c.drawLine(cx, base + 2 * d, cx, base - 24 * d, mark)
        val shown = value * factor
        val label = if (mmBase > 0f) String.format(Locale.US, "%.1f× · %d мм", shown, (shown * mmBase).roundToInt())
            else String.format(Locale.US, "%.1f×", shown)
        c.drawText(label, cx, base - 30 * d, txt)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                lastX = e.x
                set(value * exp(-dx / pxPerLog()))
            }
        }
        return true
    }

    fun set(v: Float, notify: Boolean = true) {
        value = v.coerceIn(zMin, max(zMax, zMin))
        invalidate()
        if (notify) onChange?.invoke(value)
    }
}

/** Сетки поверх превью. */
class GridView(ctx: Context) : View(ctx) {
    var mode = 0
        set(v) { field = v; visibility = if (v == 0) GONE else VISIBLE; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt(); strokeWidth = d; style = Paint.Style.STROKE
    }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF; strokeWidth = d; style = Paint.Style.STROKE
    }
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFFD54F.toInt(); strokeWidth = 1.5f * d; style = Paint.Style.STROKE
    }

    private fun lines(c: Canvas, fr: List<Float>, p: Paint) {
        val w = width.toFloat(); val h = height.toFloat()
        for (f in fr) {
            c.drawLine(w * f, 0f, w * f, h, p)
            c.drawLine(0f, h * f, w, h * f, p)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        when (mode) {
            1 -> lines(c, listOf(1 / 3f, 2 / 3f), line)
            2 -> lines(c, listOf(0.25f, 0.5f, 0.75f), line)
            3 -> lines(c, listOf(0.382f, 0.618f), gold)
            4 -> spiral(c, w, h)
            5 -> { c.save(); c.scale(-1f, 1f, w / 2, h / 2); spiral(c, w, h); c.restore() }
            6 -> {
                lines(c, listOf(1 / 3f, 2 / 3f), line)
                c.drawLine(0f, 0f, w, h, faint); c.drawLine(w, 0f, 0f, h, faint)
            }
            7 -> {
                val s = 20 * d
                c.drawLine(w / 2 - s, h / 2, w / 2 + s, h / 2, gold)
                c.drawLine(w / 2, h / 2 - s, w / 2, h / 2 + s, gold)
            }
        }
    }

    /** Золотая спираль: режем кадр в пропорции 0.618 и вписываем дуги. */
    private fun spiral(c: Canvas, w: Float, h: Float) {
        var l = 0f; var t = 0f; var r = w; var b = h
        val k = 0.618f
        for (i in 0 until 12) {
            when (i % 4) {
                0 -> {
                    val ph = (b - t) * k
                    c.drawRect(l, t, r, t + ph, faint)
                    c.drawArc(RectF(l - (r - l), t, r, t + 2 * ph), 270f, 90f, false, gold)
                    t += ph
                }
                1 -> {
                    val pw = (r - l) * k
                    c.drawRect(r - pw, t, r, b, faint)
                    c.drawArc(RectF(r - 2 * pw, t - (b - t), r, b), 0f, 90f, false, gold)
                    r -= pw
                }
                2 -> {
                    val ph = (b - t) * k
                    c.drawRect(l, b - ph, r, b, faint)
                    c.drawArc(RectF(l, b - 2 * ph, r + (r - l), b), 90f, 90f, false, gold)
                    b -= ph
                }
                else -> {
                    val pw = (r - l) * k
                    c.drawRect(l, t, l + pw, b, faint)
                    c.drawArc(RectF(l, t, l + 2 * pw, b + (b - t)), 180f, 90f, false, gold)
                    l += pw
                }
            }
        }
    }
}

/** Рамки кадрирования под соцсети и кино. */
class GuideView(ctx: Context) : View(ctx) {
    var mode = 0
        set(v) { field = v; invalidate() }
    var landscape = false
    private val aspects = floatArrayOf(0f, 9f / 16f, 1f, 4f / 5f, 16f / 9f, 2.39f)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = ctx.resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        if (mode <= 0 || mode >= aspects.size) return
        val a0 = aspects[mode]
        val a = if (landscape) 1f / a0 else a0
        val w = width.toFloat(); val h = height.toFloat()
        var rw = w; var rh = w / a
        if (rh > h) { rh = h; rw = h * a }
        val l = (w - rw) / 2; val t = (h - rh) / 2
        c.drawRect(0f, 0f, w, t, shade)
        c.drawRect(0f, t + rh, w, h, shade)
        c.drawRect(0f, t, l, t + rh, shade)
        c.drawRect(l + rw, t, w, t + rh, shade)
        c.drawRect(l, t, l + rw, t + rh, stroke)
    }
}

/** Уровень горизонта. */
class LevelView(ctx: Context) : View(ctx) {
    var roll = 0f
        set(v) { field = v; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND
    }
    private val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt(); strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * d; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val len = min(width, height) * 0.18f
        val base = (roll / 90f).roundToInt() * 90f
        val tilt = roll - base
        val ok = abs(tilt) < 1f
        c.save(); c.rotate(base, cx, cy)
        c.drawLine(cx - len * 1.5f, cy, cx - len * 1.15f, cy, white)
        c.drawLine(cx + len * 1.15f, cy, cx + len * 1.5f, cy, white)
        c.restore()
        c.save(); c.rotate(roll, cx, cy)
        c.drawLine(cx - len, cy, cx + len, cy, if (ok) yellow else white)
        if (!ok) c.drawText(String.format(Locale.US, "%.0f°", abs(tilt)), cx, cy - 10 * d, txt)
        c.restore()
    }
}

/** Шкала громкости микрофона. */
class MeterView(ctx: Context) : View(ctx) {
    var level = 0f
        set(v) { field = v; invalidate() }
    private val bgP = Paint().apply { color = 0x66000000 }
    private val fg = Paint()

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgP)
        fg.color = if (level > 0.92f) 0xFFE53935.toInt() else if (level > 0.75f) 0xFFFFD54F.toInt() else 0xFF66BB6A.toInt()
        c.drawRect(0f, 0f, width * level, height.toFloat(), fg)
    }
}

/** Гистограмма яркости. */
class HistView(ctx: Context) : View(ctx) {
    private var bins = FloatArray(64)
    private val d = ctx.resources.displayMetrics.density
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
    private val bar = Paint().apply { color = 0xDDFFFFFF.toInt() }
    private val clip = Paint().apply { color = 0xFFE53935.toInt() }

    fun setData(b: FloatArray) { bins = b; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 8 * d, 8 * d, bgP)
        val mx = bins.maxOrNull() ?: 0f
        if (mx <= 0f) return
        val pad = 4 * d
        val bw = (width - 2 * pad) / bins.size
        val hh = height - 2 * pad
        for (i in bins.indices) {
            val h = hh * sqrt(bins[i] / mx)
            val p = if (i >= bins.size - 2 && bins[i] > 0f) clip else bar
            c.drawRect(pad + i * bw, height - pad - h, pad + (i + 1) * bw, height - pad, p)
        }
    }
}

/** Зебра: штриховка на пересвеченных местах. */
class ZebraView(ctx: Context) : View(ctx) {
    private var mask: BooleanArray? = null
    private var mw = 0
    private var mh = 0
    private val paint = Paint().apply {
        val s = 12
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); strokeWidth = 3f }
        cv.drawColor(0x77FFFFFF)
        cv.drawLine(0f, s.toFloat(), s.toFloat(), 0f, p)
        cv.drawLine(-s / 2f, s / 2f, s / 2f, -s / 2f, p)
        cv.drawLine(s / 2f, s * 1.5f, s * 1.5f, s / 2f, p)
        shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun setMask(m: BooleanArray, w: Int, h: Int) { mask = m; mw = w; mh = h; invalidate() }
    fun clear() { mask = null; invalidate() }

    override fun onDraw(c: Canvas) {
        val m = mask ?: return
        if (mw == 0 || mh == 0) return
        val cw = width.toFloat() / mw
        val ch = height.toFloat() / mh
        for (y in 0 until mh) for (x in 0 until mw) {
            if (m[y * mw + x]) c.drawRect(x * cw, y * ch, (x + 1) * cw, (y + 1) * ch, paint)
        }
    }
}

/** OpenGL: камера → текстура → превью и кодировщик. Поэтому камеру можно менять во время записи. */
class GlPipe {
    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var prevSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var pw = 0
    private var ph = 0
    private var ew = 0
    private var eh = 0
    private var encRot = 0f
    private var encMirror = false
    private var tex = 0
    private var prog = 0
    private var aPos = 0
    private var aTex = 0
    private var uST = 0
    private var uMVP = 0
    private var uProf = 0
    @Volatile var profile = 0
    @Volatile var horizonDeg = 0f
    @Volatile var frontCam = false
    private val corr = FloatArray(16)
    private val tmp = FloatArray(16)
    @Volatile var peaking = false
    @Volatile var texelX = 1f / 1920f
    @Volatile var texelY = 1f / 1080f
    private var uPeak = 0
    private var uTexel = 0
    private val st = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ident = FloatArray(16)
    lateinit var camTexture: SurfaceTexture
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    private fun runSync(f: () -> Unit) {
        val latch = CountDownLatch(1)
        handler.post {
            try { f() } catch (_: Exception) {} finally { latch.countDown() }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    fun start() = runSync {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0)
        cfg = cfgs[0]
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        camTexture = SurfaceTexture(tex)
        camTexture.setOnFrameAvailableListener({ draw() }, handler)

        val vs = "attribute vec4 aPos;\nattribute vec4 aTex;\nuniform mat4 uST;\nuniform mat4 uMVP;\nvarying vec2 vTex;\nvoid main() {\n  gl_Position = uMVP * aPos;\n  vTex = (uST * aTex).xy;\n}\n"
        val fs = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 vTex;\nuniform samplerExternalOES sTex;\nuniform int uProf;\nuniform int uPeak;\nuniform vec2 uTexel;\nvec3 sat(vec3 c, float s) { float l = dot(c, vec3(0.299, 0.587, 0.114)); return mix(vec3(l), c, s); }\nvec3 con(vec3 c, float k) { return (c - 0.5) * k + 0.5; }\nvoid main() {\n  vec3 c = texture2D(sTex, vTex).rgb;\n  if (uProf == 1) { c = con(c, 0.78) + 0.03; c = sat(c, 0.8); }\n  else if (uProf == 2) { c = sat(c, 0.85); c = con(c, 1.12); float l = dot(c, vec3(0.299, 0.587, 0.114)); c += vec3(0.05, 0.0, -0.05) * (l - 0.5) * 2.0; }\n  else if (uProf == 3) { c = sat(c, 1.3); c = con(c, 1.08); }\n  else if (uProf == 4) { float l = dot(c, vec3(0.299, 0.587, 0.114)); c = con(vec3(l), 1.15); }\n  if (uPeak == 1) {\n    float l0 = dot(texture2D(sTex, vTex).rgb, vec3(0.299, 0.587, 0.114));\n    float lx = dot(texture2D(sTex, vTex + vec2(uTexel.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));\n    float ly = dot(texture2D(sTex, vTex + vec2(0.0, uTexel.y)).rgb, vec3(0.299, 0.587, 0.114));\n    if (abs(lx - l0) + abs(ly - l0) > 0.12) c = vec3(1.0, 0.15, 0.15);\n  }\n  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n}\n"
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aTex = GLES20.glGetAttribLocation(prog, "aTex")
        uST = GLES20.glGetUniformLocation(prog, "uST")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uProf = GLES20.glGetUniformLocation(prog, "uProf")
        uPeak = GLES20.glGetUniformLocation(prog, "uPeak")
        uTexel = GLES20.glGetUniformLocation(prog, "uTexel")
        android.opengl.Matrix.setIdentityM(ident, 0)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun quadDraw(m: FloatArray, peak: Boolean = false) {
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniformMatrix4fv(uST, 1, false, st, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, m, 0)
        GLES20.glUniform1i(uProf, profile)
        GLES20.glUniform1i(uPeak, if (peak) 1 else 0)
        GLES20.glUniform2f(uTexel, texelX, texelY)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun draw() {
        try {
            EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
            camTexture.updateTexImage()
            camTexture.getTransformMatrix(st)
            computeCorr()
            if (prevSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, prevSurf, prevSurf, ctx)
                GLES20.glViewport(0, 0, pw, ph)
                quadDraw(corr, peaking)
                EGL14.eglSwapBuffers(dpy, prevSurf)
            }
            if (encSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, encSurf, encSurf, ctx)
                GLES20.glViewport(0, 0, ew, eh)
                android.opengl.Matrix.setIdentityM(mvp, 0)
                if (encMirror) android.opengl.Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
                android.opengl.Matrix.rotateM(mvp, 0, encRot, 0f, 0f, 1f)
                android.opengl.Matrix.multiplyMM(tmp, 0, mvp, 0, corr, 0)
                quadDraw(tmp)
                EGLExt.eglPresentationTimeANDROID(dpy, encSurf, System.nanoTime())
                EGL14.eglSwapBuffers(dpy, encSurf)
            }
        } catch (_: Exception) {}
    }

    /** Блокировка горизонта: поворачиваем кадр против наклона и чуть приближаем, чтобы не было пустых углов. */
    private fun computeCorr() {
        android.opengl.Matrix.setIdentityM(corr, 0)
        val t = horizonDeg
        if (abs(t) < 0.05f || pw <= 0 || ph <= 0) return
        val a = pw.toFloat() / ph
        val th = Math.toRadians(abs(t).toDouble())
        val ratio = max(a, 1f / a)
        val s = (cos(th) + ratio * sin(th)).toFloat()
        android.opengl.Matrix.scaleM(corr, 0, 1f / a, 1f, 1f)
        android.opengl.Matrix.rotateM(corr, 0, if (frontCam) -t else t, 0f, 0f, 1f)
        android.opengl.Matrix.scaleM(corr, 0, a, 1f, 1f)
        android.opengl.Matrix.scaleM(corr, 0, s, s, 1f)
    }

    private fun drop(s: EGLSurface) {
        if (s == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
        EGL14.eglDestroySurface(dpy, s)
    }

    fun setPreview(s: SurfaceTexture?, w: Int, h: Int) = runSync {
        drop(prevSurf)
        prevSurf = EGL14.EGL_NO_SURFACE
        if (s != null) {
            prevSurf = EGL14.eglCreateWindowSurface(dpy, cfg, s, intArrayOf(EGL14.EGL_NONE), 0)
            pw = w; ph = h
        }
    }

    fun setPreviewSize(w: Int, h: Int) { handler.post { pw = w; ph = h } }

    fun setEncoder(s: Surface?, w: Int, h: Int, rot: Float, mirror: Boolean) = runSync {
        drop(encSurf)
        encSurf = EGL14.EGL_NO_SURFACE
        if (s != null) {
            encSurf = EGL14.eglCreateWindowSurface(dpy, cfg, s, intArrayOf(EGL14.EGL_NONE), 0)
            ew = w; eh = h; encRot = rot; encMirror = mirror
        }
    }

    fun setMirror(m: Boolean) { handler.post { encMirror = m } }

    fun release() {
        runSync {
            drop(prevSurf); prevSurf = EGL14.EGL_NO_SURFACE
            drop(encSurf); encSurf = EGL14.EGL_NO_SURFACE
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, pbuf)
            camTexture.release()
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
        thread.quitSafely()
    }
}

class MainActivity : Activity() {

    private data class Cam(
        val openId: String,
        val physicalId: String?,
        val chars: CameraCharacteristics,
        val hidden: Boolean,
        val label: String
    ) {
        val key: String get() = openId + "/" + (physicalId ?: "")
    }

    private data class Lens(val title: String, val cam: Cam, val zoom: Float = 1f)
    private data class Cfg(val cam: Cam, val size: Size, val fps: Range<Int>, val preview: Size)

    companion object {
        const val MATCH = -1
        const val WRAP = -2
        val DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert
        const val RED = 0xFFE53935.toInt()
        const val YELLOW = 0xFFFFD54F.toInt()
        const val AMBER = 0xFFFFA000.toInt()
    }

    private val gridNames = listOf(
        "Выкл", "Правило третей", "Сетка 4×4", "Золотое сечение",
        "Золотая спираль", "Золотая спираль (зеркально)", "Трети + диагонали", "Центр"
    )
    private val profileNames = listOf("Стандарт", "Плоский (под цветокоррекцию)", "Кино", "Яркий", "Ч/Б")
    private val profileShort = listOf("стандарт", "плоский", "кино", "яркий", "Ч/Б")
    private val guideNames = listOf("Выкл", "9:16 (Reels / Shorts)", "1:1", "4:5 (лента)", "16:9", "2.39:1 (кино)")
    private val presetNames = listOf("Влог", "Кино", "Таймлапс", "Мой")
    private val bitrateNames = listOf("Эконом", "Стандарт", "Максимум")
    private val tlIntervals = floatArrayOf(0f, 0.5f, 1f, 2f, 5f)
    private val isoValues = intArrayOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
    private val shutterDens = intArrayOf(25, 30, 40, 48, 50, 60, 80, 100, 120, 125, 160, 200, 250, 320,
        400, 500, 640, 800, 1000, 1250, 1600, 2000, 3200, 4000, 8000)

    private val wbList = listOf(
        Triple(CaptureRequest.CONTROL_AWB_MODE_AUTO, "Авто", "Авто"),
        Triple(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, "Дневной свет", "Солнце"),
        Triple(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "Облачно", "Облачно"),
        Triple(CaptureRequest.CONTROL_AWB_MODE_SHADE, "Тень", "Тень"),
        Triple(CaptureRequest.CONTROL_AWB_MODE_TWILIGHT, "Сумерки", "Сумерки"),
        Triple(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, "Лампа накаливания", "Лампа"),
        Triple(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, "Люминесцентная лампа", "Люмин."),
        Triple(CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT, "Тёплая люминесцентная", "Тёпл.")
    )

    private lateinit var mgr: CameraManager
    private lateinit var prefs: SharedPreferences
    private lateinit var sensors: SensorManager
    private lateinit var previewBox: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var grid: GridView
    private lateinit var level: LevelView
    private lateinit var zebra: ZebraView
    private lateinit var hist: HistView
    private lateinit var meter: MeterView
    private lateinit var ring: View
    private lateinit var flashView: View
    private lateinit var lockLabel: TextView
    private lateinit var tlBadge: TextView
    private lateinit var statusText: TextView
    private lateinit var readout: TextView
    private lateinit var timerText: TextView
    private lateinit var resChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var isoChip: TextView
    private lateinit var shutterChip: TextView
    private lateinit var wbChip: TextView
    private lateinit var evChip: TextView
    private lateinit var stabChip: TextView
    private lateinit var torchChip: TextView
    private lateinit var profileChip: TextView
    private lateinit var horizonChip: TextView
    private lateinit var timerChip: TextView
    private lateinit var guide: GuideView
    private lateinit var countText: TextView
    private lateinit var focusChip: TextView
    private lateinit var starChip: TextView
    private lateinit var focusPanel: LinearLayout
    private lateinit var focusBar: SeekBar
    private lateinit var lockOverlay: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var wheel: ZoomWheel
    private lateinit var lensBar: LinearLayout
    private lateinit var recOuter: FrameLayout
    private lateinit var recInner: View
    private lateinit var pauseBtn: TextView
    private lateinit var galleryBtn: ImageView
    private lateinit var photoBtn: TextView

    private val cams = mutableListOf<Cam>()
    private val lenses = mutableListOf<Lens>()
    private var mainEq = 1f
    private var curCam: Cam? = null
    private var curMap: StreamConfigurationMap? = null
    private var sizes = listOf<Size>()
    private var supportedSizes = setOf<Size>()
    private var curSize = Size(1920, 1080)
    private var fpsRanges = listOf<Range<Int>>()
    private var supportedFps = setOf<Range<Int>>()
    private var curFps = Range(30, 30)

    // ручные настройки
    @Volatile private var zoom = 1f
    @Volatile private var useZoomRatio = false
    @Volatile private var afPoint: Pair<Float, Float>? = null
    @Volatile private var aeAfLocked = false
    @Volatile private var wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    @Volatile private var wbLock = false
    @Volatile private var evIndex = 0
    @Volatile private var torch = false
    @Volatile private var stab = true
    @Volatile private var distCorr = true
    @Volatile private var manIso: Int? = null
    @Volatile private var manExp: Long? = null
    @Volatile private var lastIso = 100
    @Volatile private var lastExp = 10_000_000L
    @Volatile private var shownIso = 0
    @Volatile private var shownExp = 0L
    private var codecHevc = true
    private var bitrateLevel = 1
    private var tlIndex = 0
    private var levelOn = true
    private var zebraOn = false
    private var histOn = false
    private var profile = 0
    private var horizonLock = false
    private var timerSec = 0
    private var counting = false
    private var countLeft = 0
    @Volatile private var manFocus: Float? = null
    private var focusA: Float? = null
    private var focusB: Float? = null
    private var peakOn = true
    private var audioMode = 0
    private var micType = 0
    private var lockOnRec = false
    private var dimOnRec = false
    private var dimmed = false
    private var mirrorFront = false
    private var intervalSec = 0
    private var seriesOn = false
    private val markers = mutableListOf<Long>()
    private var markerBase = ""
    private var pullAnim: ValueAnimator? = null
    private var resumed = false
    private var smoothRoll = 0f

    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var activeCfg: Cfg? = null
    private var activeTemplate = CameraDevice.TEMPLATE_RECORD
    @Volatile private var activeTargets = listOf<Surface>()
    private lateinit var gl: GlPipe
    private lateinit var camSurface: Surface
    private var photoReader: ImageReader? = null
    @Volatile private var photoReady = false
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var lastVideo: Uri? = null
    @Volatile private var recording = false
    @Volatile private var paused = false
    private var pausedAt = 0L
    private var pausedTotal = 0L
    @Volatile private var pending: Cfg? = null

    private val thread = HandlerThread("cam").apply { start() }
    private val bg = Handler(thread.looper)
    private val exec = Executor { bg.post(it) }
    private val restartTask = Runnable { doRestart() }
    private val applyTask = Runnable { applyControls(false) }
    private val main = Handler(Looper.getMainLooper())
    private var recStart = 0L
    @Volatile private var devOrient = 0
    private var lastReadout = 0L
    private lateinit var orient: OrientationEventListener
    private val hideStatus = Runnable { statusText.visibility = View.GONE }
    private val lensRefresh = Runnable { renderLenses() }
    private val dimTask = Runnable { if (recording && dimOnRec) setDim(true) }
    private val seriesTask = object : Runnable {
        override fun run() {
            if (!seriesOn) return
            takePhoto()
            main.postDelayed(this, intervalSec * 1000L)
        }
    }
    private val countTask = object : Runnable {
        override fun run() {
            if (!counting) return
            if (countLeft <= 0) {
                counting = false
                countText.visibility = View.GONE
                bg.post { startRec() }
                return
            }
            countText.text = countLeft.toString()
            countText.visibility = View.VISIBLE
            countLeft--
            main.postDelayed(this, 1000)
        }
    }
    private val hideRing = Runnable { if (!aeAfLocked) ring.visibility = View.INVISIBLE }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val now = SystemClock.elapsedRealtime()
            val ms = now - recStart - pausedTotal - (if (paused) now - pausedAt else 0L)
            val s = ms / 1000
            timerText.text = String.format(Locale.US, if (paused) "❚❚ %02d:%02d" else "● %02d:%02d", s / 60, s % 60)
            main.postDelayed(this, 500)
        }
    }

    private val meterTask = object : Runnable {
        override fun run() {
            if (!recording) { meter.level = 0f; return }
            val amp = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
            val lv = if (amp > 0) ((20 * log10(amp / 32767.0) + 50) / 50).toFloat().coerceIn(0f, 1f) else 0f
            meter.level = if (paused) 0f else max(lv, meter.level * 0.7f)
            main.postDelayed(this, 100)
        }
    }

    private val analyze = object : Runnable {
        override fun run() {
            if (!zebraOn && !histOn) return
            try {
                if (texture.isAvailable && texture.width > 0 && texture.height > 0) {
                    val w = 72
                    val h = max(1, (w * texture.height.toFloat() / texture.width).toInt())
                    val bmp = texture.getBitmap(w, h)
                    if (bmp != null) {
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        bmp.recycle()
                        val bins = FloatArray(64)
                        val mask = BooleanArray(w * h)
                        for (i in px.indices) {
                            val c = px[i]
                            val y = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
                            bins[y * 64 / 256] += 1f
                            mask[i] = y >= 242
                        }
                        if (histOn) hist.setData(bins)
                        if (zebraOn) zebra.setMask(mask, w, h)
                    }
                }
            } catch (_: Exception) {}
            main.postDelayed(this, 300)
        }
    }

    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val roll = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
            smoothRoll = if (abs(roll - smoothRoll) > 180f) roll else smoothRoll * 0.8f + roll * 0.2f
            level.roll = smoothRoll
            if (horizonLock) {
                val base = (smoothRoll / 90f).roundToInt() * 90f
                gl.horizonDeg = (smoothRoll - base).coerceIn(-25f, 25f)
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val resultCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            val iso = res.get(CaptureResult.SENSOR_SENSITIVITY)
            val ex = res.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (iso != null) { shownIso = iso; if (manIso == null) lastIso = iso }
            if (ex != null) { shownExp = ex; if (manExp == null) lastExp = ex }
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadout > 500) { lastReadout = now; ui { updateReadout() } }
        }
    }

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mgr = getSystemService(CameraManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        prefs = getSharedPreferences("widecam", MODE_PRIVATE)
        stab = prefs.getBoolean("stab", true)
        distCorr = prefs.getBoolean("dist", true)
        codecHevc = prefs.getBoolean("hevc", true)
        bitrateLevel = prefs.getInt("bitrate", 1).coerceIn(0, 2)
        levelOn = prefs.getBoolean("level", true)
        zebraOn = prefs.getBoolean("zebra", false)
        histOn = prefs.getBoolean("hist", false)
        gl = GlPipe()
        gl.start()
        camSurface = Surface(gl.camTexture)
        buildUi()
        grid.mode = prefs.getInt("grid", 0)
        guide.mode = prefs.getInt("guide", 0)
        profile = prefs.getInt("profile", 0).coerceIn(0, 4)
        gl.profile = profile
        horizonLock = prefs.getBoolean("horizon", false)
        timerSec = prefs.getInt("timer", 0)
        peakOn = prefs.getBoolean("peak", true)
        audioMode = prefs.getInt("audioMode", 0)
        micType = prefs.getInt("mic", 0)
        lockOnRec = prefs.getBoolean("lockRec", false)
        dimOnRec = prefs.getBoolean("dimRec", false)
        mirrorFront = prefs.getBoolean("mirrorFront", false)
        intervalSec = prefs.getInt("interval", 0)
        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = ((o + 45) / 90 * 90) % 360
                if (r != devOrient) { devOrient = r; rotateUi(r) }
            }
        }
        bg.post { loadLastVideo() }
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) init()
        else requestPermissions(need, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) init()
        else setStatus("Нужны разрешения на камеру и микрофон", 10000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        orient.enable()
        applyLevel()
        applyAnalysis()
        if (cams.isNotEmpty() && texture.isAvailable) restartPreview()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        seriesOn = false
        main.removeCallbacks(seriesTask)
        orient.disable()
        sensors.unregisterListener(levelListener)
        main.removeCallbacks(analyze)
        bg.removeCallbacks(restartTask)
        bg.post {
            if (recording) stopRec()
            closeCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.post {
            try { photoReader?.close() } catch (_: Exception) {}
        }
        thread.quitSafely()
        gl.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) toggleRec()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- интерфейс ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(100).toFloat(); setColor(color)
    }

    private fun chip() = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; background = pill(0x33FFFFFF)
        setPadding(dp(14), dp(7), dp(14), dp(7))
    }

    private fun roundBtn(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x55FFFFFF) }
    }

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // превью и всё, что поверх него
        previewBox = FrameLayout(this)
        texture = TextureView(this)
        previewBox.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(w, h)
                gl.setPreview(st, w, h)
                restartPreview()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(w, h)
                gl.setPreviewSize(w, h)
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                gl.setPreview(null, 0, 0)
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        zebra = ZebraView(this)
        previewBox.addView(zebra, FrameLayout.LayoutParams(MATCH, MATCH))
        grid = GridView(this)
        previewBox.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        guide = GuideView(this)
        previewBox.addView(guide, FrameLayout.LayoutParams(MATCH, MATCH))
        level = LevelView(this)
        previewBox.addView(level, FrameLayout.LayoutParams(MATCH, MATCH))
        ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), YELLOW)
            }
            visibility = View.INVISIBLE
        }
        previewBox.addView(ring, FrameLayout.LayoutParams(dp(72), dp(72)))
        countText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 96f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(12f, 0f, 0f, Color.BLACK); visibility = View.GONE
        }
        previewBox.addView(countText, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        hist = HistView(this).apply { visibility = View.GONE }
        previewBox.addView(hist, FrameLayout.LayoutParams(dp(120), dp(56), Gravity.TOP or Gravity.START)
            .apply { topMargin = dp(10); leftMargin = dp(10) })

        val topInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        timerText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = pill(RED); setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE; text = "● 00:00"
        }
        meter = MeterView(this).apply { visibility = View.GONE }
        tlBadge = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            background = pill(YELLOW); setPadding(dp(10), dp(3), dp(10), dp(3)); visibility = View.GONE
        }
        lockLabel = TextView(this).apply {
            text = "Фокус и экспозиция 🔒"; setTextColor(Color.BLACK); textSize = 12f
            typeface = Typeface.DEFAULT_BOLD; background = pill(YELLOW)
            setPadding(dp(10), dp(3), dp(10), dp(3)); visibility = View.GONE
        }
        topInfo.addView(timerText)
        topInfo.addView(meter, lp(dp(90), dp(5)).apply { topMargin = dp(5) })
        topInfo.addView(tlBadge, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        topInfo.addView(lockLabel, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        previewBox.addView(topInfo, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(10) })
        root.addView(previewBox, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(56) })
        setupTouch()

        // верхняя лента
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        resChip = chip().apply { setOnClickListener { pickSize() } }
        fpsChip = chip().apply { setOnClickListener { pickFps() } }
        isoChip = chip().apply { setOnClickListener { pickIso() } }
        shutterChip = chip().apply { setOnClickListener { pickShutter() } }
        wbChip = chip().apply { setOnClickListener { pickWb() } }
        evChip = chip().apply { setOnClickListener { pickEv() } }
        stabChip = chip().apply { setOnClickListener { toggleStab() } }
        torchChip = chip().apply { text = "Фонарь"; setOnClickListener { toggleTorch() } }
        profileChip = chip().apply { setOnClickListener { pickProfile() } }
        horizonChip = chip().apply { text = "Горизонт"; setOnClickListener { setHorizon(!horizonLock) } }
        timerChip = chip().apply { setOnClickListener { cycleTimer() } }
        focusChip = chip().apply { setOnClickListener { toggleFocusPanel() } }
        starChip = chip().apply { text = "★ Метка"; setTextColor(YELLOW); visibility = View.GONE; setOnClickListener { addMarker() } }
        settingsBtn = chip().apply { text = "⚙"; textSize = 17f; setOnClickListener { showSettings() } }
        listOf(starChip, resChip, fpsChip, focusChip, isoChip, shutterChip, wbChip, evChip, profileChip, stabChip, horizonChip, timerChip, torchChip, settingsBtn)
            .forEachIndexed { i, v -> row.addView(v, lp(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(8) }) }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        scroll.addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))

        // низ
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(30), 0, dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), 0x00000000)
            )
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            background = pill(0xCC222222.toInt()); setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        bottom.addView(statusText, lp(WRAP, WRAP).apply {
            bottomMargin = dp(8); leftMargin = dp(24); rightMargin = dp(24)
        })
        readout = TextView(this).apply {
            setTextColor(0xBBFFFFFF.toInt()); textSize = 12f; gravity = Gravity.CENTER
        }
        bottom.addView(readout, lp(WRAP, WRAP))
        focusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4)); visibility = View.GONE
        }
        focusBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) setManualFocus(p / 1000f) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        focusPanel.addView(TextView(this).apply { text = "∞"; setTextColor(Color.WHITE); textSize = 15f })
        focusPanel.addView(focusBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        focusPanel.addView(TextView(this).apply { text = "близко"; setTextColor(Color.WHITE); textSize = 12f })
        listOf("A", "B", "A↔B").forEach { label ->
            val b = chip().apply {
                text = label; textSize = 12f; setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener {
                    when (label) { "A" -> setPoint(true); "B" -> setPoint(false); else -> pullFocus() }
                }
            }
            focusPanel.addView(b, lp(WRAP, WRAP).apply { leftMargin = dp(5) })
        }
        bottom.addView(focusPanel, lp(MATCH, WRAP))
        wheel = ZoomWheel(this).apply {
            onChange = { z ->
                zoom = z; bg.removeCallbacks(applyTask); bg.post(applyTask)
                main.removeCallbacks(lensRefresh); main.postDelayed(lensRefresh, 150)
            }
        }
        bottom.addView(wheel, lp(MATCH, dp(60)).apply { bottomMargin = dp(4) })
        lensBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = pill(0x55000000); setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bottom.addView(lensBar, lp(WRAP, WRAP).apply { bottomMargin = dp(16) })

        val recRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val leftSlot = FrameLayout(this)
        galleryBtn = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0x55FFFFFF); setStroke(dp(2), Color.WHITE)
            }
            clipToOutline = true
            setOnClickListener { openLast() }
        }
        pauseBtn = roundBtn("❚❚").apply { visibility = View.GONE; setOnClickListener { togglePause() } }
        leftSlot.addView(galleryBtn, FrameLayout.LayoutParams(MATCH, MATCH))
        leftSlot.addView(pauseBtn, FrameLayout.LayoutParams(MATCH, MATCH))
        photoBtn = roundBtn("📷").apply { textSize = 20f; setOnClickListener { onPhotoBtn() } }
        recOuter = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(4), Color.WHITE)
            }
            setOnClickListener { toggleRec() }
        }
        recInner = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(RED) }
        }
        recOuter.addView(recInner, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        recRow.addView(leftSlot, lp(dp(54), dp(54)))
        recRow.addView(recOuter, lp(dp(78), dp(78)).apply { leftMargin = dp(40); rightMargin = dp(40) })
        recRow.addView(photoBtn, lp(dp(54), dp(54)))
        bottom.addView(recRow, lp(MATCH, WRAP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        flashView = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        root.addView(flashView, FrameLayout.LayoutParams(MATCH, MATCH))
        flashView.isClickable = false
        lockOverlay = TextView(this).apply {
            text = "🔒 Экран заблокирован\nудерживай палец, чтобы разблокировать\nкнопка громкости — стоп"
            setTextColor(0xCCFFFFFF.toInt()); textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPadding(0, 0, 0, dp(140)); setBackgroundColor(0x01000000); visibility = View.GONE
            isClickable = true
            setOnLongClickListener { visibility = View.GONE; setStatus("Экран разблокирован", 1200); true }
        }
        root.addView(lockOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { focusAt(e.x, e.y, false); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { resetFocus(true); return true }
            override fun onLongPress(e: MotionEvent) { focusAt(e.x, e.y, true) }
        })
        val sg = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                wheel.set(zoom * detector.scaleFactor); return true
            }
        })
        previewBox.setOnTouchListener { _, e ->
            sg.onTouchEvent(e)
            if (!sg.isInProgress && e.pointerCount == 1) gd.onTouchEvent(e)
            true
        }
    }

    private fun uiDeg(r: Int) = when (r) { 90 -> -90f; 180 -> 180f; 270 -> 90f; else -> 0f }

    private fun pickLens(l: Lens) {
        if (curCam?.key == l.cam.key) {
            wheel.set(l.zoom)
            renderLenses()
        } else {
            selectCam(l.cam, l.zoom)
        }
    }

    /** Поворачиваем круглые кнопки вслед за телефоном. */
    private fun rotateUi(r: Int) = ui {
        val deg = uiDeg(r)
        guide.landscape = r == 90 || r == 270
        guide.invalidate()
        val views = mutableListOf<View>(settingsBtn, pauseBtn, galleryBtn, photoBtn)
        for (i in 0 until lensBar.childCount) views += lensBar.getChildAt(i)
        views.forEach { it.animate().rotation(deg).setDuration(200).start() }
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun setStatus(msg: String, ms: Long = 3000) = ui {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
        main.removeCallbacks(hideStatus)
        main.postDelayed(hideStatus, ms)
    }

    private fun setRecUi(rec: Boolean) = ui {
        val d = recInner.background as GradientDrawable
        val size = if (rec) dp(30) else dp(60)
        d.cornerRadius = if (rec) dp(8).toFloat() else dp(30).toFloat()
        recInner.layoutParams = (recInner.layoutParams as FrameLayout.LayoutParams).apply { width = size; height = size }
        timerText.visibility = if (rec) View.VISIBLE else View.GONE
        meter.visibility = if (rec && tlIndex == 0) View.VISIBLE else View.GONE
        (timerText.background as GradientDrawable).setColor(RED)
        pauseBtn.visibility = if (rec) View.VISIBLE else View.GONE
        galleryBtn.visibility = if (rec) View.INVISIBLE else View.VISIBLE
        pauseBtn.text = "❚❚"
        val a = if (rec) 0.3f else 1f
        listOf<View>(resChip, fpsChip, stabChip).forEach { it.alpha = a; it.isEnabled = !rec }
        main.removeCallbacks(tick)
        main.removeCallbacks(meterTask)
        starChip.visibility = if (rec) View.VISIBLE else View.GONE
        lockOverlay.visibility = if (rec && lockOnRec) View.VISIBLE else View.GONE
        main.removeCallbacks(dimTask)
        if (rec && dimOnRec) main.postDelayed(dimTask, 5000) else if (dimmed) setDim(false)
        if (rec) {
            recStart = SystemClock.elapsedRealtime(); pausedTotal = 0L; paused = false
            timerText.text = "● 00:00"
            main.post(tick)
            if (tlIndex == 0) main.post(meterTask)
        }
    }

    private fun sizeName(s: Size): String = when {
        s.width == 3840 && s.height == 2160 -> "4K"
        s.width == 1920 && s.height == 1080 -> "1080p"
        s.width == 1280 && s.height == 720 -> "720p"
        else -> "" + s.width + "×" + s.height
    }

    private fun fpsName(r: Range<Int>) =
        if (r.lower == r.upper) "" + r.upper + " к/с" else "" + r.lower + "–" + r.upper + " к/с"

    private fun expName(ns: Long): String =
        if (ns <= 0L) "?" else if (ns >= 500_000_000L) String.format(Locale.US, "%.1f с", ns / 1e9)
        else "1/" + (1e9 / ns).roundToInt()

    private fun evStep(): Float = curCam?.chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

    private fun evName(i: Int): String {
        val v = i * evStep()
        return if (abs(v) < 0.05f) "±0" else String.format(Locale.US, "%+.1f", v)
    }

    private fun onOff(b: Boolean) = if (b) "вкл" else "выкл"

    private fun tlName(): String {
        val iv = tlIntervals[tlIndex]
        if (iv <= 0f) return "выкл"
        val s = if (iv < 1f) "0.5" else iv.roundToInt().toString()
        return "1 кадр / " + s + " с (×" + (30 * iv).roundToInt() + ")"
    }

    private fun timelapseRate(): Float {
        val iv = tlIntervals[tlIndex]
        return if (iv <= 0f) 0f else 1f / iv
    }

    private fun hasStab(c: CameraCharacteristics): Boolean {
        val eis = c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        val ois = c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: intArrayOf()
        return CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON in eis ||
            CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON in ois
    }

    private fun hasDist(c: CameraCharacteristics): Boolean {
        val m = c[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES] ?: intArrayOf()
        return m.any { it != CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF }
    }

    private fun manualOk(c: CameraCharacteristics): Boolean =
        c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

    private fun isManual() = manIso != null || manExp != null

    private fun updateChips() {
        resChip.text = sizeName(curSize) + if (curSize in supportedSizes) "" else " ⚠"
        fpsChip.text = fpsName(curFps) + if (curFps in supportedFps) "" else " ⚠"
        isoChip.text = "ISO " + (manIso?.toString() ?: "авто")
        isoChip.setTextColor(if (manIso != null) YELLOW else Color.WHITE)
        shutterChip.text = "Выдержка " + (manExp?.let { expName(it) } ?: "авто")
        shutterChip.setTextColor(if (manExp != null) YELLOW else Color.WHITE)
        val c = curCam?.chars
        val stabOk = c != null && hasStab(c)
        stabChip.text = if (!stabOk) "Стаб —" else if (stab) "Стаб вкл" else "Стаб выкл"
        stabChip.setTextColor(if (stabOk && stab) YELLOW else Color.WHITE)
        val wbS = wbList.firstOrNull { it.first == wbMode }?.third ?: "Авто"
        wbChip.text = "ББ " + wbS + if (wbLock) " 🔒" else ""
        wbChip.setTextColor(if (wbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO || wbLock) YELLOW else Color.WHITE)
        evChip.text = if (isManual()) "EV —" else "EV " + evName(evIndex)
        evChip.setTextColor(if (!isManual() && evIndex != 0) YELLOW else Color.WHITE)
        torchChip.setTextColor(if (torch) YELLOW else Color.WHITE)
        tlBadge.text = "ТАЙМЛАПС " + tlName().substringAfter("(").removeSuffix(")") + " · без звука"
        tlBadge.visibility = if (tlIndex > 0) View.VISIBLE else View.GONE
        profileChip.text = "Цвет: " + profileShort[profile]
        profileChip.setTextColor(if (profile != 0) YELLOW else Color.WHITE)
        horizonChip.setTextColor(if (horizonLock) YELLOW else Color.WHITE)
        timerChip.text = if (timerSec == 0) "⏱ выкл" else "⏱ " + timerSec + " с"
        timerChip.setTextColor(if (timerSec != 0) YELLOW else Color.WHITE)
        focusChip.text = if (manFocus != null) "Фокус MF" else "Фокус авто"
        focusChip.setTextColor(if (manFocus != null) YELLOW else Color.WHITE)
        updateReadout()
    }

    private fun remainingText(): String {
        val cfg = activeCfg ?: pending ?: return ""
        @Suppress("DEPRECATION")
        val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (e: Exception) { return "" }
        val bps = bitrateFor(cfg).toLong() / 8 + (if (tlIndex == 0) 24_000L else 0L)
        val sec = free / max(1L, bps)
        return if (sec >= 3600) "осталось ≈ " + sec / 3600 + " ч " + (sec % 3600) / 60 + " мин"
        else "осталось ≈ " + sec / 60 + " мин"
    }

    private fun updateReadout() {
        val parts = mutableListOf<String>()
        try {
            val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bat != null) {
                val lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                if (lvl >= 0) parts += "🔋 " + (lvl * 100 / max(1, sc)) + "% " + temp.roundToInt() + "°C"
            }
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) parts += "🌡 телефон греется"
        } catch (_: Exception) {}
        if (shownIso > 0) parts += "ISO " + shownIso
        if (shownExp > 0) parts += expName(shownExp)
        parts += (if (codecHevc) "HEVC" else "H.264") + " " + (bitrateFor(activeCfg ?: pending ?: return) / 1_000_000) + " Мбит/с"
        val rem = remainingText()
        if (rem.isNotEmpty()) parts += rem
        readout.text = parts.joinToString("  ·  ")
    }

    private fun renderLenses() {
        lensBar.removeAllViews()
        for (l in lenses) {
            val sel = l.cam.key == curCam?.key && abs(zoom - l.zoom) < 0.05f
            val front = l.title == "Фронт"
            val tv = TextView(this).apply {
                text = if (sel && !front) l.title + "×" else l.title
                setTextColor(if (sel) YELLOW else Color.WHITE)
                textSize = if (front) 12f else 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = pill(if (sel) 0x88000000.toInt() else 0x00000000)
                rotation = uiDeg(devOrient)
                setOnClickListener { pickLens(l) }
            }
            lensBar.addView(tv, lp(if (front) dp(64) else dp(48), dp(44)).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
    }

    // ---------- фокус, экспозиция, баланс белого ----------

    private fun canFocus(c: CameraCharacteristics) =
        (c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f) > 0f

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = curCam ?: return
        if (previewBox.width == 0 || previewBox.height == 0) return
        afPoint = Pair(x / previewBox.width, y / previewBox.height)
        aeAfLocked = lock
        val s = dp(72)
        ring.x = x - s / 2f; ring.y = y - s / 2f
        (ring.background as GradientDrawable).setStroke(dp(if (lock) 3 else 2), YELLOW)
        ring.visibility = View.VISIBLE
        ring.scaleX = 1.4f; ring.scaleY = 1.4f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        main.removeCallbacks(hideRing)
        if (!lock) main.postDelayed(hideRing, 2000)
        lockLabel.visibility = if (lock) View.VISIBLE else View.GONE
        val f = canFocus(cam.chars)
        setStatus(when {
            lock -> "Фокус и экспозиция зафиксированы · двойной тап — сброс"
            !f -> "У этого объектива фокус фиксированный — замеряю свет по точке"
            else -> "Фокус по точке · двойной тап — авто"
        }, 2500)
        bg.post { applyControls(f) }
    }

    private fun resetFocus(say: Boolean) {
        afPoint = null; aeAfLocked = false
        ring.visibility = View.INVISIBLE; lockLabel.visibility = View.GONE
        if (say) setStatus("Автофокус и автоэкспозиция", 1500)
        bg.post(applyTask)
    }

    private fun pickWb() {
        val c = curCam?.chars ?: return
        val avail = (c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]
            ?: intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)).toSet()
        val modes = wbList.filter { it.first in avail }
        val lockOk = c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true
        val labels = modes.map { (if (it.first == wbMode && !wbLock) "✓ " else "") + it.second }.toMutableList()
        if (lockOk) labels += if (wbLock) "Снять фиксацию баланса" else "Зафиксировать текущий баланс 🔒"
        AlertDialog.Builder(this, DIALOG).setTitle("Баланс белого")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < modes.size) { wbMode = modes[i].first; wbLock = false }
                else { if (!wbLock) wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO; wbLock = !wbLock }
                updateChips(); bg.post(applyTask)
            }.show()
    }

    private fun pickEv() {
        if (isManual()) { setStatus("При ручных ISO/выдержке экспокоррекция не нужна"); return }
        val c = curCam?.chars ?: return
        val r = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
        val st = evStep()
        if (r == null || r.lower == r.upper || st <= 0f) { setStatus("Экспокоррекция недоступна"); return }
        val every = max(1, (0.33f / st).roundToInt())
        val idx = (r.lower..r.upper).filter { it % every == 0 }.reversed()
        AlertDialog.Builder(this, DIALOG).setTitle("Экспокоррекция")
            .setItems(idx.map { (if (it == evIndex) "✓ " else "") + evName(it) }.toTypedArray()) { _, i ->
                evIndex = idx[i]; updateChips(); bg.post(applyTask)
            }.show()
    }

    private fun pickIso() {
        val c = curCam?.chars ?: return
        if (!manualOk(c)) { setStatus("Ручная экспозиция недоступна для этого объектива"); return }
        val r = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] ?: return
        val vals = isoValues.filter { it >= r.lower && it <= r.upper }
        val labels = listOf((if (manIso == null) "✓ " else "") + "Авто") +
            vals.map { (if (it == manIso) "✓ " else "") + "ISO " + it }
        AlertDialog.Builder(this, DIALOG).setTitle("ISO")
            .setItems(labels.toTypedArray()) { _, i ->
                manIso = if (i == 0) null else vals[i - 1]
                updateChips(); bg.post(applyTask)
            }.show()
    }

    private fun pickShutter() {
        val c = curCam?.chars ?: return
        if (!manualOk(c)) { setStatus("Ручная экспозиция недоступна для этого объектива"); return }
        val r = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] ?: return
        val fps = curFps.upper
        val dens = shutterDens.filter {
            val ns = 1_000_000_000L / it
            ns >= r.lower && ns <= r.upper && it >= fps
        }
        val labels = listOf((if (manExp == null) "✓ " else "") + "Авто") + dens.map {
            val ns = 1_000_000_000L / it
            (if (ns == manExp) "✓ " else "") + "1/" + it + if (it == fps * 2) "   ← правило 180° для " + fps + " к/с" else ""
        }
        AlertDialog.Builder(this, DIALOG).setTitle("Выдержка")
            .setItems(labels.toTypedArray()) { _, i ->
                manExp = if (i == 0) null else 1_000_000_000L / dens[i - 1]
                updateChips(); bg.post(applyTask)
            }.show()
    }

    private fun pickGrid() {
        AlertDialog.Builder(this, DIALOG).setTitle("Сетка")
            .setItems(gridNames.mapIndexed { i, n -> (if (i == grid.mode) "✓ " else "") + n }.toTypedArray()) { _, i ->
                grid.mode = i
                prefs.edit().putInt("grid", i).apply()
            }.show()
    }

    private fun pickBitrate() {
        if (recording) { setStatus("Нельзя менять во время записи"); return }
        AlertDialog.Builder(this, DIALOG).setTitle("Битрейт")
            .setItems(bitrateNames.mapIndexed { i, n -> (if (i == bitrateLevel) "✓ " else "") + n }.toTypedArray()) { _, i ->
                bitrateLevel = i
                prefs.edit().putInt("bitrate", i).apply()
                updateChips()
            }.show()
    }

    private fun pickTimelapse() {
        if (recording) { setStatus("Нельзя менять во время записи"); return }
        val labels = tlIntervals.indices.map { i ->
            val save = tlIndex; tlIndex = i; val n = tlName(); tlIndex = save
            (if (i == tlIndex) "✓ " else "") + if (i == 0) "Выкл" else n
        }
        AlertDialog.Builder(this, DIALOG).setTitle("Таймлапс")
            .setItems(labels.toTypedArray()) { _, i -> tlIndex = i; updateChips() }.show()
    }

    private fun toggleStab() {
        if (recording) return
        val c = curCam?.chars ?: return
        if (!hasStab(c)) { setStatus("У этого объектива нет стабилизации"); return }
        stab = !stab
        prefs.edit().putBoolean("stab", stab).apply()
        setStatus(if (stab) "Стабилизация включена (кадр чуть уже)" else "Стабилизация выключена — максимальный угол", 2500)
        updateChips()
        restartPreview()
    }

    private fun toggleDist() {
        if (recording) { setStatus("Нельзя менять во время записи"); return }
        val c = curCam?.chars ?: return
        if (!hasDist(c)) { setStatus("Драйвер не умеет исправлять дисторсию у этого объектива", 4000); return }
        distCorr = !distCorr
        prefs.edit().putBoolean("dist", distCorr).apply()
        setStatus(if (distCorr) "Дисторсия исправляется — прямые линии ровные" else "Без исправления — максимальный угол, края «бочкой»", 3000)
        bg.post(applyTask)
    }

    private fun toggleTorch() {
        val c = curCam?.chars ?: return
        if (c[CameraCharacteristics.FLASH_INFO_AVAILABLE] != true) {
            setStatus("У этого объектива нет вспышки — она работает на 1×"); return
        }
        torch = !torch
        updateChips(); bg.post(applyTask)
    }

    private fun toggleCodec() {
        if (recording) { setStatus("Нельзя менять во время записи"); return }
        codecHevc = !codecHevc
        prefs.edit().putBoolean("hevc", codecHevc).apply()
        setStatus(if (codecHevc) "HEVC: файлы меньше, но не всё их открывает" else "H.264: открывается везде, файлы больше", 3000)
        updateChips()
        restartPreview()
    }

    private fun applyLevel() {
        level.visibility = if (levelOn) View.VISIBLE else View.GONE
        sensors.unregisterListener(levelListener)
        if ((levelOn || horizonLock) && resumed) {
            val s = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (s != null) sensors.registerListener(levelListener, s, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun applyAnalysis() {
        hist.visibility = if (histOn) View.VISIBLE else View.GONE
        if (!zebraOn) zebra.clear()
        main.removeCallbacks(analyze)
        if ((zebraOn || histOn) && resumed) main.post(analyze)
    }

    private fun aspectTag(s: Size): String {
        val r = s.width.toFloat() / s.height
        return when {
            abs(r - 4f / 3f) < 0.02f -> "   4:3 · Open Gate, макс. угол"
            abs(r - 16f / 9f) < 0.02f -> "   16:9"
            abs(r - 1f) < 0.02f -> "   1:1"
            else -> ""
        }
    }

    private fun setProfile(v: Int) {
        profile = v.coerceIn(0, profileNames.size - 1)
        gl.profile = profile
        prefs.edit().putInt("profile", profile).apply()
        updateChips()
    }

    private fun pickProfile() {
        AlertDialog.Builder(this, DIALOG).setTitle("Цветовой профиль")
            .setItems(profileNames.mapIndexed { i, n -> (if (i == profile) "✓ " else "") + n }.toTypedArray()) { _, i ->
                setProfile(i)
                if (i == 1) setStatus("Плоский профиль: картинка бледная — это нормально, цвет добавляется на монтаже", 4000)
            }.show()
    }

    private fun pickGuide() {
        AlertDialog.Builder(this, DIALOG).setTitle("Рамки кадрирования")
            .setItems(guideNames.mapIndexed { i, n -> (if (i == guide.mode) "✓ " else "") + n }.toTypedArray()) { _, i ->
                guide.mode = i
                prefs.edit().putInt("guide", i).apply()
            }.show()
    }

    private fun setHorizon(on: Boolean) {
        horizonLock = on
        prefs.edit().putBoolean("horizon", on).apply()
        if (!on) gl.horizonDeg = 0f
        applyLevel()
        updateChips()
        setStatus(if (on) "Горизонт зафиксирован — при наклоне кадр слегка приближается" else "Блокировка горизонта выключена", 2500)
    }

    private fun cycleTimer() {
        timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
        prefs.edit().putInt("timer", timerSec).apply()
        updateChips()
    }

    private fun pickPreset() {
        val labels = presetNames.mapIndexed { i, n -> n + if (prefs.contains("p" + i + "_cam")) "" else "   (пусто)" }
        AlertDialog.Builder(this, DIALOG).setTitle("Пресеты")
            .setItems(labels.toTypedArray()) { _, i ->
                val has = prefs.contains("p" + i + "_cam")
                val acts = if (has) arrayOf("Применить", "Сохранить сюда текущие настройки") else arrayOf("Сохранить сюда текущие настройки")
                AlertDialog.Builder(this, DIALOG).setTitle(presetNames[i])
                    .setItems(acts) { _, j -> if (has && j == 0) applyPreset(i) else savePreset(i) }.show()
            }.show()
    }

    private fun savePreset(i: Int) {
        val cam = curCam ?: return
        val p = "p" + i + "_"
        prefs.edit()
            .putString(p + "cam", cam.key)
            .putString(p + "size", curSize.toString())
            .putString(p + "fps", "" + curFps.lower + "-" + curFps.upper)
            .putBoolean(p + "hevc", codecHevc)
            .putInt(p + "bitrate", bitrateLevel)
            .putBoolean(p + "stab", stab)
            .putBoolean(p + "dist", distCorr)
            .putInt(p + "profile", profile)
            .putInt(p + "grid", grid.mode)
            .putInt(p + "guide", guide.mode)
            .putInt(p + "tl", tlIndex)
            .putBoolean(p + "horizon", horizonLock)
            .putFloat(p + "zoom", zoom)
            .apply()
        setStatus("Пресет «" + presetNames[i] + "» сохранён", 2000)
    }

    private fun applyPreset(i: Int) {
        if (recording) { setStatus("Нельзя во время записи"); return }
        val p = "p" + i + "_"
        val key = prefs.getString(p + "cam", null) ?: return
        val cam = cams.firstOrNull { it.key == key }
        if (cam == null) { setStatus("Камера из пресета не найдена"); return }
        prefs.edit()
            .putString("size_" + key, prefs.getString(p + "size", null))
            .putString("fps_" + key, prefs.getString(p + "fps", null))
            .apply()
        codecHevc = prefs.getBoolean(p + "hevc", codecHevc)
        bitrateLevel = prefs.getInt(p + "bitrate", bitrateLevel).coerceIn(0, 2)
        stab = prefs.getBoolean(p + "stab", stab)
        distCorr = prefs.getBoolean(p + "dist", distCorr)
        grid.mode = prefs.getInt(p + "grid", grid.mode)
        guide.mode = prefs.getInt(p + "guide", guide.mode)
        tlIndex = prefs.getInt(p + "tl", 0).coerceIn(0, tlIntervals.size - 1)
        prefs.edit()
            .putBoolean("hevc", codecHevc).putInt("bitrate", bitrateLevel)
            .putBoolean("stab", stab).putBoolean("dist", distCorr)
            .putInt("grid", grid.mode).putInt("guide", guide.mode)
            .apply()
        setProfile(prefs.getInt(p + "profile", 0))
        val hz = prefs.getBoolean(p + "horizon", false)
        horizonLock = hz
        prefs.edit().putBoolean("horizon", hz).apply()
        if (!hz) gl.horizonDeg = 0f
        applyLevel()
        selectCam(cam, prefs.getFloat(p + "zoom", 1f))
        setStatus("Пресет «" + presetNames[i] + "»", 1500)
    }

    // ----- ручной фокус -----

    private fun minFocus(): Float = curCam?.chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    private fun toggleFocusPanel() {
        val c = curCam?.chars ?: return
        if (!canFocus(c)) { setStatus("У этого объектива фокус фиксированный — ручной фокус есть на 1×", 3000); return }
        if (focusPanel.visibility == View.VISIBLE) {
            focusPanel.visibility = View.GONE
            manFocus = null
            gl.peaking = false
            setStatus("Автофокус", 1200)
            updateChips()
            bg.post(applyTask)
        } else {
            focusPanel.visibility = View.VISIBLE
            val mn = max(0.001f, minFocus())
            val v = manFocus ?: (0.2f * mn)
            focusBar.progress = (v / mn * 1000).roundToInt()
            setManualFocus(focusBar.progress / 1000f)
            if (peakOn) setStatus("Ручной фокус: красным подсвечены резкие края", 2500)
        }
    }

    private fun setManualFocus(frac: Float) {
        manFocus = frac.coerceIn(0f, 1f) * minFocus()
        gl.peaking = peakOn
        updateChips()
        bg.removeCallbacks(applyTask); bg.post(applyTask)
    }

    private fun setPoint(a: Boolean) {
        val v = manFocus ?: return
        if (a) focusA = v else focusB = v
        setStatus("Точка " + (if (a) "A" else "B") + " сохранена", 1200)
    }

    private fun pullFocus() {
        val a = focusA
        val b = focusB
        if (a == null || b == null) { setStatus("Сначала поставь фокус и сохрани точки A и B", 2500); return }
        val cur = manFocus ?: a
        val to = if (abs(cur - b) < abs(cur - a)) a else b
        val mn = max(0.001f, minFocus())
        pullAnim?.cancel()
        pullAnim = ValueAnimator.ofFloat(cur, to).apply {
            duration = 2000
            addUpdateListener { an ->
                val v = an.animatedValue as Float
                manFocus = v
                focusBar.progress = (v / mn * 1000).roundToInt()
                bg.removeCallbacks(applyTask); bg.post(applyTask)
            }
            start()
        }
    }

    // ----- метки -----

    private fun recMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return now - recStart - pausedTotal - (if (paused) now - pausedAt else 0L)
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun addMarker() {
        if (!recording) return
        val ms = recMs()
        markers += ms
        setStatus("★ Метка " + fmtTime(ms), 1200)
    }

    private fun saveMarkers(base: String) {
        if (markers.isEmpty()) return
        try {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, base + "_метки.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/WideCam")
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), v) ?: return
            val text = "Метки для " + base + ".mp4\n" +
                markers.mapIndexed { i, m -> "" + (i + 1) + ". " + fmtTime(m) }.joinToString("\n") + "\n"
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) {}
    }

    // ----- фото сериями -----

    private fun onPhotoBtn() {
        if (intervalSec <= 0 || recording) { takePhoto(); return }
        seriesOn = !seriesOn
        main.removeCallbacks(seriesTask)
        if (seriesOn) {
            photoBtn.text = "■"; photoBtn.setTextColor(YELLOW)
            setStatus("Серия: фото каждые " + intervalSec + " с · ■ — стоп", 3000)
            main.post(seriesTask)
        } else {
            photoBtn.text = "📷"; photoBtn.setTextColor(Color.WHITE)
            setStatus("Серия остановлена", 1500)
        }
    }

    private fun pickInterval() {
        val opts = intArrayOf(0, 2, 5, 10, 30, 60)
        AlertDialog.Builder(this, DIALOG).setTitle("Интервальная съёмка фото")
            .setItems(opts.map { (if (it == intervalSec) "✓ " else "") + if (it == 0) "Выкл (обычное фото)" else "Каждые " + it + " с" }.toTypedArray()) { _, i ->
                intervalSec = opts[i]
                prefs.edit().putInt("interval", intervalSec).apply()
                if (intervalSec > 0) setStatus("Нажми 📷 — начнётся серия", 2500)
            }.show()
    }

    // ----- звук -----

    private fun unprocessedOk(): Boolean = try {
        getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    } catch (e: Exception) { false }

    private fun mics(): List<AudioDeviceInfo> = try {
        getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.toList() ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun micName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "встроенный"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "проводной (3.5 мм)"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "другой (" + t + ")"
    }

    private fun pickMic(): AudioDeviceInfo? = if (micType == 0) null else mics().firstOrNull { it.type == micType }

    private fun pickAudio() {
        if (recording) { setStatus("Нельзя менять во время записи"); return }
        val devs = mics().map { it.type }.distinct()
        val items = mutableListOf((if (micType == 0) "✓ " else "") + "Микрофон: авто")
        devs.forEach { items += (if (micType == it) "✓ " else "") + "Микрофон: " + micName(it) }
        items += (if (audioMode == 0) "✓ " else "") + "Обработка: шумо- и ветроподавление телефона"
        items += (if (audioMode == 1) "✓ " else "") + "Обработка: выкл, чистый звук" + if (unprocessedOk()) "" else " (не поддерживается)"
        AlertDialog.Builder(this, DIALOG).setTitle("Звук")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i == 0 -> micType = 0
                    i <= devs.size -> micType = devs[i - 1]
                    i == devs.size + 1 -> audioMode = 0
                    else -> audioMode = 1
                }
                prefs.edit().putInt("mic", micType).putInt("audioMode", audioMode).apply()
            }.show()
    }

    // ----- экран при записи -----

    private fun setDim(on: Boolean) {
        dimmed = on
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (recording && dimOnRec) {
            if (dimmed) setDim(false)
            main.removeCallbacks(dimTask)
            main.postDelayed(dimTask, 5000)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Максимальный угол: самое большое 4:3, без стабилизации, без исправления дисторсии, без горизонта, зум до упора. */
    private fun maxAngle() {
        if (recording) { setStatus("Нельзя во время записи"); return }
        val cam = curCam ?: return
        val c = cam.chars
        val done = mutableListOf<String>()
        val g = supportedSizes.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
            .maxByOrNull { it.width * it.height }
        if (g != null) {
            curSize = g
            val m = maxFps(curMap, g)
            if (curFps !in supportedFps || (m != null && m < curFps.upper)) curFps = bestFps()
            prefs.edit()
                .putString("size_" + cam.key, g.toString())
                .putString("fps_" + cam.key, "" + curFps.lower + "-" + curFps.upper)
                .apply()
            done += "4:3 " + g.width + "×" + g.height
        }
        if (hasStab(c) && stab) {
            stab = false
            prefs.edit().putBoolean("stab", false).apply()
            done += "стаб выкл"
        }
        if (hasDist(c) && distCorr) {
            distCorr = false
            prefs.edit().putBoolean("dist", false).apply()
            done += "дисторсия не исправляется"
        }
        if (horizonLock) {
            horizonLock = false
            prefs.edit().putBoolean("horizon", false).apply()
            gl.horizonDeg = 0f
            applyLevel()
            done += "горизонт выкл"
        }
        if (wheel.visibility == View.VISIBLE && zoom > wheel.zMin + 0.01f) {
            wheel.set(wheel.zMin)
            done += "зум минимум"
        }
        updateChips()
        restartPreview()
        setStatus(if (done.isEmpty()) "Уже максимальный угол для этой камеры" else "Максимальный угол: " + done.joinToString(", "), 4000)
    }

    private fun hyperlapse() {
        if (recording) { setStatus("Нельзя во время записи"); return }
        tlIndex = 2
        if (!horizonLock) setHorizon(true)
        val c = curCam?.chars
        if (c != null && hasStab(c) && !stab) {
            stab = true
            prefs.edit().putBoolean("stab", true).apply()
            restartPreview()
        }
        updateChips()
        setStatus("Гиперлапс: 1 кадр/с (×30) + горизонт + стабилизация. Иди плавно", 4000)
    }

    private fun showSettings() {
        val items = arrayOf(
            "Пресеты: Влог / Кино / Таймлапс / Мой",
            "Максимальный угол — одним нажатием",
            "Цветовой профиль: " + profileNames[profile],
            "Рамки кадрирования: " + guideNames[guide.mode],
            "Блокировка горизонта: " + onOff(horizonLock),
            "Гиперлапс — включить одним нажатием",
            "Таймер старта: " + (if (timerSec == 0) "выкл" else "" + timerSec + " с"),
            "Интервальная съёмка фото: " + (if (intervalSec == 0) "выкл" else "каждые " + intervalSec + " с"),
            "Звук: микрофон и обработка",
            "Фокус-пикинг при ручном фокусе: " + onOff(peakOn),
            "Блокировка экрана при записи: " + onOff(lockOnRec),
            "Затемнять экран при записи: " + onOff(dimOnRec),
            "Кодек: " + (if (codecHevc) "HEVC (H.265) — компактный" else "H.264 — совместимый"),
            "Битрейт: " + bitrateNames[bitrateLevel],
            "Исправление дисторсии: " + onOff(distCorr),
            "Таймлапс: " + tlName(),
            "Сетка: " + gridNames[grid.mode],
            "Уровень горизонта: " + onOff(levelOn),
            "Зебра (пересвет): " + onOff(zebraOn),
            "Гистограмма: " + onOff(histOn),
            "Отчёт о камере",
            "Выбрать камеру вручную",
            "Зеркальное видео с фронталки: " + onOff(mirrorFront),
            "Жесты и быстрый запуск"
        )
        AlertDialog.Builder(this, DIALOG).setTitle("Настройки")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> pickPreset()
                    1 -> maxAngle()
                    2 -> pickProfile()
                    3 -> pickGuide()
                    4 -> setHorizon(!horizonLock)
                    5 -> hyperlapse()
                    6 -> cycleTimer()
                    7 -> pickInterval()
                    8 -> pickAudio()
                    9 -> {
                        peakOn = !peakOn
                        prefs.edit().putBoolean("peak", peakOn).apply()
                        gl.peaking = peakOn && manFocus != null
                    }
                    10 -> { lockOnRec = !lockOnRec; prefs.edit().putBoolean("lockRec", lockOnRec).apply() }
                    11 -> { dimOnRec = !dimOnRec; prefs.edit().putBoolean("dimRec", dimOnRec).apply() }
                    12 -> toggleCodec()
                    13 -> pickBitrate()
                    14 -> toggleDist()
                    15 -> pickTimelapse()
                    16 -> pickGrid()
                    17 -> { levelOn = !levelOn; prefs.edit().putBoolean("level", levelOn).apply(); applyLevel() }
                    18 -> { zebraOn = !zebraOn; prefs.edit().putBoolean("zebra", zebraOn).apply(); applyAnalysis() }
                    19 -> { histOn = !histOn; prefs.edit().putBoolean("hist", histOn).apply(); applyAnalysis() }
                    20 -> curCam?.let { cam ->
                        AlertDialog.Builder(this, DIALOG).setTitle(cam.label)
                            .setMessage(report(cam, curMap)).setPositiveButton("OK", null).show()
                    }
                    21 -> AlertDialog.Builder(this, DIALOG).setTitle("Все камеры")
                        .setItems(cams.map { it.label }.toTypedArray()) { _, j -> selectCam(cams[j]) }
                        .show()
                    22 -> {
                        mirrorFront = !mirrorFront
                        prefs.edit().putBoolean("mirrorFront", mirrorFront).apply()
                        setStatus(if (mirrorFront) "Видео с фронталки — зеркальное, как в зеркале" else "Видео с фронталки — как в жизни, текст читается", 2500)
                    }
                    else -> AlertDialog.Builder(this, DIALOG).setTitle("Жесты и быстрый запуск")
                        .setMessage("Тап — фокус и замер света по точке\nДолгий тап — зафиксировать фокус и экспозицию\nДвойной тап — сброс на авто\nДва пальца или колесо — зум\nКнопка громкости — старт/стоп записи\n📷 — фото, в том числе во время записи\n★ — метка во время записи (сохраняются в Documents/WideCam)\nОбъективы и фронталку можно переключать во время записи\n\nБыстрый запуск: опусти шторку → карандаш (изменить) → перетащи плитку «Ширик-камера».")
                        .setPositiveButton("OK", null).show()
                }
            }.show()
    }

    // ---------- поиск камер ----------

    private fun init() {
        if (cams.isNotEmpty()) return
        val listed = mgr.cameraIdList.toSet()
        for (id in mgr.cameraIdList) addCam(id, false)
        for (i in 0..31) {
            val id = i.toString()
            if (id !in listed) addCam(id, true)
        }
        if (cams.isEmpty()) { setStatus("Камеры не найдены", 10000); return }
        buildLenses()

        val saved = prefs.getString("cam", null)
        val start = cams.firstOrNull { it.key == saved } ?: lenses.firstOrNull()?.cam ?: cams[0]
        selectCam(start)
    }

    private fun addCam(id: String, hidden: Boolean) {
        val c = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { return }
        cams += Cam(id, null, c, hidden, describe(id, null, c, hidden))
        for (pid in c.physicalCameraIds) {
            val pc = try { mgr.getCameraCharacteristics(pid) } catch (e: Exception) { continue }
            cams += Cam(id, pid, pc, hidden, describe(id, pid, pc, hidden))
        }
    }

    private fun facing(c: CameraCharacteristics) = c[CameraCharacteristics.LENS_FACING]

    private fun focal(c: CameraCharacteristics) =
        c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull() ?: 99f

    private fun area(c: CameraCharacteristics) =
        c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]?.let { it.width * it.height } ?: 0

    private fun eqFocal(c: CameraCharacteristics): Float {
        val f = focal(c)
        val p = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return f
        val diag = sqrt(p.width * p.width + p.height * p.height)
        return if (diag > 0f) f * 43.27f / diag else f
    }

    private fun rank(c: Cam) = (if (c.physicalId == null) 0 else 2) + (if (c.hidden) 1 else 0)

    private fun buildLenses() {
        lenses.clear()
        val back = cams.filter { facing(it.chars) == CameraCharacteristics.LENS_FACING_BACK }
        val picks = back.groupBy { (focal(it.chars) * 10).roundToInt() }.values
            .map { g -> g.minByOrNull { rank(it) }!! }
        val mainCam = picks.maxByOrNull { area(it.chars) }
        mainEq = mainCam?.let { eqFocal(it.chars) } ?: 1f
        for (c in picks.sortedBy { eqFocal(it.chars) }) {
            val z = eqFocal(c.chars) / mainEq
            val t = if (z < 0.95f) String.format(Locale.US, "%.1f", z) else z.roundToInt().toString()
            lenses += Lens(t, c)
        }
        // быстрые кнопки цифрового зума на основном модуле
        if (mainCam != null) {
            val zr = if (Build.VERSION.SDK_INT >= 30) mainCam.chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] else null
            val zMaxMain = zr?.upper ?: (mainCam.chars[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f)
            for (p in listOf(2f, 3f, 5f)) if (p <= zMaxMain + 0.01f) lenses += Lens(p.roundToInt().toString(), mainCam, p)
        }
        cams.filter { facing(it.chars) == CameraCharacteristics.LENS_FACING_FRONT }
            .minByOrNull { rank(it) }?.let { lenses += Lens("Фронт", it) }
    }

    private fun describe(id: String, pid: String?, c: CameraCharacteristics, hidden: Boolean): String {
        val side = when (facing(c)) {
            CameraCharacteristics.LENS_FACING_BACK -> "задняя"
            CameraCharacteristics.LENS_FACING_FRONT -> "фронт"
            else -> "внешняя"
        }
        val f = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
            ?.joinToString("/") { String.format(Locale.US, "%.2f", it) } ?: "?"
        val pa = c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
        val mp = pa?.let { String.format(Locale.US, "%.1f Мп", it.width * it.height / 1e6) } ?: "?"
        val name = if (pid != null) "ID $id → модуль $pid" else "ID $id"
        return "$name · $side · f=$f мм · $mp" + if (hidden) " · скрытая" else ""
    }

    private fun streamMap(cam: Cam): StreamConfigurationMap? =
        cam.chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?: try {
                mgr.getCameraCharacteristics(cam.openId)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            } catch (e: Exception) { null }

    private fun maxFps(map: StreamConfigurationMap?, s: Size): Int? = try {
        val d = map!!.getOutputMinFrameDuration(MediaRecorder::class.java, s)
        if (d > 0) (1_000_000_000.0 / d).roundToInt() else null
    } catch (e: Exception) { null }

    // ---------- выбор камеры и режимов ----------

    private fun parseRange(s: String): Range<Int>? = try {
        val p = s.split("-"); Range(p[0].toInt(), p[1].toInt())
    } catch (e: Exception) { null }

    private fun bestFps(): Range<Int> {
        val m = maxFps(curMap, curSize) ?: 30
        val ok = supportedFps.filter { it.upper <= m }
        return ok.filter { it.lower == it.upper }.maxByOrNull { it.upper }
            ?: ok.maxByOrNull { it.upper } ?: fpsRanges.first()
    }

    private fun selectCam(cam: Cam, startZoom: Float = 1f) {
        curCam = cam
        gl.frontCam = false
        prefs.edit().putString("cam", cam.key).apply()
        val map = streamMap(cam)
        curMap = map

        val real = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
        supportedSizes = real.toSet()
        val list = real.sortedByDescending { it.width * it.height }.toMutableList()
        val uhd = Size(3840, 2160)
        if (uhd !in supportedSizes) list.add(0, uhd)
        sizes = list

        val ae = (cam.chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
            ?: arrayOf(Range(30, 30))).toList()
        supportedFps = ae.toSet()
        val fl = ae.sortedWith(compareByDescending<Range<Int>> { it.upper }.thenByDescending { it.lower })
            .toMutableList()
        for (r in listOf(Range(60, 60), Range(30, 30))) if (r !in supportedFps) fl += r
        fpsRanges = fl

        if (recording) {
            // во время записи размер кадра не меняем, частоту подбираем под новую камеру
            if (curFps !in supportedFps)
                curFps = supportedFps.filter { it.upper <= curFps.upper }.maxByOrNull { it.upper } ?: bestFps()
            setStatus("Переключаю объектив…", 1200)
        } else {
            val savedSize = prefs.getString("size_" + cam.key, null)
                ?.let { try { Size.parseSize(it) } catch (e: Exception) { null } }
            curSize = savedSize?.takeIf { it in list }
                ?: Size(1920, 1080).takeIf { it in supportedSizes }
                ?: real.filter { it.width * it.height <= 1920 * 1080 }.maxByOrNull { it.width * it.height }
                ?: list.first()
            val savedFps = prefs.getString("fps_" + cam.key, null)?.let { parseRange(it) }
            curFps = savedFps?.takeIf { it in fl } ?: bestFps()
        }

        // ручные настройки — на авто при смене объектива
        afPoint = null; aeAfLocked = false; evIndex = 0; torch = false; wbLock = false
        manIso = null; manExp = null
        manFocus = null; gl.peaking = false; focusPanel.visibility = View.GONE
        wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
        ring.visibility = View.INVISIBLE; lockLabel.visibility = View.GONE

        // зум
        val zr = if (Build.VERSION.SDK_INT >= 30) cam.chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] else null
        var zMin = 1f
        var zMax: Float
        if (zr != null) { useZoomRatio = true; zMin = zr.lower; zMax = zr.upper }
        else { useZoomRatio = false; zMax = cam.chars[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f }
        zMax = min(zMax, 10f)
        zoom = startZoom.coerceIn(zMin, max(zMax, zMin))
        wheel.zMin = zMin; wheel.zMax = zMax; wheel.factor = eqFocal(cam.chars) / mainEq; wheel.mmBase = mainEq
        wheel.set(zoom, false)
        wheel.visibility = if (zMax > zMin + 0.05f) View.VISIBLE else View.INVISIBLE

        bg.post { closeCamera() }
        updateChips()
        renderLenses()
        rotateUi(devOrient)
        restartPreview()
    }

    /** Если принудительный режим уронил драйвер — возвращаем рабочий. */
    private fun fallbackIfForced() {
        val cam = curCam ?: return
        if (curSize in supportedSizes && curFps in supportedFps) return
        if (curSize !in supportedSizes) {
            curSize = Size(1920, 1080).takeIf { it in supportedSizes }
                ?: supportedSizes.maxByOrNull { it.width * it.height } ?: return
        }
        if (curFps !in supportedFps) curFps = bestFps()
        prefs.edit()
            .putString("size_" + cam.key, curSize.toString())
            .putString("fps_" + cam.key, "" + curFps.lower + "-" + curFps.upper)
            .apply()
        updateChips()
        restartPreview()
    }

    private fun pickSize() {
        if (recording) return
        val cam = curCam ?: return
        val labels = sizes.map { s ->
            if (s in supportedSizes)
                sizeName(s) + "   " + s.width + "×" + s.height + "   до " + (maxFps(curMap, s) ?: "?") + " к/с" + aspectTag(s)
            else sizeName(s) + "   нет в драйвере — растяну из меньшего (не честное)"
        }
        AlertDialog.Builder(this, DIALOG).setTitle("Разрешение")
            .setItems(labels.toTypedArray()) { _, i ->
                curSize = sizes[i]
                prefs.edit().putString("size_" + cam.key, curSize.toString()).apply()
                val m = maxFps(curMap, curSize)
                if (curFps in supportedFps && m != null && m < curFps.upper) curFps = bestFps()
                updateChips(); restartPreview()
            }.show()
    }

    private fun pickFps() {
        if (recording) return
        val cam = curCam ?: return
        val labels = fpsRanges.map { fpsName(it) + if (it in supportedFps) "" else "   — попробовать силой" }
        AlertDialog.Builder(this, DIALOG).setTitle("Частота кадров")
            .setItems(labels.toTypedArray()) { _, i ->
                curFps = fpsRanges[i]
                prefs.edit().putString("fps_" + cam.key, "" + curFps.lower + "-" + curFps.upper).apply()
                updateChips(); restartPreview()
            }.show()
    }

    private fun report(cam: Cam, map: StreamConfigurationMap?): String = buildString {
        val c = cam.chars
        c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]?.let { pa ->
            val can = max(pa.width, pa.height) >= 3840 && min(pa.width, pa.height) >= 2160
            appendLine("Матрица: " + pa.width + "×" + pa.height)
            appendLine("Честное 4K: " + if (can) "физически возможно" else "невозможно — пикселей меньше, чем в кадре 4K")
        }
        val lvl = when (c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "?"
        }
        appendLine("Camera2: $lvl")
        appendLine("Автофокус: " + if (canFocus(c)) "есть" else "нет (фикс-фокус)")
        appendLine("Ручные ISO/выдержка: " + if (manualOk(c)) "есть" else "нет")
        appendLine("Исправление дисторсии: " + if (hasDist(c)) "есть" else "нет")
        val eis = c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        val ois = c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: intArrayOf()
        appendLine("Стабилизация: EIS " + (if (CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON in eis) "есть" else "нет") +
            ", OIS " + (if (CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON in ois) "есть" else "нет"))
        c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.let { appendLine("ISO: " + it.lower + "–" + it.upper) }
        c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.let { appendLine("Выдержка: " + expName(it.upper) + " – " + expName(it.lower)) }

        appendLine("\nВидео (разрешение — макс. к/с):")
        map?.getOutputSizes(MediaRecorder::class.java)
            ?.sortedByDescending { it.width * it.height }
            ?.forEach { appendLine("  " + it.width + "×" + it.height + " — " + (maxFps(map, it) ?: "?")) }
            ?: appendLine("  нет данных")

        appendLine("\nДиапазоны FPS:")
        appendLine("  " + (c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
            ?.joinToString { "" + it.lower + "-" + it.upper } ?: "нет данных"))

        appendLine("\nСлоумо:")
        val hs = map?.highSpeedVideoSizes
        if (hs.isNullOrEmpty()) appendLine("  нет")
        else hs.forEach { s ->
            appendLine("  " + s.width + "×" + s.height + ": " +
                map.getHighSpeedVideoFpsRangesFor(s).joinToString { "" + it.lower + "-" + it.upper })
        }
    }

    /** Размер кадра, который отдаёт камера (дальше OpenGL масштабирует его в превью и в запись). */
    private fun previewSizeFor(cam: Cam, target: Size): Size {
        val all = streamMap(cam)?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        if (target in all) return target
        val ratio = target.width.toFloat() / target.height
        val same = all.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f }
        return same.filter { it.width * it.height <= target.width * target.height }.maxByOrNull { it.width * it.height }
            ?: same.minByOrNull { it.width * it.height }
            ?: all.filter { it.width * it.height <= 1920 * 1080 }.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }

    private fun captureCfg(): Boolean {
        val cam = curCam ?: return false
        val prev = previewSizeFor(cam, curSize)
        pending = Cfg(cam, curSize, curFps, prev)
        val w = resources.displayMetrics.widthPixels
        val ratio = max(prev.width, prev.height).toFloat() / min(prev.width, prev.height)
        previewBox.layoutParams = previewBox.layoutParams.apply { width = w; height = (w * ratio).toInt() }
        return true
    }

    private fun restartPreview() {
        if (!captureCfg()) return
        bg.removeCallbacks(restartTask)
        bg.postDelayed(restartTask, 200)
    }

    // ---------- камера ----------

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        activeCfg = null
        photoReady = false
        try { device?.close() } catch (_: Exception) {}
        device = null
        deviceId = null
    }

    private fun afRegion(cfg: Cfg): MeteringRectangle? {
        val p = afPoint ?: return null
        val c = cfg.cam.chars
        val a = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return null
        val so = c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
        val sx: Float
        val sy: Float
        when (so) {
            90 -> { sx = p.second; sy = 1f - p.first }
            270 -> { sx = 1f - p.second; sy = p.first }
            180 -> { sx = 1f - p.first; sy = 1f - p.second }
            else -> { sx = p.first; sy = p.second }
        }
        val base = if (useZoomRatio || zoom <= 1.001f) Rect(0, 0, a.width(), a.height()) else {
            val w = (a.width() / zoom).toInt(); val h = (a.height() / zoom).toInt()
            val l = (a.width() - w) / 2; val t = (a.height() - h) / 2
            Rect(l, t, l + w, t + h)
        }
        val half = (min(base.width(), base.height()) * 0.08f).toInt()
        val cx = base.left + (sx * base.width()).toInt()
        val cy = base.top + (sy * base.height()).toInt()
        val r = Rect(
            (cx - half).coerceAtLeast(base.left), (cy - half).coerceAtLeast(base.top),
            (cx + half).coerceAtMost(base.right - 1), (cy + half).coerceAtMost(base.bottom - 1)
        )
        return MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun fill(b: CaptureRequest.Builder, cfg: Cfg) {
        val c = cfg.cam.chars
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, cfg.fps)

        // экспозиция
        if (isManual() && manualOk(c)) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val frame = 1_000_000_000L / max(1, cfg.fps.upper)
            val sr = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
            val er = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
            var iso = manIso ?: lastIso
            if (sr != null) iso = iso.coerceIn(sr.lower, sr.upper)
            var ex = min(manExp ?: lastExp, frame)
            if (er != null) ex = ex.coerceIn(er.lower, er.upper)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ex)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frame)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeAfLocked)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        }

        // зум
        if (useZoomRatio && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else if (zoom > 1.001f) {
            c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { a ->
                val w = (a.width() / zoom).toInt(); val h = (a.height() / zoom).toInt()
                val l = (a.width() - w) / 2; val t = (a.height() - h) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
            }
        }

        // фокус и точка замера
        val afModes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        val region = afRegion(cfg)
        if (region != null) {
            if (canFocus(c) && CaptureRequest.CONTROL_AF_MODE_AUTO in afModes) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                if ((c[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0)
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            if ((c[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0)
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        } else if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // ручной фокус
        val mf = manFocus
        if (mf != null && canFocus(c)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, mf)
        }

        // баланс белого, фонарь
        b.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, wbLock)
        b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

        // стабилизация
        val eis = c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in eis)
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (stab) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val ois = c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: intArrayOf()
        if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in ois)
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (stab) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)

        // дисторсия
        val dm = c[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES] ?: intArrayOf()
        if (hasDist(c)) {
            val on = if (CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY in dm)
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY else CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
            if (distCorr) b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, on)
            else if (CaptureRequest.DISTORTION_CORRECTION_MODE_OFF in dm)
                b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
        }
    }

    /** Применяет все настройки к идущему потоку — без перезапуска, можно во время записи. */
    private fun applyControls(trigger: Boolean) {
        val s = session ?: return
        val d = device ?: return
        val cfg = activeCfg ?: return
        try {
            val b = d.createCaptureRequest(activeTemplate)
            activeTargets.forEach { b.addTarget(it) }
            fill(b, cfg)
            s.setRepeatingRequest(b.build(), resultCb, bg)
            if (trigger) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, bg)
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun doRestart() {
        val cfg = pending ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null
        activeCfg = null

        if (device != null && deviceId == cfg.cam.openId) {
            createSession(cfg, true)
            return
        }
        closeCamera()
        try {
            mgr.openCamera(cfg.cam.openId, exec, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d; deviceId = cfg.cam.openId
                    createSession(cfg, true)
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close(); if (device == d) { device = null; deviceId = null }
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); if (device == d) { device = null; deviceId = null }
                    if (recording) {
                        setStatus("Эта камера не открылась (код $error) — запись идёт, переключись обратно", 6000)
                    } else {
                        setStatus("Драйвер не выдержал режим " + sizeName(cfg.size) + " · " + fpsName(cfg.fps) + " (код $error) — включил рабочий", 6000)
                        ui { fallbackIfForced() }
                    }
                }
            })
        } catch (e: Exception) {
            setStatus("Камера не открылась: " + e.message, 6000)
        }
    }

    private fun bitrateFor(cfg: Cfg): Int {
        val fps = if (tlIndex > 0) 30 else cfg.fps.upper
        val bpp = floatArrayOf(0.07f, 0.12f, 0.22f)[bitrateLevel] * (if (codecHevc) 1f else 1.6f)
        val v = (cfg.size.width.toLong() * cfg.size.height * fps * bpp).toLong()
        return v.coerceIn(4_000_000L, 160_000_000L).toInt()
    }

    private fun makeRecorder(cfg: Cfg, w: Int, h: Int, fd: java.io.FileDescriptor, audio: Boolean, tl: Float): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        if (audio) {
            r.setAudioSource(if (audioMode == 1 && unprocessedOk()) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.CAMCORDER)
            pickMic()?.let { r.setPreferredDevice(it) }
        }
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(fd)
        r.setVideoEncoder(if (codecHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(if (tl > 0f) 30 else cfg.fps.upper)
        if (tl > 0f) r.setCaptureRate(tl.toDouble())
        r.setVideoEncodingBitRate(bitrateFor(cfg))
        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(192_000)
        }
        return r
    }

    private fun photoReaderFor(cfg: Cfg): ImageReader? {
        val map = streamMap(cfg.cam) ?: return null
        val jpegs = map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: return null
        val ratio = cfg.size.width.toFloat() / cfg.size.height
        val target = max(cfg.size.width * cfg.size.height, 1920 * 1080)
        val sz = jpegs.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.width * it.height <= target }
            .maxByOrNull { it.width * it.height }
            ?: jpegs.filter { it.width * it.height <= target }.maxByOrNull { it.width * it.height }
            ?: return null
        val cur = photoReader
        if (cur != null && cur.width == sz.width && cur.height == sz.height) return cur
        try { cur?.close() } catch (_: Exception) {}
        val r = ImageReader.newInstance(sz.width, sz.height, ImageFormat.JPEG, 2)
        r.setOnImageAvailableListener({ rd -> savePhoto(rd) }, bg)
        photoReader = r
        return r
    }

    private fun isFront(cam: Cam) = facing(cam.chars) == CameraCharacteristics.LENS_FACING_FRONT

    private fun createSession(cfg: Cfg, withPhoto: Boolean) {
        val d = device ?: return
        gl.camTexture.setDefaultBufferSize(cfg.preview.width, cfg.preview.height)
        val jr = if (withPhoto) photoReaderFor(cfg) else null
        val surfaces = mutableListOf(camSurface)
        if (jr != null) surfaces += jr.surface
        val mode = sizeName(cfg.size) + " · " + fpsName(cfg.fps)
        val outs = surfaces.map { s ->
            OutputConfiguration(s).also { o -> cfg.cam.physicalId?.let { o.setPhysicalCameraId(it) } }
        }
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    photoReady = jr != null
                    activeTemplate = CameraDevice.TEMPLATE_RECORD
                    activeTargets = listOf(camSurface)
                    activeCfg = cfg
                    if (recording) gl.setMirror(isFront(cfg.cam) && mirrorFront)
                    gl.texelX = 1f / cfg.preview.width; gl.texelY = 1f / cfg.preview.height
                    applyControls(afPoint != null && canFocus(cfg.cam.chars))
                    ui { updateReadout() }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (jr != null) { createSession(cfg, false); return }
                    setStatus("Драйвер не поддерживает режим $mode — включил рабочий", 6000)
                    ui { fallbackIfForced() }
                }
            })
        try {
            d.createCaptureSession(sc)
        } catch (e: Exception) {
            setStatus("Режим $mode не запустился: " + e.message, 6000)
        }
    }

    private fun orientationFor(cfg: Cfg): Int {
        val sensor = cfg.cam.chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
        val front = cfg.cam.chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        val dev = if (front) -devOrient else devOrient
        return (sensor + dev + 360) % 360
    }

    // ---------- фото ----------

    private fun takePhoto() {
        flashView.animate().cancel()
        flashView.alpha = 0.7f
        flashView.animate().alpha(0f).setDuration(250).start()
        bg.post {
            val s = session
            val d = device
            val cfg = activeCfg
            val jr = photoReader
            if (s == null || d == null || cfg == null || jr == null || !photoReady) {
                setStatus("Фото в этом режиме недоступно — драйвер не даёт третий поток", 4000)
                return@post
            }
            try {
                val b = d.createCaptureRequest(
                    if (recording) CameraDevice.TEMPLATE_VIDEO_SNAPSHOT else CameraDevice.TEMPLATE_STILL_CAPTURE
                )
                activeTargets.forEach { b.addTarget(it) }
                b.addTarget(jr.surface)
                fill(b, cfg)
                b.set(CaptureRequest.JPEG_ORIENTATION, orientationFor(cfg))
                b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                s.capture(b.build(), null, bg)
            } catch (e: Exception) {
                setStatus("Фото не получилось: " + e.message)
            }
        }
    }

    private fun savePhoto(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val w = img.width
            val h = img.height
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val name = "WideCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WideCam")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            setStatus("Фото сохранено · " + w + "×" + h, 2000)
        } catch (e: Exception) {
            setStatus("Фото не сохранилось: " + e.message)
        } finally {
            img.close()
        }
    }

    // ---------- запись ----------

    private fun toggleRec() {
        if (recording) { bg.post { stopRec() }; return }
        if (counting) {
            counting = false
            main.removeCallbacks(countTask)
            countText.visibility = View.GONE
            setStatus("Таймер отменён", 1200)
            return
        }
        if (timerSec > 0) { counting = true; countLeft = timerSec; main.post(countTask) }
        else bg.post { startRec() }
    }

    private fun startRec() {
        if (recording) return
        val cfg = activeCfg
        if (cfg == null) { setStatus("Камера ещё не готова", 3000); return }
        try {
            val name = "WideCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WideCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            videoUri = uri
            markers.clear()
            markerBase = name.removeSuffix(".mp4")
            val fd = contentResolver.openFileDescriptor(uri, "w")!!
            pfd = fd
            // ориентация — по тому, как держат телефон в момент старта; поворот делает OpenGL
            val portrait = devOrient == 0 || devOrient == 180
            val vw = if (portrait) cfg.size.height else cfg.size.width
            val vh = if (portrait) cfg.size.width else cfg.size.height
            val tl = timelapseRate()
            val r = makeRecorder(cfg, vw, vh, fd.fileDescriptor, tl <= 0f, tl)
            r.prepare()
            recorder = r
            r.start()
            recording = true
            gl.setEncoder(r.surface, vw, vh, -devOrient.toFloat(), isFront(cfg.cam) && mirrorFront)
            setRecUi(true)
        } catch (e: Exception) {
            setStatus("Запись не запустилась: " + e.message, 6000)
            abortRecorder()
        }
    }

    private fun togglePause() {
        if (!recording) return
        val r = recorder ?: return
        try {
            if (!paused) {
                r.pause()
                pausedAt = SystemClock.elapsedRealtime(); paused = true
                pauseBtn.text = "▶"
                (timerText.background as GradientDrawable).setColor(AMBER)
            } else {
                r.resume()
                pausedTotal += SystemClock.elapsedRealtime() - pausedAt; paused = false
                pauseBtn.text = "❚❚"
                (timerText.background as GradientDrawable).setColor(RED)
            }
            main.removeCallbacks(tick); main.post(tick)
        } catch (e: Exception) {
            setStatus("Пауза не сработала: " + e.message)
        }
    }

    private fun openLast() {
        if (recording) return
        val u = lastVideo
        if (u == null) { setStatus("Пока нет записанных видео"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            setStatus("Нечем открыть видео")
        }
    }

    private fun loadThumb(u: Uri) {
        try {
            val bm = contentResolver.loadThumbnail(u, Size(200, 200), null)
            ui { galleryBtn.setImageBitmap(bm) }
        } catch (_: Exception) {}
    }

    private fun loadLastVideo() {
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                arrayOf("Movies/WideCam%"),
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use {
                if (it.moveToFirst())
                    lastVideo = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
            }
        } catch (_: Exception) {}
        lastVideo?.let { loadThumb(it) }
    }

    private fun abortRecorder() {
        gl.setEncoder(null, 0, 0, 0f, false)
        recording = false
        paused = false
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        videoUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        videoUri = null
        setRecUi(false)
    }

    private fun stopRec() {
        if (!recording) return
        recording = false
        paused = false
        gl.setEncoder(null, 0, 0, 0f, false)
        var ok = true
        try { recorder?.stop() } catch (e: Exception) { ok = false }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null

        val uri = videoUri
        videoUri = null
        if (uri != null) {
            if (ok) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                lastVideo = uri
                saveMarkers(markerBase)
                setStatus(measure(uri), 6000)
                loadThumb(uri)
            } else {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                setStatus("Запись слишком короткая — файл удалён")
            }
        }
        setRecUi(false)
    }

    /** Что реально легло в файл: разрешение и фактическая частота кадров. */
    private fun measure(uri: Uri): String = try {
        val m = MediaMetadataRetriever()
        m.setDataSource(this, uri)
        val w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val frames = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()
        val dur = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()
        m.release()
        val fps = if (frames != null && dur != null && dur > 0)
            String.format(Locale.US, "%.1f", frames * 1000 / dur) else "?"
        "Сохранено в галерею · " + w + "×" + h + " · " + fps + " к/с"
    } catch (e: Exception) {
        "Сохранено в галерею (Movies/WideCam)"
    }
}

/** Плитка в шторке: открывает камеру сразу на последнем объективе. */
class CamTile : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
