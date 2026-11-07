package br.com.recamera

import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2

class StackResult(val jpeg: ByteArray, val used: Int, val total: Int)

/**
 * Empilhamento de várias fotos:
 * 1. escolhe o quadro mais nítido como referência;
 * 2. alinha os demais (ECC, translação + rotação) em baixa resolução e aplica em resolução total;
 * 3. faz a média ponderada, com peso zero onde o quadro difere da referência (movimento/fantasma);
 * 4. aplica nitidez leve (unsharp mask) no resultado.
 */
object Stacker {
    private const val GHOST_T0 = 0.05   // abaixo disso: peso total
    private const val GHOST_T1 = 0.12   // acima disso: peso zero
    private const val SHARPEN = 0.5
    private const val JPEG_QUALITY = 95

    @Volatile private var loaded = false

    fun init(): Boolean {
        if (!loaded) loaded = try { OpenCVLoader.initLocal() } catch (e: Throwable) { false }
        return loaded
    }

    private class Frame(val file: File, val small: Mat, val score: Double, val mean: Double)

    /** Carrega o quadro em 1/4 do tamanho, em cinza float, e mede nitidez e brilho médio. */
    private fun loadSmall(file: File): Frame? {
        val bgr = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_REDUCED_COLOR_4)
        if (bgr.empty()) { bgr.release(); return null }
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        bgr.release()
        val g = Mat()
        gray.convertTo(g, CvType.CV_32F, 1.0 / 255.0)
        gray.release()
        Imgproc.GaussianBlur(g, g, Size(3.0, 3.0), 0.0)
        val lap = Mat()
        Imgproc.Laplacian(g, lap, CvType.CV_32F)
        val lm = MatOfDouble(); val ls = MatOfDouble()
        Core.meanStdDev(lap, lm, ls)
        val sd = ls.get(0, 0)[0]
        val gm = MatOfDouble(); val gs = MatOfDouble()
        Core.meanStdDev(g, gm, gs)
        val mean = gm.get(0, 0)[0]
        lap.release()
        return Frame(file, g, sd * sd, mean)
    }

    /** Matriz 2x3 (em escala do quadro pequeno) que alinha [img] à [ref], ou null se falhar. */
    private fun alignWarp(ref: Mat, img: Mat): Mat? {
        val levels = 3
        val refP = ArrayList<Mat>(); val imgP = ArrayList<Mat>()
        refP.add(ref); imgP.add(img)
        for (i in 1 until levels) {
            val r = Mat(); Imgproc.pyrDown(refP[i - 1], r); refP.add(r)
            val m = Mat(); Imgproc.pyrDown(imgP[i - 1], m); imgP.add(m)
        }
        val warp = Mat.eye(2, 3, CvType.CV_32F)
        var ok = true
        try {
            for (lvl in levels - 1 downTo 0) {
                val crit = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-5)
                Video.findTransformECC(refP[lvl], imgP[lvl], warp, Video.MOTION_EUCLIDEAN, crit, Mat(), 5)
                if (lvl > 0) { // sobe um nível de resolução: translação dobra
                    warp.put(0, 2, warp.get(0, 2)[0] * 2.0)
                    warp.put(1, 2, warp.get(1, 2)[0] * 2.0)
                }
            }
        } catch (e: Exception) {
            ok = false
        }
        for (i in 1 until levels) { refP[i].release(); imgP[i].release() }
        if (!ok) { warp.release(); return null }
        val angle = atan2(warp.get(1, 0)[0], warp.get(0, 0)[0])
        val tx = warp.get(0, 2)[0]; val ty = warp.get(1, 2)[0]
        val tooFar = abs(angle) > 0.1 || abs(tx) > 0.2 * ref.cols() || abs(ty) > 0.2 * ref.rows()
        if (tooFar) { warp.release(); return null }
        return warp
    }

    fun process(files: List<File>, onProgress: (done: Int, total: Int) -> Unit): StackResult? {
        val frames = ArrayList<Frame>()
        val keep = ArrayList<Mat>()
        try {
            for (f in files) loadSmall(f)?.let { frames.add(it); keep.add(it.small) }
            if (frames.size < 2) return null

            val refIdx = frames.indices.maxByOrNull { frames[it].score } ?: 0
            val ref = frames[refIdx]
            val refBgr = Imgcodecs.imread(ref.file.absolutePath, Imgcodecs.IMREAD_COLOR)
            keep.add(refBgr)
            if (refBgr.empty()) return null
            if ((refBgr.cols() > refBgr.rows()) != (ref.small.cols() > ref.small.rows())) return null
            val scale = refBgr.cols().toDouble() / ref.small.cols()

            // Cinza da referência (para detectar movimento) e acumuladores por canal
            val refGray = Mat(); keep.add(refGray)
            Imgproc.cvtColor(refBgr, refGray, Imgproc.COLOR_BGR2GRAY)
            refGray.convertTo(refGray, CvType.CV_32F, 1.0 / 255.0)
            Imgproc.GaussianBlur(refGray, refGray, Size(5.0, 5.0), 0.0)

            val refPlanes = ArrayList<Mat>()
            Core.split(refBgr, refPlanes)
            val sum = ArrayList<Mat>()
            for (p in refPlanes) { val s = Mat(); p.convertTo(s, CvType.CV_32F); sum.add(s); keep.add(s); p.release() }
            val wsum = Mat(refBgr.rows(), refBgr.cols(), CvType.CV_32F, Scalar(1.0)); keep.add(wsum)

            var used = 1
            var done = 0
            val others = frames.size - 1
            onProgress(0, others)
            for ((i, fr) in frames.withIndex()) {
                if (i == refIdx) continue
                done++
                onProgress(done, others)

                val w = alignWarp(ref.small, fr.small) ?: continue
                w.put(0, 2, w.get(0, 2)[0] * scale)
                w.put(1, 2, w.get(1, 2)[0] * scale)

                val bgr = Imgcodecs.imread(fr.file.absolutePath, Imgcodecs.IMREAD_COLOR)
                if (bgr.empty() || bgr.cols() != refBgr.cols() || bgr.rows() != refBgr.rows()) {
                    bgr.release(); w.release(); continue
                }
                val aligned = Mat()
                Imgproc.warpAffine(
                    bgr, aligned, w, bgr.size(),
                    Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REFLECT, Scalar(0.0)
                )
                bgr.release(); w.release()

                // Compensa pequenas variações de brilho entre os quadros
                val gain = (ref.mean / fr.mean.coerceAtLeast(1e-3)).coerceIn(0.7, 1.4)

                // Peso por pixel: 1 onde igual à referência, 0 onde houve movimento
                val g = Mat(); Imgproc.cvtColor(aligned, g, Imgproc.COLOR_BGR2GRAY)
                val g32 = Mat(); g.convertTo(g32, CvType.CV_32F, gain / 255.0); g.release()
                Imgproc.GaussianBlur(g32, g32, Size(5.0, 5.0), 0.0)
                val wt = Mat(); Core.absdiff(refGray, g32, wt); g32.release()
                wt.convertTo(wt, CvType.CV_32F, -1.0 / (GHOST_T1 - GHOST_T0), GHOST_T1 / (GHOST_T1 - GHOST_T0))
                Imgproc.threshold(wt, wt, 1.0, 1.0, Imgproc.THRESH_TRUNC)
                Imgproc.threshold(wt, wt, 0.0, 0.0, Imgproc.THRESH_TOZERO)

                val planes = ArrayList<Mat>()
                Core.split(aligned, planes); aligned.release()
                for (c in 0..2) {
                    val t = Mat()
                    planes[c].convertTo(t, CvType.CV_32F, gain)
                    Core.multiply(t, wt, t)
                    Core.add(sum[c], t, sum[c])
                    t.release(); planes[c].release()
                }
                Core.add(wsum, wt, wsum)
                wt.release()
                used++
            }

            // Média ponderada
            val outPlanes = ArrayList<Mat>()
            for (c in 0..2) {
                Core.divide(sum[c], wsum, sum[c])
                val o = Mat(); sum[c].convertTo(o, CvType.CV_8U)
                outPlanes.add(o); keep.add(o)
            }
            val merged = Mat(); keep.add(merged)
            Core.merge(outPlanes, merged)

            // Nitidez leve (o empilhamento já reduziu o ruído)
            val blur = Mat(); keep.add(blur)
            Imgproc.GaussianBlur(merged, blur, Size(0.0, 0.0), 1.5)
            Core.addWeighted(merged, 1.0 + SHARPEN, blur, -SHARPEN, 0.0, merged)

            val buf = MatOfByte(); keep.add(buf)
            Imgcodecs.imencode(".jpg", merged, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY))
            return StackResult(buf.toArray(), used, files.size)
        } finally {
            keep.forEach { it.release() }
        }
    }
}
