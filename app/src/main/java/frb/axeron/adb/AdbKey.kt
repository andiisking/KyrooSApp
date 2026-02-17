package frb.axeron.adb

import android.content.SharedPreferences
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Kelas untuk mengelola kunci RSA yang digunakan dalam otentikasi ADB.
 * Menggunakan SharedPreferences untuk menyimpan kunci agar tetap persisten.
 */
class AdbKey(private val prefs: SharedPreferences) {

    // Menggunakan lazy bawaan Kotlin untuk efisiensi memori
    private val keyPair: KeyPair by lazy {
        val pubKeyStr = prefs.getString("adb_pub_key", null)
        val privKeyStr = prefs.getString("adb_priv_key", null)

        if (pubKeyStr != null && privKeyStr != null) {
            // Jika kunci sudah ada di penyimpanan, muat kembali
            try {
                val keyFactory = KeyFactory.getInstance("RSA")
                val pubKey = keyFactory.generatePublic(
                    X509EncodedKeySpec(Base64.decode(pubKeyStr, Base64.DEFAULT))
                )
                val privKey = keyFactory.generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(privKeyStr, Base64.DEFAULT))
                )
                KeyPair(pubKey, privKey)
            } catch (e: Exception) {
                // Jika terjadi error saat memuat, buat kunci baru
                generateAndSaveNewKeyPair()
            }
        } else {
            // Jika kunci belum ada, buat baru
            generateAndSaveNewKeyPair()
        }
    }

    private fun generateAndSaveNewKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val newPair = generator.generateKeyPair()
        
        // Simpan ke SharedPreferences dalam format Base64
        prefs.edit().apply {
            putString("adb_pub_key", Base64.encodeToString(newPair.public.encoded, Base64.DEFAULT))
            putString("adb_priv_key", Base64.encodeToString(newPair.private.encoded, Base64.DEFAULT))
            apply()
        }
        return newPair
    }

    fun getPublicKey(): RSAPublicKey = keyPair.public as RSAPublicKey

    fun getPrivateKey(): RSAPrivateKey = keyPair.private as RSAPrivateKey

    /**
     * Mengembalikan string kunci publik dalam format yang dikenali oleh server ADB.
     */
    fun getPublicKeyString(): String {
        val encoded = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        return "$encoded kyroos@android"
    }
}
