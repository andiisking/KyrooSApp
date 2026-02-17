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
            val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            
            // Menggunakan PairingClient asli Axeron (Membutuhkan libadb.so)
            val pairingClient = AdbPairingClient(context, host, port)
            
            try {
                // Proses Pairing Native Spake2
                val success = pairingClient.pair(code)
                if (success) {
                    prefs.edit().putInt("paired_port", port).apply()
                    Toast.makeText(context, "Pairing Berhasil!", Toast.LENGTH_SHORT).show()
                    
                    // Beritahu service agar menghentikan scan
                    val serviceIntent = Intent(context, AdbPairingService::class.java).apply { action = "stop" }
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                pairingClient.close()
            }
        }
    }
}
