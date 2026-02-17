package frb.axeron.adb

import android.os.Build

class AdbClient(private val adbKey: AdbKey, private val host: String, private val port: Int) {
    companion object {
        init {
            try {
                System.loadLibrary("adb")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    external fun shell(command: String, port: Int, publicKey: String, privateKey: String): String

    fun execute(command: String): String {
        return try {
            shell(command, port, adbKey.getPublicKeyString(), "kyroos_key")
        } catch (e: Exception) {
            "Gagal panggil native: ${e.message}"
        }
    }
}
