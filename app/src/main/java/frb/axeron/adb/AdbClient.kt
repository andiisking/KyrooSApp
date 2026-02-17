package frb.axeron.adb

import android.util.Log
import frb.axeron.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import frb.axeron.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import frb.axeron.adb.AdbProtocol.ADB_AUTH_TOKEN
import frb.axeron.adb.AdbProtocol.A_AUTH
import frb.axeron.adb.AdbProtocol.A_CLSE
import frb.axeron.adb.AdbProtocol.A_CNXN
import frb.axeron.adb.AdbProtocol.A_MAXDATA
import frb.axeron.adb.AdbProtocol.A_OKAY
import frb.axeron.adb.AdbProtocol.A_OPEN
import frb.axeron.adb.AdbProtocol.A_STLS
import frb.axeron.adb.AdbProtocol.A_STLS_VERSION
import frb.axeron.adb.AdbProtocol.A_VERSION
import frb.axeron.adb.AdbProtocol.A_WRTE
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
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                error("Connect to adb with TLS is not supported before Android 10")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == A_AUTH) {
            if (message.command != A_AUTH && message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    /**
     * Buka stream ke destination (service ADB)
     */
    fun open(destination: String): AdbStream {
        val localId = generateLocalId()
        write(A_OPEN, localId, 0, destination)
        
        val message = read()
        if (message.command != A_OKAY) {
            throw AdbException("Failed to open stream: ${message.command}")
        }
        val remoteId = message.arg0
        
        return AdbStream(this, localId, remoteId)
    }

    /**
     * Jalankan shell command
     */
    fun shellCommand(cmd: String, listener: ((ByteArray) -> Unit)? = null) {
        command("shell:$cmd", listener)
    }

    /**
     * Jalankan command ADB
     */
    fun command(cmd: String, listener: ((ByteArray) -> Unit)? = null) {
        val localId = 1
        write(A_OPEN, localId, 0, cmd)

        var message = read()
        when (message.command) {
            A_OKAY -> {
                val remoteId = message.arg0
                while (true) {
                    message = read()
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("Unexpected command: ${message.command}")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("Unexpected response: ${message.command}")
            }
        }
    }

    /**
     * Write message ke ADB server
     */
    internal fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        write(AdbMessage(command, arg0, arg1, data))
    }

    internal fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    /**
     * Read message dari ADB server
     */
    internal fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        
        val data: ByteArray? = if (dataLength > 0) {
            ByteArray(dataLength).also { inputStream.readFully(it, 0, dataLength) }
        } else null
        
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    private fun generateLocalId(): Int {
        return (1..Int.MAX_VALUE).random()
    }

    override fun close() {
        try { plainInputStream.close() } catch (_: Throwable) {}
        try { plainOutputStream.close() } catch (_: Throwable) {}
        try { socket.close() } catch (_: Exception) {}

        if (useTls) {
            try { tlsInputStream.close() } catch (_: Throwable) {}
            try { tlsOutputStream.close() } catch (_: Throwable) {}
            try { tlsSocket.close() } catch (_: Exception) {}
        }
    }
}

/**
 * Stream ADB untuk komunikasi two-way
 */
class AdbStream(
    private val client: AdbClient,
    private val localId: Int,
    private val remoteId: Int
) : Closeable {
    
    private var closed = false
    private val readBuffer = mutableListOf<ByteArray>()
    private var writeBuffer = ByteArray(0)

    /**
     * Baca data dari stream (blocking)
     */
    fun read(): ByteArray? {
        if (closed) return null
        
        while (true) {
            val message = client.read()
            when (message.command) {
                AdbProtocol.A_WRTE -> {
                    if (message.arg0 == localId) {
                        client.write(AdbProtocol.A_OKAY, localId, remoteId)
                        return message.data
                    }
                }
                AdbProtocol.A_CLSE -> {
                    close()
                    return null
                }
            }
        }
    }

    /**
     * Tulis data ke stream
     */
    fun write(data: ByteArray) {
        if (closed) throw IllegalStateException("Stream closed")
        
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(offset + AdbProtocol.A_MAXDATA, data.size))
            client.write(AdbProtocol.A_WRTE, localId, remoteId, chunk)
            
            // Tunggu OKAY
            val response = client.read()
            if (response.command != AdbProtocol.A_OKAY) {
                throw AdbException("Write failed: ${response.command}")
            }
            offset += chunk.size
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            try {
                client.write(AdbProtocol.A_CLSE, localId, remoteId)
            } catch (_: Exception) {}
        }
    }
}
