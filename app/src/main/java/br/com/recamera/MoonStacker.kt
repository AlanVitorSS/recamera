package br.com.recamera

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.roundToInt

class MoonResult(val jpeg: ByteArray, val used: Int, val detected: Int, val total: Int, val bestFrame: File)

/**
 * Sessão do modo Lua. Cada quadro é processado assim que é capturado (detecta a Lua, recorta, mede a
 * nitidez) e o arquivo grande é apagado na hora; só o melhor quadro inteiro fica guardado. Assim dá para
 * capturar centenas de quadros sem lotar o armazenamento e o final da captura fica rápido.
 *
 * No fim: escolhe os recortes mais nítidos, alinha (ECC), combina numa grade 2x/3x maior
 * (super-resolução pelos deslocamentos reais entre quadros) e realça os detalhes em duas escalas.
 */
class MoonSession {
    private class Det(val cx: Double, val cy: Double, val r: Double, val reducedCols: Int)
    private class Crop(val mat: Mat, val score: Double)

    private val crops = ArrayList<Crop>()
    private var side = 0
    private var up = 1
    private var maxKeep = 8
    private var bestScore = -1.0

    var bestFile: File? = null
        private set
    var total = 0
        private set
    var detected = 0
        private set

    /** Processa um quadro recém-capturado. Chamar sempre da mesma thread. */
    fun add(file: File) {
        total++
        try {
            val det = detect(file) ?: return
            val full = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
            if (full.empty()) { full.release(); return }
            val sc = full.cols().toDouble() / det.reducedCols
            if (side == 0) { // o tamanho do recorte vem do primeiro quadro com a Lua
                side = (det.r * sc * 2.6).toInt().coerceIn(64, 1400)
                side -= side % 2
                up = when { side <= 350 -> 3; side <= 900 -> 2; else -> 1 }
                maxKeep = (120_000_000 / (side * side * 3)).coerceIn(8, 300)
            }
            if (full.cols() < side || full.rows() < side) { full.release(); return }
            val x0 = (det.cx * sc - side / 2.0).roundToInt().coerceIn(0, full.cols() - side)
            val y0 = (det.cy * sc - side / 2.0).roundToInt().coerceIn(0, full.rows() - side)
            val crop = full.submat(Rect(x0, y0, side, side)).clone()
            full.release()
            detected++
            val score = sharpness(crop)
            crops.add(Crop(crop, score))
            if (score > bestScore) {
                bestScore = score
                val old = bestFile
                bestFile = file
                if (old != null && old != file) old.delete()
            }
            if (crops.size > maxKeep) { // memória: descarta o pior recorte
                val worst = crops.minByOrNull { it.score }!!
                crops.remove(worst); worst.mat.release()
            }
        } finally {
            if (file != bestFile) file.delete()
        }
    }

    fun release() {
        crops.forEach { it.mat.release() }
        crops.clear()
        bestFile?.delete()
        bestFile = null
    }

    /** Localiza a Lua numa versão 1/4 do quadro; devolve centro e raio (em pixels dessa versão). */
    private fun detect(file: File): Det? {
        val g = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_REDUCED_GRAYSCALE_4)
        if (g.empty()) { g.release(); return null }
        val bw = Mat()
        val hier = Mat()
        try {
            Imgproc.GaussianBlur(g, g, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(g, bw, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(bw, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val best = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val frac = Imgproc.contourArea(best) / (g.cols().toDouble() * g.rows())
            if (frac < 0.0002 || frac > 0.5) return null // pequeno demais (ruído) ou céu claro demais
            val center = Point()
            val radius = FloatArray(1)
            Imgproc.minEnclosingCircle(MatOfPoint2f(*best.toArray()), center, radius)
            return Det(center.x, center.y, radius[0].toDouble(), g.cols())
        } finally {
            g.release(); bw.release(); hier.release()
        }
    }

    private fun gray32(bgr: Mat): Mat {
        val g = Mat(); Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY)
        val f = Mat(); g.convertTo(f, CvType.CV_32F, 1.0 / 255.0); g.release()
        Imgproc.GaussianBlur(f, f, Size(3.0, 3.0), 0.0)
        return f
    }

    private fun sharpness(bgr: Mat): Double {
        val g = gray32(bgr)
        val lap = Mat(); Imgproc.Laplacian(g, lap, CvType.CV_32F)
        val m = MatOfDouble(); val s = MatOfDouble(); Core.meanStdDev(lap, m, s)
        val sd = s.get(0, 0)[0]
        g.release(); lap.release()
        return sd * sd
    }

    /** Junta os melhores recortes. Devolve null se a Lua foi achada em menos de 3 quadros. */
    fun finish(onProgress: (stage: String, done: Int, total: Int) -> Unit): MoonResult? {
        val keep = ArrayList<Mat>()
        try {
            val ref0 = bestFile ?: return null
            if (crops.size < 3) return null

            crops.sortByDescending { it.score }
            val use = maxOf(minOf(8, crops.size), (crops.size * KEEP_FRACTION).roundToInt())
                .coerceAtMost(crops.size).coerceAtMost(MAX_USED)
            val sel = crops.take(use)
            val refGray = gray32(sel[0].mat); keep.add(refGray)
            val levels = when { side >= 240 -> 3; side >= 120 -> 2; else -> 1 }

            val outSide = side * up
            val sum = Mat.zeros(outSide, outSide, CvType.CV_32FC3); keep.add(sum)
            var used = 0
            for ((k, c) in sel.withIndex()) {
                onProgress("Combinando", k + 1, sel.size)
                val warp: Mat
                if (k == 0) warp = Mat.eye(2, 3, CvType.CV_32F)
                else {
                    val g = gray32(c.mat)
                    val w = Stacker.alignWarp(refGray, g, levels)
                    g.release()
                    if (w == null) continue
                    warp = w
                }
                val w6 = FloatArray(6); warp.get(0, 0, w6); warp.release()
                val m = Mat(2, 3, CvType.CV_32F)
                m.put(0, 0, floatArrayOf(w6[0] / up, w6[1] / up, w6[2], w6[3] / up, w6[4] / up, w6[5]))
                val f = Mat(); c.mat.convertTo(f, CvType.CV_32FC3)
                val big = Mat()
                Imgproc.warpAffine(
                    f, big, m, Size(outSide.toDouble(), outSide.toDouble()),
                    Imgproc.INTER_CUBIC + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE, Scalar(0.0)
                )
                Core.add(sum, big, sum)
                f.release(); big.release(); m.release()
                used++
            }
            if (used < 2) return null
            sum.convertTo(sum, CvType.CV_32FC3, 1.0 / used)

            // Realce em duas escalas (fina e média)
            val b1 = Mat(); keep.add(b1)
            val b2 = Mat(); keep.add(b2)
            Imgproc.GaussianBlur(sum, b1, Size(0.0, 0.0), 0.7 * up)
            Imgproc.GaussianBlur(sum, b2, Size(0.0, 0.0), 2.0 * up)
            val mid = Mat(); keep.add(mid)
            Core.subtract(b1, b2, mid)
            val out = Mat(); keep.add(out)
            Core.addWeighted(sum, 1.0 + SHARP_FINE, b1, -SHARP_FINE, 0.0, out)
            Core.addWeighted(out, 1.0, mid, SHARP_MID, 0.0, out)

            val out8 = Mat(); keep.add(out8)
            out.convertTo(out8, CvType.CV_8UC3) // satura em 0..255
            val buf = MatOfByte(); keep.add(buf)
            Imgcodecs.imencode(".jpg", out8, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY))
            return MoonResult(buf.toArray(), used, detected, total, ref0)
        } finally {
            keep.forEach { it.release() }
        }
    }

    private companion object {
        const val KEEP_FRACTION = 0.5
        const val MAX_USED = 150
        const val SHARP_FINE = 1.0
        const val SHARP_MID = 0.6
        const val JPEG_QUALITY = 95
    }
}
