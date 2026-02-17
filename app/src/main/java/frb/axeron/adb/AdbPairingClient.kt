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
            
            // Cek apakah hasil dari native mengandung kata gagal atau tidak
            // Biasanya jika sukses, .so akan mengembalikan string kosong atau 'success'
            !result.contains("fail", ignoreCase = true) && !result.contains("error", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}
