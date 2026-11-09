package com.mark.widecam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    }

    private lateinit var mgr: CameraManager
    private lateinit var prefs: SharedPreferences
    private lateinit var texture: TextureView
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var resChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var infoBtn: TextView
    private lateinit var lensBar: LinearLayout
    private lateinit var recOuter: FrameLayout
    private lateinit var recInner: View

    private val cams = mutableListOf<Cam>()
    private val lenses = mutableListOf<Lens>()
    private var curCam: Cam? = null
    private var curMap: StreamConfigurationMap? = null
    private var sizes = listOf<Size>()
    private var supportedSizes = setOf<Size>()
    private var curSize = Size(1920, 1080)
    private var fpsRanges = listOf<Range<Int>>()
    private var supportedFps = setOf<Range<Int>>()
    private var curFps = Range(30, 30)

    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    @Volatile private var recording = false
    @Volatile private var pending: Cfg? = null

    private val thread = HandlerThread("cam").apply { start() }
    private val bg = Handler(thread.looper)
    private val exec = Executor { bg.post(it) }
    private val restartTask = Runnable { doRestart(false) }
    private val main = Handler(Looper.getMainLooper())
    private var recStart = 0L
    private val hideStatus = Runnable { statusText.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            timerText.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
            main.postDelayed(this, 500)
        }
    }

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mgr = getSystemService(CameraManager::class.java)
        prefs = getSharedPreferences("widecam", MODE_PRIVATE)
        buildUi()
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
        if (cams.isNotEmpty() && texture.isAvailable) restartPreview()
    }

    override fun onPause() {
        super.onPause()
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

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        texture = TextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(56) })

        // верхняя панель: разрешение, fps, таймер, меню
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        resChip = chip().apply { setOnClickListener { pickSize() } }
        fpsChip = chip().apply { setOnClickListener { pickFps() } }
        timerText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = pill(RED); setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.INVISIBLE; text = "● 00:00"
        }
        infoBtn = chip().apply { text = "ⓘ"; textSize = 17f; setOnClickListener { showInfoMenu() } }
        top.addView(resChip)
        top.addView(fpsChip, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(timerText)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(infoBtn)
        root.addView(top, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))

        // нижняя панель: статус, объективы, кнопка записи
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(40), 0, dp(28))
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
            bottomMargin = dp(14); leftMargin = dp(24); rightMargin = dp(24)
        })
        lensBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = pill(0x55000000); setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bottom.addView(lensBar, lp(WRAP, WRAP).apply { bottomMargin = dp(22) })

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
        bottom.addView(recOuter, lp(dp(78), dp(78)))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        setContentView(root)
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun setStatus(msg: String, ms: Long = 4000) = ui {
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
        timerText.visibility = if (rec) View.VISIBLE else View.INVISIBLE
        val a = if (rec) 0.3f else 1f
        listOf<View>(resChip, fpsChip, infoBtn, lensBar).forEach { it.alpha = a; it.isEnabled = !rec }
        for (i in 0 until lensBar.childCount) lensBar.getChildAt(i).isEnabled = !rec
        main.removeCallbacks(tick)
        if (rec) { recStart = SystemClock.elapsedRealtime(); timerText.text = "● 00:00"; main.post(tick) }
    }

    private fun sizeName(s: Size): String = when {
        s.width == 3840 && s.height == 2160 -> "4K"
        s.width == 1920 && s.height == 1080 -> "1080p"
        s.width == 1280 && s.height == 720 -> "720p"
        else -> "" + s.width + "×" + s.height
    }

    private fun fpsName(r: Range<Int>) =
        if (r.lower == r.upper) "" + r.upper + " к/с" else "" + r.lower + "–" + r.upper + " к/с"

    private fun updateChips() {
        resChip.text = sizeName(curSize) + if (curSize in supportedSizes) "" else " ⚠"
        fpsChip.text = fpsName(curFps) + if (curFps in supportedFps) "" else " ⚠"
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

    /** Эквивалентное фокусное (как у полного кадра) — чтобы посчитать «0.6×». */
    private fun eqFocal(c: CameraCharacteristics): Float {
        val f = focal(c)
        val p = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return f
        val diag = sqrt(p.width * p.width + p.height * p.height)
        return if (diag > 0f) f * 43.27f / diag else f
    }

    /** Чем меньше, тем надёжнее: отдельная камера лучше, чем модуль внутри логической. */
    private fun rank(c: Cam) = (if (c.physicalId == null) 0 else 2) + (if (c.hidden) 1 else 0)

    private fun buildLenses() {
        lenses.clear()
        val back = cams.filter { facing(it.chars) == CameraCharacteristics.LENS_FACING_BACK }
        val picks = back.groupBy { (focal(it.chars) * 10).roundToInt() }.values
            .map { g -> g.minByOrNull { rank(it) }!! }
        val mainCam = picks.maxByOrNull { area(it.chars) }
        val mainEq = mainCam?.let { eqFocal(it.chars) } ?: 1f
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

        bg.post { closeCamera() }
        updateChips()
        renderLenses()
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
            .setItems(arrayOf("Отчёт о текущей камере", "Выбрать камеру вручную")) { _, i ->
                if (i == 0) {
                    AlertDialog.Builder(this, DIALOG).setTitle(cam.label)
                        .setMessage(report(cam, curMap)).setPositiveButton("OK", null).show()
                } else {
                    AlertDialog.Builder(this, DIALOG).setTitle("Все камеры")
                        .setItems(cams.map { it.label }.toTypedArray()) { _, j -> selectCam(cams[j]) }
                        .show()
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
        texture.layoutParams = texture.layoutParams.apply { width = w; height = (w * ratio).toInt() }
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
        try { device?.close() } catch (_: Exception) {}
        device = null
        deviceId = null
    }

    @SuppressLint("MissingPermission")
    private fun doRestart(record: Boolean) {
        val cfg = pending ?: return
        val st = texture.surfaceTexture ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null

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
                    setStatus("Камера не открылась (код $error) — система не пускает", 6000)
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
                    try {
                        val req = d.createCaptureRequest(
                            if (record) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                        ).apply {
                            targets.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, cfg.fps)
                        }
                        s.setRepeatingRequest(req.build(), null, bg)
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
                    setStatus("Драйвер не поддерживает режим $mode", 6000)
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
        r.setOrientationHint(cfg.cam.chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90)
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
