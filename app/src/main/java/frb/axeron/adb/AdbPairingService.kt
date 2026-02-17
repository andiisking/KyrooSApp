package frb.axeron.adb

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer

class AdbPairingService : Service() {
    private val CHANNEL_ID = "kyroos_pairing_channel"
    private val NOTIFICATION_ID = 1001
    private var adbMdns: AdbMdns? = null
    private var currentPort = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Menggunakan AdbMdns untuk mencari port pairing (_adb-tls-pairing)
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, Observer { port ->
            if (port != null && port != -1) {
                currentPort = port
                updateNotification("Layanan Ditemukan di Port: $port. Silakan Input Kode.")
            } else {
                currentPort = -1
                updateNotification("Mencari Layanan Wireless Debugging...")
            }
        })
        adbMdns?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "pairing_success") {
            showSuccessNotification()
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Memulai Pemindaian ADB..."))
        }
        return START_STICKY
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        val remoteInput = androidx.core.app.RemoteInput.Builder("extra_pairing_code")
            .setLabel("Masukkan 6 Digit Kode Pairing")
            .build()

        // Kirim port yang terdeteksi ke Receiver
        val intent = Intent(this, AdbPairingReceiver::class.java).apply {
            putExtra("detected_port", currentPort)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit, "INPUT KODE", pendingIntent
        ).addRemoteInput(remoteInput).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "KyrooS ADB", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KyrooS ADB Scanner")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
    }

    private fun showSuccessNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KyrooS ADB Berhasil!")
            .setContentText("Status: TERHUBUNG ✅")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, notification)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        adbMdns?.stop()
        super.onDestroy()
    }
}
