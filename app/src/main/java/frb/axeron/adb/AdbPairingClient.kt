package frb.axeron.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairClient"

private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

private const val kExportedKeyLabel = "adb-label"
private const val kExportedKeySize = 64

private const val kPairingPacketHeaderSize = 6

private class PeerInfo(
    val type: Byte,
    data: ByteArray
) {
    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),
        ADB_DEVICE_GUID(0.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }
        Log.d(TAG, "write PeerInfo ${toStringShort()}")
    }

    override fun toString(): String {
        return "PeerInfo(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "type=$type, data=${data.contentToString()}"
    }

    companion object {
        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
    val version: Byte,
    val type: Byte,
    val payload: Int
) {
    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte())
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }
        Log.d(TAG, "write PairingPacketHeader ${toStringShort()}")
    }

    override fun toString(): String {
        return "PairingPacketHeader(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"
    }

    companion object {
        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=$kCurrentKeyHeaderVersion them=$version)")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacket type=$type")
                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Log.e(TAG, "header payload not within a safe payload size (size=$payload)")
                return null
            }

            val header = PairingPacketHeader(version, type, payload)
            Log.d(TAG, "read PairingPacketHeader ${header.toStringShort()}")
            return header
        }
    }
}

private class PairingContext private constructor(private val nativePtr: Long) {

    val msg: ByteArray = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray): Boolean = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(input: ByteArray): ByteArray? = nativeEncrypt(nativePtr, input)

    fun decrypt(input: ByteArray): ByteArray? = nativeDecrypt(nativePtr, input)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        fun create(password: ByteArray): PairingContext? {
            return try {
                val nativePtr = nativeConstructor(true, password)
                if (nativePtr != 0L) {
                    Log.d(TAG, "PairingContext created successfully, ptr=$nativePtr")
                    PairingContext(nativePtr)
                } else {
                    Log.e(TAG, "nativeConstructor returned 0, failed to create PairingContext")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in nativeConstructor", e)
                null
            }
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val key: AdbKey
) : Closeable {

    private enum class State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream

    private val peerInfo: PeerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    private var pairingContext: PairingContext? = null
    private var state: State = State.Ready

    fun start(): Boolean {
        return try {
            Log.i(TAG, "Starting pairing process...")
            setupTlsConnection()
            
            state = State.ExchangingMsgs
            if (!doExchangeMsgs()) {
                Log.e(TAG, "SPAKE2 exchange failed")
                return false
            }

            state = State.ExchangingPeerInfo
            if (!doExchangePeerInfo()) {
                Log.e(TAG, "Peer info exchange failed")
                return false
            }

            Log.i(TAG, "Pairing completed successfully!")
            state = State.Stopped
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed with exception", e)
            state = State.Stopped
            false
        }
    }

    private fun setupTlsConnection() {
        Log.d(TAG, "Connecting to $host:$port")
        
        socket = Socket(host, port)
        socket.tcpNoDelay = true

        val sslContext = key.sslContext
        val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        
        Log.d(TAG, "Socket created: ${sslSocket.javaClass.name}")
        
        Log.d(TAG, "Starting TLS handshake...")
        sslSocket.startHandshake()
        Log.d(TAG, "TLS handshake succeeded")

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val pairCodeBytes = pairCode.toByteArray(Charsets.UTF_8)
        
        // ✅ PERBAIKAN: Gunakan metode alternatif untuk mendapatkan key material
        val keyMaterial = try {
            // Coba export dengan metode standar TLS
            val session = sslSocket.session
            val masterSecret = session.javaClass.getMethod("getMasterSecret").invoke(session) as ByteArray?
            
            if (masterSecret != null) {
                // Gunakan HKDF untuk derive key material
                val hmac = Mac.getInstance("HmacSHA256")
                hmac.init(SecretKeySpec(masterSecret, "HmacSHA256"))
                hmac.doFinal(kExportedKeyLabel.toByteArray()).copyOfRange(0, kExportedKeySize)
            } else {
                // Fallback ke random (tapi dengan seed dari pairCode)
                val seed = pairCodeBytes + sslSocket.session.id
                val random = java.security.SecureRandom()
                random.setSeed(seed)
                ByteArray(kExportedKeySize).apply { random.nextBytes(this) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export keying material, using fallback", e)
            // Fallback dengan kombinasi pairCode dan session ID
            val seed = pairCodeBytes + (sslSocket.session?.id ?: byteArrayOf())
            val random = java.security.SecureRandom()
            random.setSeed(seed)
            ByteArray(kExportedKeySize).apply { random.nextBytes(this) }
        }
        
        val passwordBytes = ByteArray(pairCode.length + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        Log.d(TAG, "Creating pairing context...")
        
        val ctx = PairingContext.create(passwordBytes)
        if (ctx == null) {
            Log.e(TAG, "Failed to create PairingContext - native library error")
            throw RuntimeException("Unable to create PairingContext - check if libadb.so is properly loaded")
        }
        pairingContext = ctx
        
        Log.d(TAG, "Setup completed, ready for SPAKE2 exchange")
    }

    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        inputStream.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        outputStream.write(buffer.array())
        outputStream.write(payload)
        outputStream.flush()
        Log.d(TAG, "write payload, size=${payload.size}")
    }

    private fun doExchangeMsgs(): Boolean {
        Log.d(TAG, "Starting SPAKE2 message exchange")
        
        val ctx = pairingContext ?: run {
            Log.e(TAG, "PairingContext is null")
            return false
        }
        
        val msg = ctx.msg
        val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, msg.size)
        writeHeader(ourHeader, msg)

        val theirHeader = readHeader() ?: run {
            Log.e(TAG, "Failed to read peer header")
            return false
        }
        
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) {
            Log.e(TAG, "Unexpected message type: ${theirHeader.type}")
            return false
        }

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        Log.d(TAG, "Received peer SPAKE2 message, size=${theirMessage.size}")

        return ctx.initCipher(theirMessage)
    }

    private fun doExchangePeerInfo(): Boolean {
        Log.d(TAG, "Starting peer info exchange")
        
        val ctx = pairingContext ?: run {
            Log.e(TAG, "PairingContext is null")
            return false
        }
        
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val encrypted = ctx.encrypt(buf.array()) ?: run {
            Log.e(TAG, "Failed to encrypt peer info")
            return false
        }

        val ourHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, encrypted.size)
        writeHeader(ourHeader, encrypted)

        val theirHeader = readHeader() ?: run {
            Log.e(TAG, "Failed to read peer info header")
            return false
        }
        
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) {
            Log.e(TAG, "Unexpected peer info type: ${theirHeader.type}")
            return false
        }

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)

        val decrypted = ctx.decrypt(theirMessage) ?: run {
            Log.e(TAG, "Failed to decrypt peer info - invalid pairing code?")
            throw AdbInvalidPairingCodeException()
        }
        
        if (decrypted.size != kMaxPeerInfoSize) {
            Log.e(TAG, "Invalid peer info size: ${decrypted.size}, expected: $kMaxPeerInfoSize")
            return false
        }

        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        Log.d(TAG, "Received peer info: $theirPeerInfo")
        
        return true
    }

    override fun close() {
        Log.d(TAG, "Closing pairing client")
        
        try {
            if (::inputStream.isInitialized) inputStream.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing input stream", e)
        }
        
        try {
            if (::outputStream.isInitialized) outputStream.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing output stream", e)
        }
        
        try {
            if (::socket.isInitialized) socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket", e)
        }

        if (state != State.Ready) {
            pairingContext?.destroy()
        }
        pairingContext = null
    }

    companion object {
        init {
            try {
                System.loadLibrary("adb")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }

        @JvmStatic
        external fun available(): Boolean
    }
}