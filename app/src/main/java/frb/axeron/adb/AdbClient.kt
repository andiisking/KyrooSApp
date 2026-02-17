package frb.axeron.adb

class AdbClient(private val adbKey: AdbKey, private val host: String, private val port: Int) {
    companion object {
        init { System.loadLibrary("adb") }
    }

    external fun shell(command: String, port: Int, pub: String, priv: String): String

    fun execute(command: String): String {
        return try {
            shell(command, port, adbKey.getPublicKeyString(), "kyroos_priv")
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
