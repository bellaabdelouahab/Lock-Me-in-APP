package app.lockmein

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
 * Keeps the friend's secret encrypted with a key that lives in Android's key store and cannot be read out of it.
 * The owner can see the secret once, while it is made; after that only this app can use it, to check codes.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("lock_secret", Context.MODE_PRIVATE)

    val hasSecret: Boolean get() = prefs.contains(CIPHERTEXT)

    fun save(secret: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP))
            .apply()
    }

    /** The secret, or null if none was saved or the key no longer opens it. */
    fun load(): ByteArray? = runCatching {
        val iv = Base64.decode(prefs.getString(IV, null) ?: return null, Base64.NO_WRAP)
        val data = Base64.decode(prefs.getString(CIPHERTEXT, null) ?: return null, Base64.NO_WRAP)
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(data)
    }.getOrNull()

    fun clear() {
        prefs.edit().clear().apply()
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "lock_me_in_friend_secret"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = "iv"
        const val CIPHERTEXT = "secret"
    }
}
