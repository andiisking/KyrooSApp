package frb.axeron.adb

import org.conscrypt.Conscrypt
import java.security.Security
import javax.net.ssl.SSLContext

class AdbPairingClient(private val adbKey: AdbKey) {
    init {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    fun pair(host: String, port: Int, pairingCode: String): Boolean {
        return try {
            val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
            sslContext.init(null, null, null)
            true 
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
