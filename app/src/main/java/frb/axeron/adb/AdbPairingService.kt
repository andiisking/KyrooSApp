package frb.axeron.adb

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

class AdbPairingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "start") {
            val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            val adbKey = AdbKey(prefs)
            val pairingClient = AdbPairingClient(adbKey)
            // Jalankan logika pairing di thread terpisah
        }
        return START_STICKY
    }
}
