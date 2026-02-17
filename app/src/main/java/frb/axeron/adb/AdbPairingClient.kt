package frb.axeron.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairClient"

private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

private const val kExportedKeyLabel = "adb-label\u0000"
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
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
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
    private lateinit var pairingContext: PairingContext
    private var state: State = State.Ready

    /**
     * Memulai proses pairing ADB wireless
     * @return true jika pairing berhasil, false jika gagal
     */
    fun start(): Boolean {
        return try {
            setupTlsConnection()
            
            state = State.ExchangingMsgs
            if (!doExchangeMsgs()) {
                state = State.Stopped
                return false
            }

            state = State.ExchangingPeerInfo
            if (!doExchangePeerInfo()) {
                state = State.Stopped
                return false
            }

            state = State.Stopped
            Log.i(TAG, "Pairing completed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed", e)
            state = State.Stopped
            false
        }
    }

    /**
     * Setup koneksi TLS ke service pairing
     */
    private fun setupTlsConnection() {
        Log.d(TAG, "Connecting to $host:$port")
        
        socket = Socket(host, port)
        socket.tcpNoDelay = true

        val sslContext = key.sslContext
        val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        
        Log.d(TAG, "Starting TLS handshake...")
        sslSocket.startHandshake()
        Log.d(TAG, "TLS handshake succeeded")

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        // Export keying material dari TLS session
        val pairCodeBytes = pairCode.toByteArray(Charsets.UTF_8)
        val keyMaterial = Conscrypt.exportKeyingMaterial(
            sslSocket,
            kExportedKeyLabel,
            null,
            kExportedKeySize
        )

        // Gabungkan pairing code dengan key material
        val passwordBytes = ByteArray(pairCode.length + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        Log.d(TAG, "Creating pairing context...")
        val ctx = PairingContext.create(passwordBytes)
        checkNotNull(ctx) { "Unable to create PairingContext" }
        pairingContext = ctx
        
        Log.d(TAG, "Setup completed, ready for SPAKE2 exchange")
    }

    /**
     * Buat header packet pairing
     */
    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    /**
     * Baca header dari input stream
     */
    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        inputStream.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    /**
     * Tulis header dan payload ke output stream
     */
    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        outputStream.write(buffer.array())
        outputStream.write(payload)
        outputStream.flush()
        Log.d(TAG, "write payload, size=${payload.size}")
    }

    /**
     * Lakukan pertukaran SPAKE2 messages
     */
    private fun doExchangeMsgs(): Boolean {
        Log.d(TAG, "Starting SPAKE2 message exchange")
        
        val msg = pairingContext.msg
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

        return pairingContext.initCipher(theirMessage)
    }

    /**
     * Lakukan pertukaran informasi peer (public key)
     */
    private fun doExchangePeerInfo(): Boolean {
        Log.d(TAG, "Starting peer info exchange")
        
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val encrypted = pairingContext.encrypt(buf.array()) ?: run {
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

        val decrypted = pairingContext.decrypt(theirMessage) ?: run {
            Log.e(TAG, "Failed to decrypt peer info - invalid pairing code?")
            throw AdbInvalidPairingCodeException()
        }
        
        if (decrypted.size != kMaxPeerInfoSize) {
            Log.e(TAG, "Invalid peer info size: ${decrypted.size}, expected: $kMaxPeerInfoSize")
            return false
        }

        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        Log.d(TAG, "Received peer info: $theirPeerInfo")
        
        // Di sini bisa ditambahkan validasi public key peer jika diperlukan
        
        return true
    }

    /**
     * Tutup semua resource
     */
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

        if (state != State.Ready && ::pairingContext.isInitialized) {
            pairingContext.destroy()
        }
    }

    companion object {
        init {
            try {
                System.loadLibrary("adb")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
        }

        /**
         * Cek apakah pairing tersedia (native library bisa diload)
         */
        @JvmStatic
        external fun available(): Boolean
    }
}
