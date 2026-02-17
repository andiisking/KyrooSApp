package frb.axeron.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        
        if (intent.action == "trigger_connect") {
            // LOGIKA AUTO-CONNECT
            val connectPort = intent.getIntExtra("port", -1)
            if (connectPort != -1) {
                // Simpan port koneksi utama
                prefs.edit().putInt("paired_port", connectPort).apply()
                
                // Beritahu service bahwa kita sudah sukses terhubung
                val serviceIntent = Intent(context, AdbPairingService::class.java).apply {
                    action = "connection_success"
                }
                context.startService(serviceIntent)
            }
            return
        }

        // LOGIKA PAIRING (INPUT KODE)
        val results = RemoteInput.getResultsFromIntent(intent)
        val port = intent.getIntExtra("port", -1)

        if (results != null && port != -1) {
            val code = results.getCharSequence("pairing_code").toString()
            val isPairingOk = AdbPairingClient(AdbKey(prefs)).pair("127.0.0.1", port, code)
            
            if (isPairingOk) {
                // Setelah pairing OK, suruh service cari port KONEKSI (_adb-tls-connect)
                val serviceIntent = Intent(context, AdbPairingService::class.java).apply {
                    action = "start_connect_scan"
                }
                context.startService(serviceIntent)
            } else {
                Toast.makeText(context, "Pairing Gagal! Cek Kode.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
