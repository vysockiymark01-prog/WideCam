package com.mark.widecam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
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
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private data class Cam(
        val openId: String,          // какой ID открываем
        val physicalId: String?,     // физический модуль внутри логической камеры
        val chars: CameraCharacteristics,
        val label: String
    )

    private data class Cfg(val cam: Cam, val size: Size, val fps: Range<Int>, val preview: Size)

    private lateinit var mgr: CameraManager
    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private lateinit var camSpin: Spinner
    private lateinit var sizeSpin: Spinner
    private lateinit var fpsSpin: Spinner
    private lateinit var recBtn: Button
    private lateinit var info: TextView

    private val cams = mutableListOf<Cam>()
    private var sizes = listOf<Size>()
    private var supportedSizes = setOf<Size>()
    private var fpsRanges = listOf<Range<Int>>()
    private var supportedFps = setOf<Range<Int>>()

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

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mgr = getSystemService(CameraManager::class.java)
        buildUi()
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) init()
        else requestPermissions(need, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) init()
        else toast("Нужны разрешения на камеру и микрофон")
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

    // ---------- интерфейс ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        texture = TextureView(this)
        root.addView(texture, LinearLayout.LayoutParams(400, 710).apply { gravity = Gravity.CENTER_HORIZONTAL })
        status = TextView(this).apply { textSize = 13f; setPadding(0, 12, 0, 0) }
        root.addView(status)

        camSpin = Spinner(this); sizeSpin = Spinner(this); fpsSpin = Spinner(this)
        root.addView(label("Камера / модуль")); root.addView(camSpin)
        root.addView(label("Разрешение")); root.addView(sizeSpin)
        root.addView(label("FPS")); root.addView(fpsSpin)

        recBtn = Button(this).apply { text = "● Запись"; setOnClickListener { toggleRec() } }
        root.addView(recBtn)

        info = TextView(this).apply { setTextIsSelectable(true); textSize = 12f; setPadding(0, 16, 0, 0) }
        root.addView(info)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 20, 0, 0) }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun listener(f: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = f()
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun toast(msg: String) = ui { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    private fun setStatus(msg: String) = ui { status.text = msg }
    private fun setControls(on: Boolean) = ui {
        camSpin.isEnabled = on; sizeSpin.isEnabled = on; fpsSpin.isEnabled = on
    }

    // ---------- поиск камер (включая скрытые) ----------

    private fun init() {
        if (cams.isNotEmpty()) return
        val listed = mgr.cameraIdList.toSet()
        for (id in mgr.cameraIdList) addCam(id, hidden = false)
        // ID, которых нет в официальном списке: vivo иногда прячет модули
        for (i in 0..31) {
            val id = i.toString()
            if (id !in listed) addCam(id, hidden = true)
        }
        if (cams.isEmpty()) { toast("Камеры не найдены"); return }

        camSpin.adapter = adapter(cams.map { it.label })
        camSpin.onItemSelectedListener = listener { onCamChanged() }
        sizeSpin.onItemSelectedListener = listener { restartPreview() }
        fpsSpin.onItemSelectedListener = listener { restartPreview() }

        // по умолчанию — задний модуль с самым коротким фокусом (ширик)
        val wide = cams.indices.minByOrNull {
            val c = cams[it].chars
            val back = c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
            focal(c) + if (back) 0f else 100f
        } ?: 0
        camSpin.setSelection(wide)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = restartPreview()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun addCam(id: String, hidden: Boolean) {
        val c = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { return }
        cams += Cam(id, null, c, describe(id, null, c, hidden))
        for (pid in c.physicalCameraIds) {
            val pc = try { mgr.getCameraCharacteristics(pid) } catch (e: Exception) { continue }
            cams += Cam(id, pid, pc, describe(id, pid, pc, hidden))
        }
    }

    private fun focal(c: CameraCharacteristics) =
        c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull() ?: 99f

    private fun describe(id: String, pid: String?, c: CameraCharacteristics, hidden: Boolean): String {
        val facing = when (c[CameraCharacteristics.LENS_FACING]) {
            CameraCharacteristics.LENS_FACING_BACK -> "задняя"
            CameraCharacteristics.LENS_FACING_FRONT -> "фронт"
            else -> "внешняя"
        }
        val f = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
            ?.joinToString("/") { String.format(Locale.US, "%.2f", it) } ?: "?"
        val pa = c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
        val mp = pa?.let { String.format(Locale.US, "%.1f Мп", it.width * it.height / 1e6) } ?: "?"
        val name = if (pid != null) "ID $id → модуль $pid" else "ID $id"
        return "$name · $facing · f=$f мм · $mp" + if (hidden) " · СКРЫТАЯ" else ""
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

    // ---------- выбор режимов ----------

    private fun onCamChanged() {
        val cam = cams.getOrNull(camSpin.selectedItemPosition) ?: return
        val map = streamMap(cam)

        val real = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
        supportedSizes = real.toSet()
        val list = real.sortedByDescending { it.width * it.height }.toMutableList()
        val uhd = Size(3840, 2160)
        if (uhd !in supportedSizes) list.add(0, uhd)
        sizes = list
        sizeSpin.adapter = adapter(list.map { s ->
            if (s in supportedSizes) "${s.width}x${s.height}  (до ${maxFps(map, s) ?: "?"} к/с)"
            else "${s.width}x${s.height}  — нет в драйвере, попытка силой"
        })
        val fhd = list.indexOf(Size(1920, 1080))
        if (fhd >= 0) sizeSpin.setSelection(fhd)

        val ae = (cam.chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
            ?: arrayOf(Range(30, 30))).toList()
        supportedFps = ae.toSet()
        val fpsList = ae.sortedWith(compareByDescending<Range<Int>> { it.upper }.thenByDescending { it.lower })
            .toMutableList()
        for (r in listOf(Range(60, 60), Range(30, 30))) if (r !in supportedFps) fpsList += r
        fpsRanges = fpsList
        fpsSpin.adapter = adapter(fpsList.map {
            "${it.lower}–${it.upper}" + if (it in supportedFps) "" else "  (силой)"
        })

        info.text = report(cam, map)
        bg.post { closeCamera() }
        restartPreview()
    }

    private fun report(cam: Cam, map: StreamConfigurationMap?): String = buildString {
        val c = cam.chars
        appendLine(cam.label)
        c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]?.let { pa ->
            val can = max(pa.width, pa.height) >= 3840 && min(pa.width, pa.height) >= 2160
            appendLine("Матрица: ${pa.width}x${pa.height}")
            appendLine("Честное 4K: " + if (can) "физически возможно" else "НЕВОЗМОЖНО — пикселей меньше, чем в кадре 4K")
        }
        val lvl = when (c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "?"
        }
        appendLine("Camera2 уровень: $lvl")

        appendLine("\nВидео (разрешение — макс. к/с по драйверу):")
        map?.getOutputSizes(MediaRecorder::class.java)
            ?.sortedByDescending { it.width * it.height }
            ?.forEach { appendLine("  ${it.width}x${it.height} — ${maxFps(map, it) ?: "?"}") }
            ?: appendLine("  нет данных")

        appendLine("\nДиапазоны FPS:")
        appendLine("  " + (c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
            ?.joinToString { "${it.lower}-${it.upper}" } ?: "нет данных"))

        appendLine("\nHigh-speed (слоумо):")
        val hs = map?.highSpeedVideoSizes
        if (hs.isNullOrEmpty()) appendLine("  нет")
        else hs.forEach { s ->
            appendLine("  ${s.width}x${s.height}: " +
                map.getHighSpeedVideoFpsRangesFor(s).joinToString { "${it.lower}-${it.upper}" })
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
        val cam = cams.getOrNull(camSpin.selectedItemPosition) ?: return false
        val size = sizes.getOrNull(sizeSpin.selectedItemPosition) ?: return false
        val fps = fpsRanges.getOrNull(fpsSpin.selectedItemPosition) ?: return false
        val prev = previewSizeFor(cam, size)
        pending = Cfg(cam, size, fps, prev)

        val w = (resources.displayMetrics.widthPixels * 0.55f).toInt()
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
                    setStatus("Камера ${cfg.cam.openId} не открылась (код $error) — система не пускает")
                    if (record) abortRecorder()
                }
            })
        } catch (e: Exception) {
            setStatus("Камера ${cfg.cam.openId} не открылась: ${e.message}")
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
        val mode = "${cfg.size.width}x${cfg.size.height} @ ${cfg.fps.lower}-${cfg.fps.upper}"
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
                            setStatus("● ЗАПИСЬ $mode")
                            ui { recBtn.text = "■ Стоп" }
                        } else {
                            setStatus("Превью ок. Режим записи: $mode")
                        }
                    } catch (e: Exception) {
                        setStatus("Драйвер отказал: ${e.message}")
                        if (record) abortRecorder()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    setStatus("Драйвер ОТКЛОНИЛ режим $mode")
                    if (record) abortRecorder()
                }
            })
        try {
            d.createCaptureSession(sc)
        } catch (e: Exception) {
            setStatus("Сессия не создана ($mode): ${e.message}")
            if (record) abortRecorder()
        }
    }

    // ---------- запись ----------

    private fun toggleRec() {
        if (!recording) {
            if (!captureCfg()) return
            setControls(false)
            ui { recBtn.isEnabled = false }
            bg.removeCallbacks(restartTask)
            bg.post { doRestart(true); ui { recBtn.isEnabled = true } }
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
        setStatus("Рекордер не запустился: ${e.message}")
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
        ui { recBtn.text = "● Запись"; recBtn.isEnabled = true }
        setControls(true)
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
                setStatus(measure(uri))
            } else {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                setStatus("Запись слишком короткая или пустая — файл удалён")
            }
        }
        ui { recBtn.text = "● Запись" }
        setControls(true)
        if (reopen) doRestart(false)
    }

    /** Проверяем, что реально легло в файл: разрешение и фактическую частоту кадров. */
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
        "Сохранено в Movies/WideCam: ${w}x$h, реально $fps к/с"
    } catch (e: Exception) {
        "Сохранено в Movies/WideCam"
    }
}
