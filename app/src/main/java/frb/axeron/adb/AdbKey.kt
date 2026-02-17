package frb.axeron.adb

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*

private const val TAG = "AdbKey"

class AdbKey(private val context: Context, private val name: String) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_kyroos_adb_enc_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16

        // Padding standar untuk tanda tangan RSA ADB
        private val PADDING = byteArrayOf(
            0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14
        )
    }

    private val encryptionKey: Key
    private val privateKey: RSAPrivateKey
    private val publicKey: RSAPublicKey
    private val certificate: X509Certificate
    private val adbKeyStore = PreferenceAdbKeyStore(context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE))

    init {
        this.encryptionKey = getOrCreateEncryptionKey() ?: error("Keystore error")
        this.privateKey = getUnsafeOrCreatePrivateKey()
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)
        ) as RSAPublicKey

        // Membuat sertifikat digital KyrooS secara native
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val certBuilder = X509v3CertificateBuilder(
            X500Name("CN=KyrooS"), BigInteger.ONE, Date(0), Date(2461449600 * 1000), Locale.ROOT,
            X500Name("CN=KyrooS"), SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        
        this.certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBuilder.encoded)) as X509Certificate
    }

    // Fungsi pembantu agar PairingClient bisa mengambil kunci
    fun getPublicKeyString(): String {
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP) + " $name"
    }

    val adbPublicKey: ByteArray by lazy {
        publicKey.adbEncoded(name)
    }

    private fun getOrCreateEncryptionKey(): Key? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return ks.getKey(ENCRYPTION_KEY_ALIAS, null) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(KeyGenParameterSpec.Builder(ENCRYPTION_KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            gen.generateKey()
        }
    }

    private fun getUnsafeOrCreatePrivateKey(): RSAPrivateKey {
        val data = adbKeyStore.get()
        if (data != null) {
            try {
                return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(data)) as RSAPrivateKey
            } catch (e: Exception) {}
        }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
        gen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val pair = gen.generateKeyPair()
        val key = pair.private as RSAPrivateKey
        adbKeyStore.put(key.encoded)
        return key
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    val sslContext: SSLContext by lazy {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        ctx
    }

    private val keyManager get() = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(t: Array<out String>, i: Array<out Principal>?, s: Socket?) = "key"
        override fun getCertificateChain(a: String?) = if (a == "key") arrayOf(certificate) else null
        override fun getPrivateKey(a: String?) = if (a == "key") privateKey else null
        override fun getClientAliases(t: String?, i: Array<out Principal>?) = null
        override fun getServerAliases(t: String?, i: Array<out Principal>?) = null
        override fun chooseServerAlias(t: String, i: Array<out Principal>?, s: Socket?) = null
    }

    private val trustManager get() = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) {}
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {}
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }
}

// Implementasi Storage KyrooS yang disederhanakan
class PreferenceAdbKeyStore(private val preference: SharedPreferences) {
    fun put(bytes: ByteArray) {
        preference.edit().putString("adbkey", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }
    fun get(): ByteArray? {
        val data = preference.getString("adbkey", null) ?: return null
        return Base64.decode(data, Base64.NO_WRAP)
    }
}
