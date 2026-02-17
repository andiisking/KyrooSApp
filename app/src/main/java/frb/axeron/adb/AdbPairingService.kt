package frb.axeron.adb

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer

class AdbPairingService : Service() {
    private val CHANNEL_ID = "kyroos_adb"
    private var adbMdns: AdbMdns? = null
    private var currentPort = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            "start_pairing_scan" -> {
                startForeground(1001, createNotification("Mencari Layanan Pairing..."))
                setupMdns(AdbMdns.TLS_PAIRING)
            }
            "start_connect_scan" -> {
                // Berhenti scan pairing, ganti ke scan koneksi utama
                setupMdns(AdbMdns.TLS_CONNECT)
                updateNotification("Pairing Sukses! Menghubungkan ke ADB...")
            }
            "connection_success" -> {
                showFinalNotification("KyrooS Terhubung! ✅", "ADB Wireless Aktif & Siap Digunakan.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun setupMdns(type: String) {
        adbMdns?.stop()
        adbMdns = AdbMdns(this, type, Observer { port ->
            if (port != null && port != -1) {
                currentPort = port
                if (type == AdbMdns.TLS_PAIRING) {
                    updateNotification("Port Pairing Ditemukan: $port")
                } else {
                    // AUTO-CONNECT: Jika port koneksi ketemu, langsung tembak!
                    val intent = Intent(this, AdbPairingReceiver::class.java).apply {
                        action = "trigger_connect"
                        putExtra("port", currentPort)
                    }
                    sendBroadcast(intent)
                }
            }
        })
        adbMdns?.start()
    }

    private fun updateNotification(msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, createNotification(msg))
    }

    private fun createNotification(msg: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "KyrooS ADB", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }

        val remoteInput = androidx.core.app.RemoteInput.Builder("pairing_code").setLabel("Kode Pairing").build()
        val intent = Intent(this, AdbPairingReceiver::class.java).putExtra("port", currentPort)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KyrooS ADB")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)

        // Hanya tampilkan tombol input jika sedang dalam mode pairing
        if (msg.contains("Pairing") || msg.contains("Mencari")) {
            builder.addAction(NotificationCompat.Action.Builder(0, "INPUT KODE", pi).addRemoteInput(remoteInput).build())
        }

        return builder.build()
    }

    private fun showFinalNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(1002, notif)
    }

    override fun onDestroy() {
        adbMdns?.stop()
        super.onDestroy()
    }
}
