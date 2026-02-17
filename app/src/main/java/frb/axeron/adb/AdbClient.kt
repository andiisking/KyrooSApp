package frb.axeron.adb

class AdbClient(private val adbKey: AdbKey, private val host: String, private val port: Int) {
    companion object {
        init {
            try {
                System.loadLibrary("adb")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Fungsi native untuk eksekusi shell
    external fun shell(command: String, port: Int, pub: String, priv: String): String

    // Fungsi native untuk proses PAIRING
    external fun pair(code: String, port: Int, pub: String, priv: String): String

    fun execute(command: String): String {
        return try {
            shell(command, port, adbKey.getPublicKeyString(), "kyroos_priv")
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun doPair(code: String): String {
        return try {
            pair(code, port, adbKey.getPublicKeyString(), "kyroos_priv")
        } catch (e: Exception) {
            "Pairing Error: ${e.message}"
        }
    }
}
