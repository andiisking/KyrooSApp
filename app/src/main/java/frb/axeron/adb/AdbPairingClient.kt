package frb.axeron.adb

import android.os.Build

class AdbClient(private val adbKey: AdbKey, private val host: String, private val port: Int) {

    companion object {
        init {
            // Memanggil libadb.so yang kamu taruh di jniLibs
            try {
                System.loadLibrary("adb")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Ini adalah fungsi 'Sakti'. Nama fungsi ini harus cocok dengan 
     * apa yang didefinisikan di dalam libadb.so milik Axeron.
     */
    external fun shell(command: String, port: Int, publicKey: String, privateKey: String): String

    fun execute(command: String): String {
        return try {
            // Kita panggil fungsi native dari .so
            val result = shell(
                command, 
                port, 
                adbKey.getPublicKeyString(), 
                // Kita asumsikan .so butuh key dalam format tertentu
                "kyroos_key" 
            )
            result
        } catch (e: Exception) {
            "Gagal panggil native: ${e.message}"
        }
    }
}
