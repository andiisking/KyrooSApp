package frb.axeron.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            val code = remoteInput.getCharSequence("extra_pairing_code").toString()
            Toast.makeText(context, "Pairing Kode: $code", Toast.LENGTH_SHORT).show()
            
            // Logic pairing dipicu di sini
            val serviceIntent = Intent(context, AdbPairingService::class.java).apply {
                putExtra("status", "Sedang Pairing: $code")
            }
            context.startService(serviceIntent)
        }
    }
}
