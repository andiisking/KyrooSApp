package frb.axeron.adb

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

class AdbClient(private val key: AdbKey, private val port: Int, private val host: String = "127.0.0.1") : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream
    private var useTls = false
    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream
    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() {
        val socket = Socket()
        val address = InetSocketAddress(host, port)
        socket.connect(address, 5000)
        socket.tcpNoDelay = true
        this.socket = socket
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "host::")

        var message = read()
        if (message.command == AdbProtocol.A_STLS) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                error("Connect to adb with TLS is not supported before Android 10")
            }
            write(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == AdbProtocol.A_AUTH) {
            if (message.arg0 != AdbProtocol.ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != AdbProtocol.A_CNXN) {
                write(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != AdbProtocol.A_CNXN) error("not A_CNXN")
    }

    // ========== METHOD GAYA AXERON (dengan listener) ==========
    fun shellCommand(cmd: String, onData: (ByteArray) -> Unit) {
        command("shell:$cmd", onData)
    }

    fun command(cmd: String, onData: (ByteArray) -> Unit) {
        val localId = 1
        write(AdbProtocol.A_OPEN, localId, 0, cmd)

        var message = read()
        when (message.command) {
            AdbProtocol.A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    when (message.command) {
                        AdbProtocol.A_WRTE -> {
                            if (message.data_length > 0) {
                                onData(message.data!!)
                            }
                            write(AdbProtocol.A_OKAY, localId, remoteId)
                        }
                        AdbProtocol.A_CLSE -> {
                            write(AdbProtocol.A_CLSE, localId, remoteId)
                            break
                        }
                        else -> error("Unexpected command: ${message.command}")
                    }
                }
            }
            AdbProtocol.A_CLSE -> {
                val remoteId = message.arg0
                write(AdbProtocol.A_CLSE, localId, remoteId)
            }
            else -> error("Unexpected response: ${message.command}")
        }
    }

    // ========== METHOD STREAM (untuk kompatibilitas dengan kode lama) ==========
    fun open(destination: String): AdbStream {
        val localId = generateLocalId()
        write(AdbProtocol.A_OPEN, localId, 0, destination)
        val message = read()
        if (message.command != AdbProtocol.A_OKAY) {
            throw AdbException("Failed to open stream: ${message.command}")
        }
        val remoteId = message.arg0
        return AdbStream(this, localId, remoteId)
    }

    internal fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        write(AdbMessage(command, arg0, arg1, data))
    }

    internal fun write(command: Int, arg0: Int, arg1: Int, data: String) {
        write(AdbMessage(command, arg0, arg1, data))
    }

    internal fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    internal fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        inputStream.readFully(buffer.array(), 0, 24)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data = if (dataLength > 0) {
            ByteArray(dataLength).also { inputStream.readFully(it, 0, dataLength) }
        } else null
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    private fun generateLocalId(): Int {
        return (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    }

    override fun close() {
        runCatching { plainInputStream.close() }
        runCatching { plainOutputStream.close() }
        runCatching { socket.close() }
        if (useTls) {
            runCatching { tlsInputStream.close() }
            runCatching { tlsOutputStream.close() }
            runCatching { tlsSocket.close() }
        }
    }
}

// ========== AdbStream (tetap ada untuk kompatibilitas) ==========
class AdbStream(
    private val client: AdbClient,
    private val localId: Int,
    private val remoteId: Int
) : Closeable {
    private var closed = false
    private val chunks = mutableListOf<ByteArray>()

    fun readAll(): ByteArray {
        Log.d(TAG, "readAll started")
        while (true) {
            val message = client.read()
            when (message.command) {
                AdbProtocol.A_WRTE -> {
                    if (message.arg0 == localId) {
                        client.write(AdbProtocol.A_OKAY, localId, remoteId)
                        if (message.data != null) {
                            chunks.add(message.data)
                            Log.d(TAG, "Chunk size: ${message.data.size}")
                        }
                    }
                }
                AdbProtocol.A_CLSE -> {
                    closed = true
                    break
                }
                else -> {
                    Log.w(TAG, "Unexpected command in readAll: ${message.command}")
                }
            }
        }
        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(result, pos)
            pos += chunk.size
        }
        Log.d(TAG, "readAll complete: $total bytes")
        return result
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { client.write(AdbProtocol.A_CLSE, localId, remoteId) }
        }
    }
}