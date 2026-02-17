package frb.axeron.adb

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer

class AdbPairingService : Service() {
    private val CHANNEL_ID = "adb_pairing"
    private var adbMdns: AdbMdns? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(1, buildNotification("Mencari layanan pairing..."))

        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, Observer { port ->
            if (port != null && port != -1) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(1, buildNotification("Layanan ditemukan di port $port", port))
            }
        })
        adbMdns?.start()

        return START_STICKY
    }

    private fun buildNotification(msg: String, port: Int? = null): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KyrooS ADB Scanner")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)

        if (port != null) {
            val remoteInput = androidx.core.app.RemoteInput.Builder("pairing_code").setLabel("Kode 6 Digit").build()
            val intent = Intent(this, AdbPairingReceiver::class.java).apply {
                putExtra("pairing_port", port)
            }
            val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val action = NotificationCompat.Action.Builder(0, "INPUT KODE", pi).addRemoteInput(remoteInput).build()
            builder.addAction(action)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "ADB Pairing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    override fun onDestroy() {
        adbMdns?.stop()
        super.onDestroy()
    }
}
