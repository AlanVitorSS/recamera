package br.com.recamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private companion object {
        const val ZOOM_STEPS = 1000
        const val BG = 0xFF101810.toInt()
        const val ACCENT = 0xFF8BC34A.toInt()
        const val FAIL = 0xFFFF5252.toInt()
    }

    private lateinit var previewView: PreviewView
    private lateinit var focusRing: View
    private lateinit var captureButton: Button
    private lateinit var zoomLabel: TextView
    private lateinit var expLabel: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var expBar: SeekBar
    private val modeButtons = LinkedHashMap<String, Button>()

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val tempFiles = mutableListOf<File>()

    private var mode = "AUTO"
    private var busy = false
    private var ringSize = 0
    private var focusToken = 0
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var expLower = 0
    private var expStep = 1f

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else toast("Permissão da câmera necessária.", long = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun seekListener(onChange: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onChange(p, fromUser)
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        // Android 15 (targetSdk 35) desenha por baixo das barras do sistema: aplica os recuos.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Pré-visualização + anel de foco por cima
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; keepScreenOn = true }
        ringSize = dp(80)
        focusRing = View(this).apply { visibility = View.INVISIBLE }
        val frame = FrameLayout(this)
        frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(focusRing, FrameLayout.LayoutParams(ringSize, ringSize))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setupFocusGesture()

        // Modos
        val modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(4)) }
        listOf("AUTO", "LUA", "NOITE", "RETRATO", "PAISAGEM").forEach { label ->
            val b = Button(this).apply {
                text = label; textSize = 10f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { mode = label; updateModeUi(); toast("Modo $label selecionado") }
            }
            modeButtons[label] = b
            modeRow.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        root.addView(modeRow)

        // Zoom
        zoomLabel = TextView(this).apply { text = "ZOOM"; setTextColor(-1); gravity = Gravity.CENTER }
        root.addView(zoomLabel)
        zoomBar = SeekBar(this).apply {
            max = ZOOM_STEPS
            setOnSeekBarChangeListener(seekListener { p, user ->
                if (user) camera?.cameraControl?.setZoomRatio(progressToRatio(p))
            })
        }
        root.addView(zoomBar)

        // Exposição
        expLabel = TextView(this).apply { text = "BRILHO / EXPOSIÇÃO"; setTextColor(-1); gravity = Gravity.CENTER }
        root.addView(expLabel)
        expBar = SeekBar(this).apply {
            max = 12; progress = 6
            setOnSeekBarChangeListener(seekListener { p, user ->
                if (user) {
                    val idx = p + expLower
                    camera?.cameraControl?.setExposureCompensationIndex(idx)
                    updateExposureLabel(idx)
                }
            })
        }
        root.addView(expBar)

        captureButton = Button(this).apply { textSize = 16f; setOnClickListener { captureBurst() } }
        root.addView(captureButton, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(TextView(this).apply {
            text = "Toque na imagem para focar · segure para travar foco/exposição.\nProtótipo: o alinhamento e o empilhamento virão nas próximas etapas."
            textSize = 11f; setTextColor(0xFFCCCCCC.toInt()); gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(8))
        })
        setContentView(root)
        updateModeUi()
    }

    private fun burstCount() = if (mode == "LUA" || mode == "NOITE") 8 else 6

    private fun updateModeUi() {
        modeButtons.forEach { (label, b) ->
            b.alpha = if (label == mode) 1f else 0.45f
            b.setTypeface(null, if (label == mode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        if (!busy) captureButton.text = "●  FOTOGRAFAR (${burstCount()} FOTOS)"
    }

    // ---------- Foco por toque ----------

    private fun setupFocusGesture() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y, lock = false); return true }
            override fun onLongPress(e: MotionEvent) { focusAt(e.x, e.y, lock = true) }
        })
        previewView.setOnTouchListener { v, ev ->
            detector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val builder = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (lock) builder.disableAutoCancel() else builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
        val action = builder.build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) {
            toast("Esta câmera não suporta foco por toque.")
            return
        }
        val token = ++focusToken
        showRing(x, y)
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            if (token != focusToken) return@addListener // toque mais novo já assumiu
            val ok = try { future.get().isFocusSuccessful } catch (e: Exception) { false }
            finishRing(ok, lock)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ringDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), color)
    }

    private fun showRing(x: Float, y: Float) {
        focusRing.animate().cancel()
        focusRing.apply {
            translationX = x - ringSize / 2f
            translationY = y - ringSize / 2f
            background = ringDrawable(Color.WHITE)
            alpha = 1f; scaleX = 1.4f; scaleY = 1.4f
            visibility = View.VISIBLE
            animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
    }

    private fun finishRing(ok: Boolean, locked: Boolean) {
        focusRing.background = ringDrawable(if (ok) ACCENT else FAIL)
        if (locked && ok) {
            toast("Foco e exposição travados. Toque para liberar.")
            return // fica visível até o próximo toque
        }
        focusRing.animate().alpha(0f).setStartDelay(800).setDuration(300)
            .withEndAction { focusRing.visibility = View.INVISIBLE }.start()
    }

    // ---------- Câmera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                camera = cam
                setupControls(cam)
            } catch (e: Exception) {
                toast("Não foi possível iniciar a câmera: ${e.message}", long = true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Ajusta os sliders ao que a câmera realmente oferece (zoom mín/máx e faixa de exposição). */
    private fun setupControls(cam: Camera) {
        cam.cameraInfo.zoomState.value?.let { zs ->
            zoomMin = zs.minZoomRatio; zoomMax = zs.maxZoomRatio
            zoomBar.progress = ratioToProgress(zs.zoomRatio)
            zoomLabel.text = zoomText(zs.zoomRatio)
        }
        cam.cameraInfo.zoomState.observe(this) { zs -> zoomLabel.text = zoomText(zs.zoomRatio) }

        val es = cam.cameraInfo.exposureState
        val range = es.exposureCompensationRange
        expLower = range.lower
        expStep = es.exposureCompensationStep.toFloat()
        expBar.isEnabled = es.isExposureCompensationSupported
        expBar.max = (range.upper - range.lower).coerceAtLeast(1)
        expBar.progress = (0 - range.lower).coerceIn(0, expBar.max)
        if (es.isExposureCompensationSupported) cam.cameraControl.setExposureCompensationIndex(0)
        updateExposureLabel(0)
    }

    private fun progressToRatio(p: Int): Float {
        if (zoomMax <= zoomMin) return zoomMin
        val r = zoomMin * (zoomMax / zoomMin).toDouble().pow(p / ZOOM_STEPS.toDouble())
        return r.toFloat().coerceIn(zoomMin, zoomMax)
    }

    private fun ratioToProgress(r: Float): Int {
        if (zoomMax <= zoomMin) return 0
        return (ZOOM_STEPS * ln(r / zoomMin) / ln(zoomMax / zoomMin)).roundToInt().coerceIn(0, ZOOM_STEPS)
    }

    private fun zoomText(r: Float) = "ZOOM  " + String.format(Locale.US, "%.1fx", r)

    private fun updateExposureLabel(idx: Int) {
        expLabel.text = "BRILHO / EXPOSIÇÃO  " + String.format(Locale.US, "%+.1f EV", idx * expStep)
    }

    // ---------- Captura ----------

    private fun setBusy(value: Boolean) {
        busy = value
        captureButton.isEnabled = !value
        if (!value) updateModeUi()
    }

    private fun captureBurst() {
        val cap = imageCapture ?: return
        if (busy) return
        setBusy(true)
        tempFiles.clear()
        val count = burstCount()
        toast("Capturando $count fotos… mantenha o celular firme.")
        captureNext(cap, count, 0)
    }

    private fun captureNext(cap: ImageCapture, total: Int, index: Int) {
        if (index >= total) { finishBurst(); return }
        captureButton.text = "Capturando ${index + 1}/$total…"
        val file = File(cacheDir, "burst_${System.currentTimeMillis()}_$index.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        cap.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                tempFiles.add(file)
                captureNext(cap, total, index + 1)
            }
            override fun onError(exception: ImageCaptureException) {
                toast("Falha na captura: ${exception.message}", long = true)
                finishBurst()
            }
        })
    }

    // MVP conservador: salva a primeira imagem capturada. Não afirma empilhamento ainda.
    private fun finishBurst() {
        val files = tempFiles.toList()
        tempFiles.clear()
        val first = files.firstOrNull()
        if (first == null) { toast("Nenhuma foto capturada."); setBusy(false); return }
        captureButton.text = "Salvando…"
        executor.execute { // cópia de arquivo fora da thread principal
            val msg = try {
                savePhoto(first)
                "Sequência concluída. Foto-base salva em Fotos/Recamera."
            } catch (e: Exception) {
                "Erro ao salvar: ${e.message}"
            }
            files.forEach { it.delete() }
            runOnUiThread { toast(msg, long = true); setBusy(false) }
        }
    }

    private fun savePhoto(source: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Recamera_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Recamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("não foi possível criar o arquivo na galeria")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("não foi possível abrir o arquivo")
            out.use { o -> source.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // não deixa foto vazia/pendente na galeria
            throw e
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        tempFiles.forEach { it.delete() }
    }
}
