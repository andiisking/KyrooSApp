package frb.axeron.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent)
        val port = intent.getIntExtra("pairing_port", -1)
        val host = intent.getStringExtra("pairing_host") ?: "127.0.0.1"

        if (results != null && port != -1) {
            val code = results.getCharSequence("pairing_code").toString()
            
            // Constructor asli Axeron: AdbPairingClient(Context, Host, Port)
            val pairingClient = AdbPairingClient(context, host, port)
            
            try {
                // Fungsi pair asli Axeron hanya menerima 1 parameter: pairingCode
                if (pairingClient.pair(code)) {
                    val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putInt("paired_port", port).apply()
                    
                    Toast.makeText(context, "KyrooS Berhasil Terhubung!", Toast.LENGTH_SHORT).show()
                    
                    // Berhentikan scan pairing
                    context.startService(Intent(context, AdbPairingService::class.java).apply {
                        action = "stop"
                    })
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // AdbPairingClient Axeron mengimplementasikan Closeable
                try { pairingClient.close() } catch (e: Exception) {}
            }
        }
    }
}
