package frb.axeron.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL = "adb_pairing"
        private const val NOTIFICATION_ID = 1122
        private const val REPLY_ACTION = "reply"
        private const val REMOTE_INPUT_RESULT_KEY = "pairing_code"
        private const val HOST_KEY = "pairing_host"
        private const val PORT_KEY = "pairing_port"
    }

    private lateinit var notificationManager: NotificationManager
    private var adbMdns: AdbMdns? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "start" -> startSearching()
            REPLY_ACTION -> handleReply(intent)
            "stop" -> stopSelf()
        }
        return START_STICKY
    }

    private fun startSearching() {
        // Memunculkan notifikasi pencarian awal
        startForeground(NOTIFICATION_ID, buildSearchingNotification())

        // Memanggil AdbMdns, jika port ditemukan maka notifikasi diubah untuk input PIN
        adbMdns = AdbMdns(this, "_adb-tls-pairing._tcp", Observer { port ->
            val host = "127.0.0.1" // Default localhost untuk perangkat yang sama
            notificationManager.notify(NOTIFICATION_ID, buildInputNotification(host, port))
        })
        adbMdns?.start()
    }

    private fun handleReply(intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val pairingCode = remoteInput?.getCharSequence(REMOTE_INPUT_RESULT_KEY)?.toString()
        val host = intent.getStringExtra(HOST_KEY) ?: "127.0.0.1"
        val port = intent.getIntExtra(PORT_KEY, -1)

        if (pairingCode != null && port != -1) {
            // Ubah notifikasi menjadi sedang memproses
            notificationManager.notify(NOTIFICATION_ID, buildWorkingNotification())

            // Eksekusi proses Pairing libadb.so di Background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val adbKey = AdbKey(getSharedPreferences("adb_prefs", Context.MODE_PRIVATE))
                    val client = AdbPairingClient(host, port, pairingCode, adbKey)
                    
                    val success = client.start()
                    client.close()

                    if (success) {
                        // Simpan port yang berhasil dipairing untuk digunakan oleh WebView / MainActivity nanti
                        getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
                            .edit().putInt("paired_port", port).apply()

                        notificationManager.notify(NOTIFICATION_ID, buildSuccessNotification())
                        stopSelf()
                    } else {
                        notificationManager.notify(NOTIFICATION_ID, buildErrorNotification("Kode Salah atau Kadaluarsa!"))
                    }
                } catch (e: Exception) {
                    notificationManager.notify(NOTIFICATION_ID, buildErrorNotification(e.message ?: "Gagal Pairing"))
                }
            }
        }
    }

    // --- PEMBUATAN UI NOTIFIKASI ---

    private fun buildSearchingNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mencari layanan Pairing ADB...")
            .setContentText("Nyalakan Wireless Debugging di Opsi Pengembang.")
            .build()
    }

    private fun buildInputNotification(host: String, port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY)
            .setLabel("Masukkan 6 digit kode pairing")
            .build()

        val replyIntent = Intent(this, AdbPairingService::class.java).apply {
            action = REPLY_ACTION
            putExtra(HOST_KEY, host)
            putExtra(PORT_KEY, port)
        }

        val pendingIntent = PendingIntent.getService(
            this, 0, replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val action = Notification.Action.Builder(
            android.R.drawable.ic_menu_send, "Masukkan Kode", pendingIntent
        ).addRemoteInput(remoteInput).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pairing ADB Ditemukan")
            .setContentText("Ketuk 'Masukkan Kode' lalu ketik 6 digit angka dari pengaturan.")
            .addAction(action)
            .build()
    }

    private fun buildWorkingNotification() = Notification.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("Memproses otentikasi...")
        .build()

    private fun buildSuccessNotification() = Notification.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(android.R.drawable.checkbox_on_background)
        .setContentTitle("Pairing Berhasil!")
        .setContentText("KyrooS siap digunakan.")
        .setAutoCancel(true)
        .build()

    private fun buildErrorNotification(msg: String) = Notification.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_delete)
        .setContentTitle("Gagal Pairing")
        .setContentText(msg)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "ADB Pairing Service",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adbMdns?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
