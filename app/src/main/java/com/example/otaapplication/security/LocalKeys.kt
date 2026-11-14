package com.example.otaapplication.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keys for data at rest on this phone. One AES-256 key lives in the Android Keystore (it never
 * leaves the secure hardware); it wraps the random SQLCipher key and seals advice images.
 * Neither needs the user to authenticate, so background sync keeps working while the app is locked.
 */
object LocalKeys {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ota_local_data"
    private const val PREFS = "ota_secure"
    private const val DB_KEY = "db_key"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    @Volatile private var cachedDbKey: ByteArray? = null

    @Synchronized
    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    /** IV (12 bytes) followed by AES-GCM ciphertext+tag. */
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "Sealed data too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, IV_BYTES))
        }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    /**
     * The SQLCipher key for this phone's databases as a raw-key literal (`x'…'`), so opening a
     * database skips the slow passphrase derivation. Created on first use.
     */
    @Synchronized
    fun databaseKey(context: Context): ByteArray {
        cachedDbKey?.let { return it }
        val prefs = prefs(context)
        val raw = prefs.getString(DB_KEY, null)?.let { open(Base64.decode(it, Base64.NO_WRAP)) }
            ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.also { key ->
                prefs.edit().putString(DB_KEY, Base64.encodeToString(seal(key), Base64.NO_WRAP)).commit()
            }
        return rawKeyLiteral(raw).also { cachedDbKey = it }
    }

    /** Raw 32-byte database key, for the move to the new app (only ever inside an encrypted backup). */
    fun exportDatabaseKey(context: Context): ByteArray {
        val literal = String(databaseKey(context), Charsets.US_ASCII)
        return literal.removePrefix("x'").removeSuffix("'").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Adopts [raw] as this phone's database key (restoring a moved database). */
    @Synchronized
    fun importDatabaseKey(context: Context, raw: ByteArray) {
        require(raw.size == 32)
        prefs(context).edit().putString(DB_KEY, Base64.encodeToString(seal(raw), Base64.NO_WRAP)).commit()
        cachedDbKey = rawKeyLiteral(raw)
    }

    /** Forgets the database key (after the key was found unusable and the databases set aside). */
    @Synchronized
    fun resetDatabaseKey(context: Context) {
        prefs(context).edit().remove(DB_KEY).commit()
        cachedDbKey = null
    }

    fun rawKeyLiteral(raw: ByteArray): ByteArray =
        ("x'" + raw.joinToString("") { "%02x".format(it) } + "'").toByteArray(Charsets.US_ASCII)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
