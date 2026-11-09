package com.mark.widecam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
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
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Колесо зума: тянешь пальцем влево-вправо. */
class ZoomWheel(ctx: Context) : View(ctx) {
    var zMin = 1f
    var zMax = 8f
    var value = 1f
    var factor = 1f
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
        c.drawText(String.format(Locale.US, "%.1f×", value * factor), cx, base - 30 * d, txt)
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

    private data class Lens(val title: String, val cam: Cam)
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
    private lateinit var previewBox: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var grid: GridView
    private lateinit var ring: View
    private lateinit var lockLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var resChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var wbChip: TextView
    private lateinit var evChip: TextView
    private lateinit var stabChip: TextView
    private lateinit var torchChip: TextView
    private lateinit var gridChip: TextView
    private lateinit var infoBtn: TextView
    private lateinit var wheel: ZoomWheel
    private lateinit var lensBar: LinearLayout
    private lateinit var recOuter: FrameLayout
    private lateinit var recInner: View
    private lateinit var pauseBtn: TextView
    private lateinit var galleryBtn: TextView

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

    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var activeCfg: Cfg? = null
    private var activeTemplate = CameraDevice.TEMPLATE_PREVIEW
    private var activeTargets = listOf<Surface>()
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
    private val restartTask = Runnable { doRestart(false) }
    private val applyTask = Runnable { applyControls(false) }
    private val main = Handler(Looper.getMainLooper())
    private var recStart = 0L
    @Volatile private var devOrient = 0
    private lateinit var orient: OrientationEventListener
    private val hideStatus = Runnable { statusText.visibility = View.GONE }
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

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mgr = getSystemService(CameraManager::class.java)
        prefs = getSharedPreferences("widecam", MODE_PRIVATE)
        stab = prefs.getBoolean("stab", true)
        buildUi()
        grid.mode = prefs.getInt("grid", 0)
        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = ((o + 45) / 90 * 90) % 360
                if (r != devOrient) { devOrient = r; rotateUi(r) }
            }
        }
        loadLastVideo()
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
        orient.enable()
        if (cams.isNotEmpty() && texture.isAvailable) restartPreview()
    }

    override fun onPause() {
        super.onPause()
        orient.disable()
        bg.removeCallbacks(restartTask)
        bg.post {
            if (recording) stopRec(reopen = false)
            closeCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thread.quitSafely()
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

        // превью + сетка + рамка фокуса
        previewBox = FrameLayout(this)
        texture = TextureView(this)
        previewBox.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
        grid = GridView(this)
        previewBox.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), YELLOW)
            }
            visibility = View.INVISIBLE
        }
        previewBox.addView(ring, FrameLayout.LayoutParams(dp(72), dp(72)))
        val topInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        timerText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = pill(RED); setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE; text = "● 00:00"
        }
        lockLabel = TextView(this).apply {
            text = "Фокус и экспозиция 🔒"; setTextColor(Color.BLACK); textSize = 12f
            typeface = Typeface.DEFAULT_BOLD; background = pill(YELLOW)
            setPadding(dp(10), dp(3), dp(10), dp(3)); visibility = View.GONE
        }
        topInfo.addView(timerText)
        topInfo.addView(lockLabel, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        previewBox.addView(topInfo, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(10) })
        root.addView(previewBox, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(56) })
        setupTouch()

        // верхняя лента настроек
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        resChip = chip().apply { setOnClickListener { pickSize() } }
        fpsChip = chip().apply { setOnClickListener { pickFps() } }
        stabChip = chip().apply { setOnClickListener { toggleStab() } }
        wbChip = chip().apply { setOnClickListener { pickWb() } }
        evChip = chip().apply { setOnClickListener { pickEv() } }
        gridChip = chip().apply { setOnClickListener { pickGrid() } }
        torchChip = chip().apply { text = "Фонарь"; setOnClickListener { toggleTorch() } }
        infoBtn = chip().apply { text = "ⓘ"; textSize = 17f; setOnClickListener { showInfoMenu() } }
        listOf(resChip, fpsChip, stabChip, wbChip, evChip, gridChip, torchChip, infoBtn).forEachIndexed { i, v ->
            row.addView(v, lp(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(8) })
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        scroll.addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))

        // низ: статус, колесо зума, объективы, кнопки
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
            bottomMargin = dp(10); leftMargin = dp(24); rightMargin = dp(24)
        })
        wheel = ZoomWheel(this).apply {
            onChange = { z -> zoom = z; bg.removeCallbacks(applyTask); bg.post(applyTask) }
        }
        bottom.addView(wheel, lp(MATCH, dp(64)).apply { bottomMargin = dp(6) })
        lensBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = pill(0x55000000); setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bottom.addView(lensBar, lp(WRAP, WRAP).apply { bottomMargin = dp(18) })

        val recRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        pauseBtn = roundBtn("❚❚").apply { visibility = View.INVISIBLE; setOnClickListener { togglePause() } }
        galleryBtn = roundBtn("▶").apply { setOnClickListener { openLast() } }
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
        recRow.addView(pauseBtn, lp(dp(54), dp(54)))
        recRow.addView(recOuter, lp(dp(78), dp(78)).apply { leftMargin = dp(40); rightMargin = dp(40) })
        recRow.addView(galleryBtn, lp(dp(54), dp(54)))
        bottom.addView(recRow, lp(MATCH, WRAP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

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

    /** Поворачиваем круглые кнопки вслед за телефоном. */
    private fun rotateUi(r: Int) = ui {
        val deg = when (r) { 90 -> -90f; 180 -> 180f; 270 -> 90f; else -> 0f }
        val views = mutableListOf<View>(infoBtn, pauseBtn, galleryBtn)
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
        (timerText.background as GradientDrawable).setColor(RED)
        pauseBtn.visibility = if (rec) View.VISIBLE else View.INVISIBLE
        pauseBtn.text = "❚❚"
        val a = if (rec) 0.3f else 1f
        listOf<View>(resChip, fpsChip, stabChip, infoBtn, lensBar, galleryBtn).forEach { it.alpha = a; it.isEnabled = !rec }
        for (i in 0 until lensBar.childCount) lensBar.getChildAt(i).isEnabled = !rec
        main.removeCallbacks(tick)
        if (rec) {
            recStart = SystemClock.elapsedRealtime(); pausedTotal = 0L; paused = false
            timerText.text = "● 00:00"; main.post(tick)
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

    private fun evStep(): Float = curCam?.chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

    private fun evName(i: Int): String {
        val v = i * evStep()
        return if (abs(v) < 0.05f) "±0" else String.format(Locale.US, "%+.1f", v)
    }

    private fun hasStab(c: CameraCharacteristics): Boolean {
        val eis = c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        val ois = c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: intArrayOf()
        return CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON in eis ||
            CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON in ois
    }

    private fun updateChips() {
        resChip.text = sizeName(curSize) + if (curSize in supportedSizes) "" else " ⚠"
        fpsChip.text = fpsName(curFps) + if (curFps in supportedFps) "" else " ⚠"
        val c = curCam?.chars
        val stabOk = c != null && hasStab(c)
        stabChip.text = if (!stabOk) "Стаб —" else if (stab) "Стаб вкл" else "Стаб выкл"
        stabChip.setTextColor(if (stabOk && stab) YELLOW else Color.WHITE)
        val wbS = wbList.firstOrNull { it.first == wbMode }?.third ?: "Авто"
        wbChip.text = "ББ " + wbS + if (wbLock) " 🔒" else ""
        wbChip.setTextColor(if (wbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO || wbLock) YELLOW else Color.WHITE)
        evChip.text = "EV " + evName(evIndex)
        evChip.setTextColor(if (evIndex != 0) YELLOW else Color.WHITE)
        gridChip.text = if (grid.mode == 0) "Сетка" else "Сетка ✓"
        gridChip.setTextColor(if (grid.mode != 0) YELLOW else Color.WHITE)
        torchChip.setTextColor(if (torch) YELLOW else Color.WHITE)
    }

    private fun renderLenses() {
        lensBar.removeAllViews()
        for (l in lenses) {
            val sel = l.cam.key == curCam?.key
            val front = l.title == "Фронт"
            val tv = TextView(this).apply {
                text = if (sel && !front) l.title + "×" else l.title
                setTextColor(if (sel) YELLOW else Color.WHITE)
                textSize = if (front) 12f else 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = pill(if (sel) 0x88000000.toInt() else 0x00000000)
                setOnClickListener { if (!recording) selectCam(l.cam) }
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

    private fun pickGrid() {
        AlertDialog.Builder(this, DIALOG).setTitle("Сетка")
            .setItems(gridNames.mapIndexed { i, n -> (if (i == grid.mode) "✓ " else "") + n }.toTypedArray()) { _, i ->
                grid.mode = i
                prefs.edit().putInt("grid", i).apply()
                updateChips()
            }.show()
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

    private fun toggleTorch() {
        val c = curCam?.chars ?: return
        if (c[CameraCharacteristics.FLASH_INFO_AVAILABLE] != true) {
            setStatus("У этого объектива нет вспышки — она работает на 1×"); return
        }
        torch = !torch
        updateChips(); bg.post(applyTask)
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

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = restartPreview()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

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

    /** Эквивалентное фокусное — чтобы посчитать «0.6×». */
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

    private fun selectCam(cam: Cam) {
        curCam = cam
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

        val savedSize = prefs.getString("size_" + cam.key, null)
            ?.let { try { Size.parseSize(it) } catch (e: Exception) { null } }
        curSize = savedSize?.takeIf { it in list }
            ?: Size(1920, 1080).takeIf { it in supportedSizes }
            ?: real.filter { it.width * it.height <= 1920 * 1080 }.maxByOrNull { it.width * it.height }
            ?: list.first()
        val savedFps = prefs.getString("fps_" + cam.key, null)?.let { parseRange(it) }
        curFps = savedFps?.takeIf { it in fl } ?: bestFps()

        // ручные настройки — на авто при смене объектива
        afPoint = null; aeAfLocked = false; evIndex = 0; torch = false; wbLock = false
        wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
        ring.visibility = View.INVISIBLE; lockLabel.visibility = View.GONE

        // зум
        val zr = if (Build.VERSION.SDK_INT >= 30) cam.chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] else null
        var zMin = 1f
        var zMax: Float
        if (zr != null) { useZoomRatio = true; zMin = zr.lower; zMax = zr.upper }
        else { useZoomRatio = false; zMax = cam.chars[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f }
        zMax = min(zMax, 10f)
        zoom = 1f.coerceIn(zMin, max(zMax, zMin))
        wheel.zMin = zMin; wheel.zMax = zMax; wheel.factor = eqFocal(cam.chars) / mainEq
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
                sizeName(s) + "   " + s.width + "×" + s.height + "   до " + (maxFps(curMap, s) ?: "?") + " к/с"
            else sizeName(s) + "   нет в драйвере — попробовать силой"
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

    private fun showInfoMenu() {
        if (recording) return
        val cam = curCam ?: return
        AlertDialog.Builder(this, DIALOG).setTitle("Камера")
            .setItems(arrayOf("Отчёт о текущей камере", "Выбрать камеру вручную", "Подсказка по жестам")) { _, i ->
                when (i) {
                    0 -> AlertDialog.Builder(this, DIALOG).setTitle(cam.label)
                        .setMessage(report(cam, curMap)).setPositiveButton("OK", null).show()
                    1 -> AlertDialog.Builder(this, DIALOG).setTitle("Все камеры")
                        .setItems(cams.map { it.label }.toTypedArray()) { _, j -> selectCam(cams[j]) }
                        .show()
                    else -> AlertDialog.Builder(this, DIALOG).setTitle("Жесты")
                        .setMessage("Тап — фокус и замер света по точке\nДолгий тап — зафиксировать фокус и экспозицию\nДвойной тап — сброс на авто\nДва пальца или колесо — зум\nКнопка громкости — старт/стоп записи")
                        .setPositiveButton("OK", null).show()
                }
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
        val eis = c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        val ois = c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: intArrayOf()
        appendLine("Стабилизация: EIS " + (if (CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON in eis) "есть" else "нет") +
            ", OIS " + (if (CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON in ois) "есть" else "нет"))

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

    private fun previewSizeFor(cam: Cam, target: Size): Size {
        val all = streamMap(cam)?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val ratio = target.width.toFloat() / target.height
        return all.filter {
            abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.width * it.height <= 1920 * 1080
        }.maxByOrNull { it.width * it.height }
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
        if (recording) return
        if (!captureCfg()) return
        bg.removeCallbacks(restartTask)
        bg.postDelayed(restartTask, 200)
    }

    // ---------- камера ----------

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        activeCfg = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        deviceId = null
    }

    /** Точка фокуса из координат экрана в координаты матрицы. */
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
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, cfg.fps)

        if (useZoomRatio && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else if (zoom > 1.001f) {
            c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { a ->
                val w = (a.width() / zoom).toInt(); val h = (a.height() / zoom).toInt()
                val l = (a.width() - w) / 2; val t = (a.height() - h) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
            }
        }

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

        b.set(CaptureRequest.CONTROL_AE_LOCK, aeAfLocked)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        b.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, wbLock)
        b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

        val eis = c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in eis)
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (stab) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val ois = c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: intArrayOf()
        if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in ois)
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (stab) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
    }

    /** Применяет зум/фокус/ББ/EV/фонарь к уже идущему потоку — без перезапуска, можно во время записи. */
    private fun applyControls(trigger: Boolean) {
        val s = session ?: return
        val d = device ?: return
        val cfg = activeCfg ?: return
        try {
            val b = d.createCaptureRequest(activeTemplate)
            activeTargets.forEach { b.addTarget(it) }
            fill(b, cfg)
            s.setRepeatingRequest(b.build(), null, bg)
            if (trigger) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, bg)
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun doRestart(record: Boolean) {
        val cfg = pending ?: return
        val st = texture.surfaceTexture ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null
        activeCfg = null

        if (device != null && deviceId == cfg.cam.openId) {
            createSession(cfg, st, record)
            return
        }
        closeCamera()
        try {
            mgr.openCamera(cfg.cam.openId, exec, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d; deviceId = cfg.cam.openId
                    createSession(cfg, st, record)
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close(); if (device == d) { device = null; deviceId = null }
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); if (device == d) { device = null; deviceId = null }
                    setStatus("Драйвер не выдержал режим " + sizeName(cfg.size) + " · " + fpsName(cfg.fps) + " (код $error) — включил рабочий", 6000)
                    ui { fallbackIfForced() }
                    if (record) abortRecorder()
                }
            })
        } catch (e: Exception) {
            setStatus("Камера не открылась: " + e.message, 6000)
            if (record) abortRecorder()
        }
    }

    private fun createSession(cfg: Cfg, st: SurfaceTexture, record: Boolean) {
        val d = device ?: return
        st.setDefaultBufferSize(cfg.preview.width, cfg.preview.height)
        val targets = mutableListOf(Surface(st))
        if (record) {
            val r = prepareRecorder(cfg) ?: return
            targets += r.surface
        }
        val mode = sizeName(cfg.size) + " · " + fpsName(cfg.fps)
        val outs = targets.map { s ->
            OutputConfiguration(s).also { o -> cfg.cam.physicalId?.let { o.setPhysicalCameraId(it) } }
        }
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    activeTemplate = if (record) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    activeTargets = targets
                    activeCfg = cfg
                    try {
                        applyControls(afPoint != null && canFocus(cfg.cam.chars))
                        if (record) {
                            recorder?.start()
                            recording = true
                            setRecUi(true)
                        }
                    } catch (e: Exception) {
                        setStatus("Драйвер отказал: " + e.message, 6000)
                        if (record) abortRecorder()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    setStatus("Драйвер не поддерживает режим $mode — включил рабочий", 6000)
                    ui { fallbackIfForced() }
                    if (record) abortRecorder()
                }
            })
        try {
            d.createCaptureSession(sc)
        } catch (e: Exception) {
            setStatus("Режим $mode не запустился: " + e.message, 6000)
            if (record) abortRecorder()
        }
    }

    // ---------- запись ----------

    private fun toggleRec() {
        if (!recording) {
            if (!captureCfg()) return
            recOuter.isEnabled = false
            bg.removeCallbacks(restartTask)
            bg.post { doRestart(true); ui { recOuter.isEnabled = true } }
        } else {
            bg.post { stopRec(reopen = true) }
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
    }

    private fun prepareRecorder(cfg: Cfg): MediaRecorder? = try {
        val name = "WideCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WideCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        videoUri = uri
        val fd = contentResolver.openFileDescriptor(uri, "w")!!
        pfd = fd

        val w = cfg.size.width
        val h = cfg.size.height
        val fps = cfg.fps.upper
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(fd.fileDescriptor)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(fps)
        r.setVideoEncodingBitRate((w.toLong() * h * fps / 8).coerceIn(8_000_000L, 120_000_000L).toInt())
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(48000)
        r.setAudioEncodingBitRate(192_000)
        // ориентация видео — по тому, как держат телефон в момент старта записи
        val sensor = cfg.cam.chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
        val front = cfg.cam.chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        val dev = if (front) -devOrient else devOrient
        r.setOrientationHint((sensor + dev + 360) % 360)
        r.prepare()
        recorder = r
        r
    } catch (e: Exception) {
        setStatus("Запись не запустилась: " + e.message, 6000)
        abortRecorder()
        null
    }

    private fun abortRecorder() {
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

    private fun stopRec(reopen: Boolean) {
        recording = false
        paused = false
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
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
                setStatus(measure(uri), 6000)
            } else {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                setStatus("Запись слишком короткая — файл удалён")
            }
        }
        setRecUi(false)
        if (reopen) doRestart(false)
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
