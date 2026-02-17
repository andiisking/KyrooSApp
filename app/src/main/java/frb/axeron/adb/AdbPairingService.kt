package frb.axeron.adb

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput as androidxRemoteInput

class AdbPairingService : Service() {
    private val CHANNEL_ID = "kyroos_pairing_channel"
    private val NOTIFICATION_ID = 1001

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra("status") ?: "Ready to Pair"
        // Langsung panggil startForeground agar tidak FC
        startForeground(NOTIFICATION_ID, createNotification(status))
        return START_STICKY
    }

    private fun createNotification(content: String): Notification {
        val remoteInput = androidxRemoteInput.Builder("extra_pairing_code")
            .setLabel("Masukkan 6 digit kode pairing...")
            .build()

        val intent = Intent(this, AdbPairingReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit, "INPUT KODE", pendingIntent
        ).addRemoteInput(remoteInput).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "KyrooS ADB", NotificationManager.IMPORTANCE_LOW))
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KyrooS ADB Pairer")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
    }
}
