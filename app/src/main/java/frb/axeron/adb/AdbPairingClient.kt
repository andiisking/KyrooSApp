package frb.axeron.adb

import org.conscrypt.Conscrypt
import java.security.Security

class AdbPairingClient(private val adbKey: AdbKey) {
    init {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    fun pair(host: String, port: Int, pairingCode: String): Boolean {
        return try {
            val adbClient = AdbClient(adbKey, host, port)
            val result = adbClient.doPair(pairingCode)
            
            // Verifikasi hasil dari library native
            result.contains("success", ignoreCase = true) || result.isEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
