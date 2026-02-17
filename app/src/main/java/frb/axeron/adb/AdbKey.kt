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

class AdbKey(private val prefs: SharedPreferences) {
    private val keyPair: KeyPair by lazy {
        val pubKeyStr = prefs.getString("adb_pub_key", null)
        val privKeyStr = prefs.getString("adb_priv_key", null)

        if (pubKeyStr != null && privKeyStr != null) {
            val keyFactory = KeyFactory.getInstance("RSA")
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(Base64.decode(pubKeyStr, Base64.DEFAULT)))
            val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privKeyStr, Base64.DEFAULT)))
            KeyPair(pubKey, privKey)
        } else {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val pair = gen.generateKeyPair()
            prefs.edit().apply {
                putString("adb_pub_key", Base64.encodeToString(pair.public.encoded, Base64.DEFAULT))
                putString("adb_priv_key", Base64.encodeToString(pair.private.encoded, Base64.DEFAULT))
                apply()
            }
            pair
        }
    }

    fun getPublicKey(): RSAPublicKey = keyPair.public as RSAPublicKey
    fun getPrivateKey(): RSAPrivateKey = keyPair.private as RSAPrivateKey
    fun getPublicKeyString(): String = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP) + " kyroos@android"
}
