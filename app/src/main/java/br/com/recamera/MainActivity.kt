package br.com.recamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
    private var opencvOk = false
    private lateinit var ai: AiSettings
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
        ai = AiSettings(this)
        opencvOk = Stacker.init()
        buildScreen()
        if (!opencvOk) toast("OpenCV não carregou: as fotos serão salvas sem empilhamento.", long = true)
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
        val gear = Button(this).apply {
            text = "⚙"; textSize = 20f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { showSettings() }
        }
        frame.addView(gear, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(8), dp(8), 0)
        })
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
            text = "Toque na imagem para focar · segure para travar foco/exposição.\nAs fotos da sequência são alinhadas e combinadas. ⚙ = IA Gemini (opcional)."
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

    private fun finishBurst() {
        val files = tempFiles.toList()
        tempFiles.clear()
        if (files.isEmpty()) { toast("Nenhuma foto capturada."); setBusy(false); return }
        captureButton.text = "Processando…"
        executor.execute { // processamento pesado fora da thread principal
            val msg = try { processAndSave(files) } catch (e: Throwable) { "Erro ao salvar: ${e.message}" }
            files.forEach { it.delete() }
            runOnUiThread { toast(msg, long = true); setBusy(false) }
        }
    }

    private fun processAndSave(files: List<File>): String {
        val stamp = System.currentTimeMillis()
        val original = files.first()

        var result: StackResult? = null
        var failure: String? = null
        if (!opencvOk) failure = "OpenCV indisponível"
        else if (files.size < 2) failure = "poucas fotos"
        else try {
            result = Stacker.process(files) { done, total ->
                runOnUiThread { captureButton.text = "Alinhando $done/$total…" }
            }
            if (result == null) failure = "não foi possível alinhar os quadros"
        } catch (e: Throwable) { // inclui falta de memória
            failure = e.javaClass.simpleName
        }

        val res = result
        saveToGallery("Recamera_$stamp.jpg", "Pictures/Recamera") { out ->
            if (res != null) out.write(res.jpeg) else original.inputStream().use { it.copyTo(out) }
        }
        if (res == null) return "Salva sem empilhar ($failure)."
        // Mantém o original (com os metadados da câmera) separado
        saveToGallery("Recamera_${stamp}_original.jpg", "Pictures/Recamera/Originais") { out ->
            original.inputStream().use { it.copyTo(out) }
        }
        var aiNote = ""
        if (ai.enabled) {
            val key = ai.loadKey()
            if (key == null) aiNote = " IA: chave não encontrada, abra ⚙ e informe de novo."
            else try {
                runOnUiThread { captureButton.text = "Enviando à IA…" }
                val enhanced = GeminiClient.enhance(key, ai.model, res.jpeg, ai.imageSize)
                saveToGallery("Recamera_${stamp}_IA.jpg", "Pictures/Recamera/IA") { it.write(enhanced) }
                aiNote = " Versão IA em Fotos/Recamera/IA."
            } catch (e: Throwable) {
                aiNote = " IA não aplicada: ${e.message}."
            }
        }
        return "Foto combinada com ${res.used} de ${res.total} quadros. Original em Fotos/Recamera/Originais.$aiNote"
    }

    private fun saveToGallery(name: String, relativePath: String, write: (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("não foi possível criar o arquivo na galeria")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("não foi possível abrir o arquivo")
            out.use { write(it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // não deixa foto vazia/pendente na galeria
            throw e
        }
    }

    // ---------- Configurações da IA (Gemini) ----------

    private fun showSettings() {
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        col.addView(TextView(this).apply {
            text = "Chave da API Gemini"; setPadding(0, dp(8), 0, 0)
        })
        val keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            hint = if (ai.hasKey()) "Chave salva (digite para trocar)" else "Cole aqui a sua chave"
        }
        col.addView(keyField)
        col.addView(TextView(this).apply { text = "Modelo de imagem"; setPadding(0, dp(12), 0, 0) })
        val modelField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setText(ai.model)
        }
        col.addView(modelField)
        col.addView(TextView(this).apply {
            textSize = 11f
            text = "Padrão: ${GeminiClient.DEFAULT_MODEL} (consegue gerar até 4K). Modelos \"flash\" costumam devolver só 1K."
        })
        col.addView(TextView(this).apply { text = "Tamanho da imagem da IA"; setPadding(0, dp(12), 0, 0) })
        val sizeOpts = listOf(
            "1K" to "1K: rápido e mais barato",
            "2K" to "2K: equilíbrio",
            "4K" to "4K: mais pixels, mais lento e mais caro"
        )
        val sizeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        sizeOpts.forEachIndexed { i, (v, label) ->
            sizeGroup.addView(RadioButton(this).apply { id = 1000 + i; text = label; isChecked = ai.imageSize == v })
        }
        col.addView(sizeGroup)
        val sw = Switch(this).apply {
            text = "Melhorar também com IA (online)"; isChecked = ai.enabled; setPadding(0, dp(12), 0, dp(4))
        }
        col.addView(sw)
        col.addView(TextView(this).apply {
            textSize = 12f
            text = "Desligado por padrão: sem IA o app funciona 100% offline.\n\n" +
                "Com a IA ligada, depois de combinar as fotos o app envia a foto combinada (reduzida a no máximo " +
                "3072 px) ao Google Gemini pela internet, usando a sua chave, e salva o resultado separado em " +
                "Fotos/Recamera/IA. A foto combinada e o original continuam sempre guardados.\n\n" +
                "Atenção: a IA pode alterar detalhes (inclusive rostos e textos), e a imagem dela costuma ter " +
                "resolução diferente da foto combinada. Os pixels extras são detalhes que a IA estima, não detalhes reais da cena. Por isso ela não substitui a sua foto.\n\n" +
                "A chave fica cifrada só neste aparelho e nunca vai para o código ou o GitHub."
        })
        col.addView(Button(this).apply {
            text = "Testar chave (não envia fotos)"
            setOnClickListener {
                val typed = keyField.text.toString().filter { !it.isWhitespace() }
                val key = typed.ifEmpty { ai.loadKey().orEmpty() }
                val model = modelField.text.toString().trim().ifEmpty { GeminiClient.DEFAULT_MODEL }
                if (key.isEmpty()) { toast("Informe a chave primeiro."); return@setOnClickListener }
                toast("Testando…")
                Thread {
                    val msg = try { GeminiClient.test(key, model) } catch (e: Exception) { "Falhou: ${e.message}" }
                    runOnUiThread { toast(msg, long = true) }
                }.start()
            }
        })
        MaterialAlertDialogBuilder(this)
            .setTitle("Configurações de IA")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Salvar") { _, _ ->
                saveSettings(
                    keyField.text.toString().filter { !it.isWhitespace() }, modelField.text.toString().trim(),
                    sizeOpts.getOrNull(sizeGroup.checkedRadioButtonId - 1000)?.first ?: "2K", sw.isChecked
                )
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Apagar chave") { _, _ ->
                ai.clearKey(); ai.enabled = false; toast("Chave apagada. IA desligada.")
            }
            .show()
    }

    private fun saveSettings(key: String, model: String, size: String, enable: Boolean) {
        if (key.isNotEmpty()) {
            if (!Regex("^[A-Za-z0-9_.-]{20,}$").matches(key)) { toast("A chave parece inválida. Confira e cole de novo.", long = true); return }
            ai.saveKey(key)
        }
        ai.model = model.ifEmpty { GeminiClient.DEFAULT_MODEL }
        ai.imageSize = size
        if (enable && !ai.hasKey()) { ai.enabled = false; toast("Informe a chave para ligar a IA.", long = true); return }
        if (enable && !ai.consent) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Enviar fotos à internet?")
                .setMessage(
                    "Com a IA ligada, as fotos combinadas serão enviadas ao Google Gemini, pela internet, usando a sua " +
                    "chave e o seu limite de uso. Não use em fotos que você não quer enviar. " +
                    "Você pode desligar quando quiser em ⚙."
                )
                .setPositiveButton("Concordo") { _, _ -> ai.consent = true; ai.enabled = true; toast("IA ligada.") }
                .setNegativeButton("Cancelar") { _, _ -> ai.enabled = false; toast("IA continua desligada.") }
                .show()
        } else {
            ai.enabled = enable
            toast(if (enable) "IA ligada." else "Configurações salvas.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        tempFiles.forEach { it.delete() }
    }
}
