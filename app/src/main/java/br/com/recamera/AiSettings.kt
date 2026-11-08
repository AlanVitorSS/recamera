package br.com.recamera

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Configurações da IA. A chave da API é digitada pelo usuário, nunca vai no código/repositório,
 * e fica cifrada com uma chave do Android Keystore (só este app, só este aparelho).
 */
class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("recamera_ai", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }

    /** O usuário já leu e aceitou que a foto será enviada à internet. */
    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(v) { prefs.edit().putBoolean("consent", v).apply() }

    var model: String
        get() {
            val m = prefs.getString("model", null)?.takeIf { it.isNotBlank() }
            return if (m == null || m in RETIRED_MODELS) GeminiClient.DEFAULT_MODEL else m
        }
        set(v) { prefs.edit().putString("model", v).apply() }

    /** Tamanho pedido à IA: 1K, 2K ou 4K (maior lado aproximado). */
    var imageSize: String
        get() = prefs.getString("size", null)?.takeIf { it in listOf("1K", "2K", "4K") } ?: "2K"
        set(v) { prefs.edit().putString("size", v).apply() }

    fun hasKey(): Boolean = prefs.contains("key")

    fun saveKey(key: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        prefs.edit().putString("key", packed).apply()
    }

    fun loadKey(): String? {
        val packed = prefs.getString("key", null) ?: return null
        return try {
            val (iv, enc) = packed.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch (e: Exception) {
            null // chave do Keystore perdida ou dado corrompido: o usuário digita de novo
        }
    }

    fun clearKey() { prefs.edit().remove("key").apply() }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "recamera_ai_key"
        // Modelos que o Google anunciou como desativados: troca automaticamente pelo padrão
        val RETIRED_MODELS = setOf("gemini-2.5-flash-image")
    }
}
