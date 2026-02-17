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
            val connectPort = intent.getIntExtra("port", -1)
            if (connectPort != -1) {
                prefs.edit().putInt("paired_port", connectPort).apply()
                val serviceIntent = Intent(context, AdbPairingService::class.java).apply { action = "connection_success" }
                context.startService(serviceIntent)
            }
            return
        }

        val results = RemoteInput.getResultsFromIntent(intent)
        val port = intent.getIntExtra("port", -1)

        if (results != null && port != -1) {
            val code = results.getCharSequence("pairing_code").toString()
            if (AdbPairingClient(AdbKey(prefs)).pair("127.0.0.1", port, code)) {
                val serviceIntent = Intent(context, AdbPairingService::class.java).apply { action = "start_connect_scan" }
                context.startService(serviceIntent)
            } else {
                Toast.makeText(context, "Pairing Gagal!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
