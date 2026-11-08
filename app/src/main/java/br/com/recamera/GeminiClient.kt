package br.com.recamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.math.roundToInt

/** Cliente mínimo da API Gemini (REST). A chave vai no cabeçalho, nunca na URL nem em log. */
object GeminiClient {
    const val DEFAULT_MODEL = "gemini-3-pro-image-preview"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MAX_SIDE = 3072
    private const val PROMPT =
        "Upscale and enhance this photograph to a higher resolution: reduce noise and grain, " +
        "recover sharpness and fine detail, and correct exposure and color naturally. " +
        "Keep the composition, framing, objects, people, faces and any text exactly the same. " +
        "Do not add, remove or invent anything. Return only the enhanced image."

    // Proporções aceitas pela API de imagem
    private val RATIOS = listOf(
        "1:1" to 1.0, "2:3" to 2.0 / 3, "3:2" to 1.5, "3:4" to 0.75, "4:3" to 4.0 / 3,
        "4:5" to 0.8, "5:4" to 1.25, "9:16" to 9.0 / 16, "16:9" to 16.0 / 9, "21:9" to 21.0 / 9
    )

    private fun nearestRatio(w: Int, h: Int): String {
        val r = Math.log(w.toDouble() / h)
        return RATIOS.minByOrNull { Math.abs(Math.log(it.second) - r) }!!.first
    }

    private fun checkModel(model: String): String {
        if (!Regex("^[A-Za-z0-9._-]{3,80}$").matches(model)) throw IOException("nome de modelo inválido")
        return model
    }

    /** Verifica a chave e o modelo sem enviar nenhuma foto. */
    fun test(apiKey: String, model: String): String {
        request(BASE + checkModel(model), apiKey, null, 20_000)
        return "Chave válida. Modelo encontrado."
    }

    private class Shrunk(val jpeg: ByteArray, val w: Int, val h: Int)

    private fun buildBody(b64: String, aspect: String, size: String?): ByteArray {
        val cfg = JSONObject().put("aspectRatio", aspect)
        if (size != null) cfg.put("imageSize", size)
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64))))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                .put("imageConfig", cfg))
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** Envia a foto (reduzida a no máx. 3072 px) e devolve a versão melhorada em JPEG. */
    fun enhance(apiKey: String, model: String, jpeg: ByteArray, imageSize: String): ByteArray {
        val small = shrinkToJpeg(jpeg)
        val b64 = Base64.encodeToString(small.jpeg, Base64.NO_WRAP)
        val aspect = nearestRatio(small.w, small.h)
        val url = BASE + checkModel(model) + ":generateContent"

        val text = try {
            request(url, apiKey, buildBody(b64, aspect, imageSize), 240_000)
        } catch (e: IOException) {
            // Alguns modelos não aceitam "imageSize": tenta de novo só com a proporção
            if (e.message?.startsWith("erro 400") == true) request(url, apiKey, buildBody(b64, aspect, null), 240_000)
            else throw e
        }
        val resp = JSONObject(text)
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                val data = inline.optString("data")
                if (data.isNotEmpty()) return toJpeg(Base64.decode(data, Base64.DEFAULT))
            }
        }
        val block = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        throw IOException(if (block.isNotEmpty()) "a IA bloqueou a imagem ($block)" else "a IA não devolveu imagem")
    }

    private fun shrinkToJpeg(src: ByteArray): Shrunk {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("não foi possível ler a foto")
        val longest = maxOf(bmp.width, bmp.height)
        val scaled = if (longest > MAX_SIDE) {
            val f = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).roundToInt(), (bmp.height * f).roundToInt(), true)
        } else bmp
        if (scaled !== bmp) bmp.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val w = scaled.width; val h = scaled.height
        scaled.recycle()
        return Shrunk(out.toByteArray(), w, h)
    }

    private fun toJpeg(img: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(img, 0, img.size) ?: throw IOException("imagem da IA ilegível")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun request(url: String, key: String, body: ByteArray?, readTimeoutMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("x-goog-api-key", key)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(friendly(code, text))
            return text
        } catch (e: UnknownHostException) {
            throw IOException("sem conexão com a internet")
        } catch (e: SocketTimeoutException) {
            throw IOException("tempo esgotado ao falar com a IA")
        } finally {
            c.disconnect()
        }
    }

    private fun friendly(code: Int, text: String): String {
        val apiMsg = try { JSONObject(text).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
        return when {
            code == 401 || code == 403 -> "chave inválida ou sem permissão para este modelo"
            code == 400 && apiMsg.contains("API key", ignoreCase = true) -> "chave inválida"
            code == 404 -> "modelo não encontrado: confira o nome nas configurações"
            code == 429 -> "limite de uso (cota) atingido; tente mais tarde"
            apiMsg.isNotEmpty() -> "erro $code: ${apiMsg.take(120)}"
            else -> "erro $code"
        }
    }
}
