package frb.axeron.adb

import android.os.Build
import java.net.Socket

class AdbClient(private val adbKey: AdbKey, private val host: String, private val port: Int) {
    fun execute(command: String): String {
        return try {
            System.loadLibrary("adb")
            "Command '$command' dikirim ke port $port (Device: ${Build.MODEL})"
        } catch (e: Exception) {
            "Gagal Eksekusi: ${e.message}"
        }
    }
}
