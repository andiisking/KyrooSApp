package frb.axeron.adb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt  // ✅ TAMBAH INI
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
import javax.net.ssl.*

private const val TAG = "AdbKey"

class AdbKey(private val context: Context, private val name: String) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_kyroos_adb_enc_"
        
        // ✅ BARU: Install Conscrypt sebagai provider pertama
        init {
            try {
                // Hapus Conscrypt jika sudah ada
                val existingProvider = Security.getProvider(Conscrypt.PROVIDER_NAME)
                if (existingProvider != null) {
                    Security.removeProvider(Conscrypt.PROVIDER_NAME)
                }
                // Install Conscrypt sebagai provider pertama (prioritas tertinggi)
                Security.insertProviderAt(Conscrypt.newProviderBuilder().provideTrustManager(true).build(), 1)
                Log.d(TAG, "Conscrypt provider installed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install Conscrypt provider", e)
            }
        }
        
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
    val publicKey: RSAPublicKey
    private val certificate: X509Certificate
    private val adbKeyStore = PreferenceAdbKeyStore(
        context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
    )

    init {
        this.encryptionKey = getOrCreateEncryptionKey() ?: error("Keystore error")
        this.privateKey = getUnsafeOrCreatePrivateKey()
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)
        ) as RSAPublicKey

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val certBuilder = X509v3CertificateBuilder(
            X500Name("CN=KyrooS"), 
            BigInteger.ONE, 
            Date(0), 
            Date(2461449600L * 1000), 
            Locale.ROOT,
            X500Name("CN=KyrooS"), 
            SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        
        this.certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBuilder.encoded)) as X509Certificate
    }

    fun getPublicKeyString(): String {
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP) + " $name"
    }

    val adbPublicKey: ByteArray by lazy {
        publicKey.adbEncoded(name) 
    }

    private fun getOrCreateEncryptionKey(): Key? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return ks.getKey(ENCRYPTION_KEY_ALIAS, null) ?: run {
            val gen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, 
                ANDROID_KEYSTORE
            )
            gen.init(
                KeyGenParameterSpec.Builder(
                    ENCRYPTION_KEY_ALIAS, 
                    KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            )
            gen.generateKey()
        }
    }

    private fun getUnsafeOrCreatePrivateKey(): RSAPrivateKey {
        val data = adbKeyStore.get()
        if (data != null) {
            try {
                return KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(data)) as RSAPrivateKey
            } catch (e: Exception) {
            }
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

    @Volatile
    private var sslContextInstance: SSLContext? = null

    val sslContext: SSLContext
        @RequiresApi(Build.VERSION_CODES.R)
        get() {
            val cached = sslContextInstance
            if (cached != null) return cached
            
            synchronized(this) {
                var result = sslContextInstance
                if (result == null) {
                    result = createSslContext()
                    sslContextInstance = result
                }
                return result
            }
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createSslContext(): SSLContext {
        // ✅ PERBAIKAN: Gunakan "TLSv1.3" dengan Conscrypt provider
        val ctx = SSLContext.getInstance("TLSv1.3", Conscrypt.PROVIDER_NAME)
        ctx.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        Log.d(TAG, "SSLContext created with Conscrypt provider")
        return ctx
    }

    private val keyManager: X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?, 
            issuers: Array<out Principal>?, 
            socket: Socket?
        ): String = "key"
        
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = 
            if (alias == "key") arrayOf(certificate) else null
            
        override fun getPrivateKey(alias: String?): PrivateKey? = 
            if (alias == "key") privateKey else null
            
        override fun getClientAliases(
            keyType: String?, 
            issuers: Array<out Principal>?
        ): Array<String>? = null
            
        override fun getServerAliases(
            keyType: String?, 
            issuers: Array<out Principal>?
        ): Array<String>? = null
            
        override fun chooseServerAlias(
            keyType: String, 
            issuers: Array<out Principal>?, 
            socket: Socket?
        ): String? = null
    }

    private val trustManager: X509ExtendedTrustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?, 
            authType: String?, 
            socket: Socket?
        ) {}
        
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?, 
            authType: String?, 
            engine: SSLEngine?
        ) {}
        
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?, 
            authType: String?
        ) {}
        
        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?, 
            authType: String?, 
            socket: Socket?
        ) {}
        
        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?, 
            authType: String?, 
            engine: SSLEngine?
        ) {}
        
        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?, 
            authType: String?
        ) {}
        
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

class PreferenceAdbKeyStore(private val preference: SharedPreferences) {
    fun put(bytes: ByteArray) {
        preference.edit()
            .putString("adbkey", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .apply()
    }
    
    fun get(): ByteArray? {
        val data = preference.getString("adbkey", null) ?: return null
        return Base64.decode(data, Base64.NO_WRAP)
    }
}

private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
private const val RSAPublicKey_Size = 524

private fun BigInteger.toAdbEncoded(): IntArray {
    val encoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)
    var tmp = this.add(BigInteger.ZERO)
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        encoded[i] = out[1].toInt()
    }
    return encoded
}

fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    val bytes = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(bytes)
    nameBytes.copyInto(bytes, base64Bytes.size)
    return bytes
}
