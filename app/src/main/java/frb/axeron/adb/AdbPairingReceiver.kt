package frb.axeron.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        // Mengambil port yang dideteksi oleh AdbMdns via Service
        val port = intent.getIntExtra("detected_port", -1)

        if (remoteInput != null && port != -1) {
            val pairingCode = remoteInput.getCharSequence("extra_pairing_code").toString()
            
            // Simpan port ke SharedPreferences untuk digunakan AdbClient nanti
            val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("paired_port", port).apply()

            // Eksekusi Pairing yang sebenarnya
            val adbKey = AdbKey(prefs)
            val pairingClient = AdbPairingClient(adbKey)
            
            val isSuccess = pairingClient.pair("127.0.0.1", port, pairingCode)

            if (isSuccess) {
                // Beritahu service bahwa pairing sukses untuk menampilkan notifikasi centang hijau
                val successIntent = Intent(context, AdbPairingService::class.java).apply {
                    action = "pairing_success"
                }
                context.startService(successIntent)
                Toast.makeText(context, "KyrooS: Pairing Berhasil di Port $port!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Pairing Gagal! Cek kodenya lagi.", Toast.LENGTH_SHORT).show()
            }
        } else if (port == -1) {
            Toast.makeText(context, "Tunggu sebentar, port belum terdeteksi...", Toast.LENGTH_SHORT).show()
        }
    }
}
