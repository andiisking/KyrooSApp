package frb.axeron.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent)
        val port = intent.getIntExtra("port", -1)
        // Gunakan IP dinamis dari sistem jika tersedia, atau localhost sebagai cadangan
        val host = intent.getStringExtra("host") ?: "127.0.0.1"

        if (results != null && port != -1) {
            val code = results.getCharSequence("pairing_code").toString()
            val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            
            val isSuccess = AdbPairingClient(AdbKey(prefs)).pair(host, port, code)
            
            if (isSuccess) {
                prefs.edit().putInt("paired_port", port).apply()
                // Hanya kirim sinyal sukses jika pairing benar-benar terverifikasi
                val successIntent = Intent(context, AdbPairingService::class.java).apply {
                    action = "pairing_success"
                }
                context.startService(successIntent)
            } else {
                // Beritahu user jika kode salah atau jabat tangan TLS gagal
                Toast.makeText(context, "Pairing Gagal: Periksa Kode atau Koneksi!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
