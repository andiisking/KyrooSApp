package frb.axeron.adb

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.ConnectException

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL = "adb_pairing"
        private const val TAG = "KyroosPairingService"
        private const val NOTIFICATION_ID = 1
        private const val REPLY_REQUEST_CODE = 1
        private const val STOP_REQUEST_CODE = 2
        private const val START_ACTION = "start"
        private const val STOP_ACTION = "stop"
        private const val REPLY_ACTION = "reply"
        private const val REMOTE_INPUT_RESULT_KEY = "pairing_code"
        private const val PORT_KEY = "pairing_port"

        fun startIntent(context: Context): Intent = Intent(context, AdbPairingService::class.java).setAction(START_ACTION)
        fun stopIntent(context: Context): Intent = Intent(context, AdbPairingService::class.java).setAction(STOP_ACTION)
    }

    private var adbMdns: AdbMdns? = null
    private var started = false

    private val observerPairing = Observer<Int> { port ->
        if (port != null && port > 0) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, createInputNotification(port))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(NOTIFICATION_CHANNEL, "KyrooS ADB Pairing", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            START_ACTION -> onStart()
            REPLY_ACTION -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val code = results?.getCharSequence(REMOTE_INPUT_RESULT_KEY)?.toString()
                val port = intent.getIntExtra(PORT_KEY, -1)
                if (code != null && port != -1) {
                    onInput(code, port)
                } else onStart()
            }
            STOP_ACTION -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> return START_NOT_STICKY
        }

        notification?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, it)
            }
        }
        return START_STICKY
    }

    private fun onStart(): Notification {
        if (!started) {
            started = true
            adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observerPairing).apply { start() }
        }
        return searchingNotification
    }

    private fun onInput(code: String, port: Int) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val adbKey = AdbKey(this@AdbPairingService, "kyroos_device")
            // Sesuaikan dengan constructor: AdbPairingClient(host, port, code, key)
            val pairingClient = AdbPairingClient("127.0.0.1", port, code, adbKey)
            
            if (pairingClient.start()) {
                handleResult(true, null)
            } else {
                handleResult(false, Exception("Pairing failed"))
            }
        } catch (e: Exception) {
            handleResult(false, e)
        }
    }
}


    private fun handleResult(success: Boolean, exception: Throwable?) {
        val nm = getSystemService(NotificationManager::class.java)
        val title = if (success) "KyrooS Berhasil Terhubung! ✅" else "Pairing Gagal ❌"
        val text = if (success) "ADB Wireless siap digunakan." else exception?.message

        val finalNotif = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, finalNotif)
        if (success) {
            adbMdns?.stop()
            stopSelf()
        }
    }

    // --- Bagian UI Notifikasi ---

    private fun createInputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY).setLabel("Masukkan Kode 6 Digit").build()
        val replyIntent = Intent(this, AdbPairingService::class.java).apply {
            action = REPLY_ACTION
            putExtra(PORT_KEY, port)
        }
        val pi = PendingIntent.getService(this, 0, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = Notification.Action.Builder(null, "INPUT KODE", pi).addRemoteInput(remoteInput).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Layanan Pairing Ditemukan")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    private val searchingNotification: Notification
        get() = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Mencari Wireless Debugging...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    private val workingNotification: Notification
        get() = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Sedang Menghubungkan...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        adbMdns?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
