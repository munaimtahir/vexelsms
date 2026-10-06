package pk.vexel.health.smsgateway.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore backed encryption for device credentials and locally queued SMS payloads. */
class SecretStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("gateway_private", android.content.Context.MODE_PRIVATE)
    private val alias = "vexel_sms_gateway_v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized private fun key(): SecretKey {
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        val all = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }

    fun putJson(name: String, json: JSONObject) {
        check(prefs.edit().putString(name, encrypt(json.toString())).commit()) { "Secure settings could not be saved" }
    }
    fun getJson(name: String): JSONObject? = prefs.getString(name, null)?.let { JSONObject(decrypt(it)) }
    fun clear(name: String) { prefs.edit().remove(name).apply() }
}
