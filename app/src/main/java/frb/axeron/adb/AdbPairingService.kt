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
import android.provider.Settings
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
        const val PAIRING_SUCCESS_ACTION = "frb.axeron.adb.PAIRING_SUCCESS"
        const val PAIRING_FAILED_ACTION = "frb.axeron.adb.PAIRING_FAILED"
        const val EXTRA_PORT = "port"
        const val EXTRA_ERROR = "error"

        private const val TAG = "AdbPairingService"
        private const val NOTIFICATION_ID = 1
        private const val REPLY_REQUEST_CODE = 1
        private const val STOP_REQUEST_CODE = 2
        private const val RETRY_REQUEST_CODE = 3
        private const val START_ACTION = "start"
        private const val STOP_ACTION = "stop"
        private const val REPLY_ACTION = "reply"
        private const val REMOTE_INPUT_RESULT_KEY = "pairing_code"
        private const val PORT_KEY = "pairing_port"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(START_ACTION)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(STOP_ACTION)
        }

        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).apply {
                action = REPLY_ACTION
                putExtra(PORT_KEY, port)
            }
        }
    }

    private var adbMdns: AdbMdns? = null
    private var started = false
    private var foundPort = -1

    private val observerPairing = Observer<Int> { port ->
        Log.i(TAG, "Pairing service port: $port")
        if (port <= 0) return@Observer
        foundPort = port

        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent
        val notification = createInputNotification(port)

        try {
            // Coba update notifikasi yang sudah ada
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Wireless Debugging Pairing",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            setShowBadge(false)
            description = "Notifikasi untuk pairing ADB wireless"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_ACTION -> {
                onStart()
                startForeground(NOTIFICATION_ID, searchingNotification)
            }
            REPLY_ACTION -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val code = results?.getCharSequence(REMOTE_INPUT_RESULT_KEY)?.toString() ?: ""
                val port = intent.getIntExtra(PORT_KEY, -1)

                if (port != -1 && code.isNotBlank()) {
                    onInput(code, port)
                } else {
                    // Jika ada error, kembali ke mode pencarian
                    onStart()
                    startForeground(NOTIFICATION_ID, searchingNotification)
                }
            }
            STOP_ACTION -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observerPairing).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
        adbMdns = null
    }

    override fun onDestroy() {
        stopSearch()
        super.onDestroy()
    }

    private fun onStart() {
        stopSearch()
        startSearch()
    }

    private fun onInput(code: String, port: Int) {
        // Hentikan pencarian selama proses pairing
        stopSearch()

        // Update notifikasi ke mode working
        startForeground(NOTIFICATION_ID, workingNotification)

        CoroutineScope(Dispatchers.IO).launch {
            val success = try {
                // Buat AdbKey menggunakan PreferenceAdbKeyStore
                val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
                val keyStore = PreferenceAdbKeyStore(prefs)
                val key = AdbKey(keyStore, "kyroos_device")

                val pairingClient = AdbPairingClient("127.0.0.1", port, code, key)
                pairingClient.use { it.start() }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing error", e)
                false
            }

            handleResult(success, port)
        }
    }

    private fun handleResult(success: Boolean, port: Int) {
        // Hentikan foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        val title: String
        val text: String

        if (success) {
            Log.i(TAG, "Pair succeed on port $port")
            title = "Pairing Berhasil! ✅"
            text = "ADB Wireless aktif di port $port"

            // Simpan port ke SharedPreferences
            val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("paired_port", port).apply()

            // Kirim broadcast sukses
            val broadcastIntent = Intent(PAIRING_SUCCESS_ACTION).apply {
                putExtra(EXTRA_PORT, port)
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)

        } else {
            title = "Pairing Gagal ❌"
            text = "Periksa kode pairing dan coba lagi"

            // Kirim broadcast gagal
            val broadcastIntent = Intent(PAIRING_FAILED_ACTION).apply {
                putExtra(EXTRA_ERROR, text)
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)
        }

        // Tampilkan notifikasi hasil
        val nm = getSystemService(NotificationManager::class.java)
        val resultNotification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
            .apply {
                if (!success) {
                    addAction(retryNotificationAction)
                }
            }
            .build()

        nm.notify(NOTIFICATION_ID, resultNotification)

        // Hentikan service
        stopSelf()
    }

    // ========== NOTIFICATION ACTIONS ==========
    private val stopNotificationAction by lazy {
        val pendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Notification.Action.Builder(
            null,
            "Stop searching",
            pendingIntent
        ).build()
    }

    private val retryNotificationAction by lazy {
        val pendingIntent = PendingIntent.getService(
            this,
            RETRY_REQUEST_CODE,
            startIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Notification.Action.Builder(
            null,
            "Coba Lagi",
            pendingIntent
        ).build()
    }

    private fun createInputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY)
            .setLabel("Kode Pairing 6 Digit")
            .build()

        val replyIntent = replyIntent(this, port)
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getForegroundService(
                this,
                REPLY_REQUEST_CODE,
                replyIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this,
                REPLY_REQUEST_CODE,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val replyAction = Notification.Action.Builder(
            null,
            "Masukkan Kode",
            pendingIntent
        ).addRemoteInput(remoteInput).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Layanan Pairing Ditemukan")
            .setContentText("Port: $port - Ketuk untuk memasukkan kode")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(replyAction)
            .addAction(stopNotificationAction)
            .setOngoing(true)
            .build()
    }

    private val searchingNotification by lazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Mencari Wireless Debugging...")
            .setContentText("Pastikan Wireless Debugging aktif di pengaturan developer")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(stopNotificationAction)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    private val workingNotification by lazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Sedang Menghubungkan...")
            .setContentText("Jangan tutup aplikasi")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}