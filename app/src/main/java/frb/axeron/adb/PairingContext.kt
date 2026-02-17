package frb.axeron.adb

import java.io.Closeable

class PairingContext private constructor(private val nativePtr: Long) : Closeable {

    // Fungsi native yang didefinisikan di adb_pairing.cpp
    private external fun nativeMsg(ptr: Long): ByteArray
    private external fun nativeInitCipher(ptr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(ptr: Long, data: ByteArray): ByteArray?
    private external fun nativeDecrypt(ptr: Long, data: ByteArray): ByteArray?
    private external fun nativeDestroy(ptr: Long)

    val msg: ByteArray get() = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)
    fun encrypt(data: ByteArray) = nativeEncrypt(nativePtr, data)
    fun decrypt(data: ByteArray) = nativeDecrypt(nativePtr, data)

    override fun close() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
        }
    }

    companion object {
        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long

        fun create(password: ByteArray): PairingContext? {
            val ptr = nativeConstructor(true, password)
            return if (ptr != 0L) PairingContext(ptr) else null
        }
    }
}
