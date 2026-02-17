package frb.axeron.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.app.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        /**
         * Intent untuk memulai service
         */
        fun startIntent(context: Context): Intent = 
            Intent(context, AdbPairingService::class.java).setAction(START_ACTION)
        
        /**
         * Intent untuk menghentikan service
         */
        fun stopIntent(context: Context): Intent = 
            Intent(context, AdbPairingService::class.java).setAction(STOP_ACTION)
    }

    private var adbMdns: AdbMdns? = null
    private var started = false

    /**
     * Observer untuk menerima port pairing dari mDNS discovery
     */
    private val observerPairing = Observer<Int> { port ->
        if (port != null && port > 0) {
            Log.d(TAG, "Pairing service found on port $port")
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, createInputNotification(port))
        } else {
            Log.d(TAG, "Pairing service lost")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        // Buat notification channel
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "KyrooS ADB Pairing",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            setShowBadge(false)
            description = "Notifikasi untuk pairing ADB wireless"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        val notification: Notification? = when (intent?.action) {
            START_ACTION -> {
                onStartDiscovery()
            }
            
            REPLY_ACTION -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val code = results?.getCharSequence(REMOTE_INPUT_RESULT_KEY)?.toString()
                val port = intent.getIntExtra(PORT_KEY, -1)
                
                if (!code.isNullOrBlank() && port != -1) {
                    // Validasi format kode (6 digit)
                    if (code.matches(Regex("^\\d{6}$"))) {
                        onPairingCodeReceived(code, port)
                        workingNotification
                    } else {
                        showErrorNotification("Kode harus 6 digit angka")
                        createInputNotification(port)
                    }
                } else {
                    showErrorNotification("Kode atau port tidak valid")
                    onStartDiscovery()
                }
            }
            
            STOP_ACTION -> {
                Log.d(TAG, "Stopping service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                return START_NOT_STICKY
            }
        }

        notification?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    it, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, it)
            }
        }
        
        return START_STICKY
    }

    /**
     * Mulai discovery service pairing via mDNS
     */
    private fun onStartDiscovery(): Notification {
        if (!started) {
            started = true
            Log.d(TAG, "Starting mDNS discovery")
            
            adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observerPairing).apply { 
                start() 
            }
        }
        return searchingNotification
    }

    /**
     * Handle ketika kode pairing diterima dari user
     */
    private fun onPairingCodeReceived(code: String, port: Int) {
        Log.d(TAG, "Starting pairing with port=$port")
        
        // Update notifikasi ke "Sedang memproses..."
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, workingNotification)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Buat atau ambil existing key
                val adbKey = AdbKey(this@AdbPairingService, "kyroos_device")
                
                // Buat pairing client
                val pairingClient = AdbPairingClient("127.0.0.1", port, code, adbKey)
                
                // Jalankan pairing
                val success = pairingClient.use { it.start() }
                
                withContext(Dispatchers.Main) {
                    if (success) {
                        handleSuccess()
                    } else {
                        handleFailure(Exception("Pairing gagal, periksa kode dan coba lagi"))
                    }
                }
                
            } catch (e: AdbInvalidPairingCodeException) {
                Log.e(TAG, "Invalid pairing code", e)
                withContext(Dispatchers.Main) {
                    handleFailure(Exception("Kode pairing salah"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing error", e)
                withContext(Dispatchers.Main) {
                    handleFailure(e)
                }
            }
        }
    }

    /**
     * Handle pairing berhasil
     */
    private fun handleSuccess() {
        Log.i(TAG, "Pairing successful")
        
        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("KyrooS Berhasil Terhubung! ✅")
            .setContentText("ADB Wireless siap digunakan. Anda bisa menutup aplikasi.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        
        // Stop discovery dan service
        adbMdns?.stop()
        stopSelf()
    }

    /**
     * Handle pairing gagal
     */
    private fun handleFailure(exception: Throwable) {
        Log.e(TAG, "Pairing failed: ${exception.message}")
        
        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Pairing Gagal ❌")
            .setContentText(exception.message ?: "Terjadi kesalahan tidak diketahui")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        
        // Restart discovery
        started = false
        adbMdns?.stop()
        adbMdns = null
        onStartDiscovery()
    }

    /**
     * Tampilkan error notification sementara
     */
    private fun showErrorNotification(message: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Error")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    /**
     * Buat notifikasi input kode pairing
     */
    private fun createInputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY)
            .setLabel("Masukkan Kode 6 Digit")
            .build()

        val replyIntent = Intent(this, AdbPairingService::class.java).apply {
            action = REPLY_ACTION
            putExtra(PORT_KEY, port)
        }
        
        val pendingIntent = PendingIntent.getService(
            this,
            REPLY_REQUEST_CODE,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        
        val replyAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_send,
            "INPUT KODE",
            pendingIntent
        ).addRemoteInput(remoteInput).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Layanan Pairing Ditemukan")
            .setContentText("Ketuk untuk memasukkan kode 6 digit dari Wireless Debugging")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(replyAction)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    /**
     * Notifikasi saat mencari service
     */
    private val searchingNotification: Notification
        get() = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Mencari Wireless Debugging...")
            .setContentText("Pastikan Wireless Debugging aktif di pengaturan developer")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setProgress(0, 0, true) // Indeterminate progress
            .build()

    /**
     * Notifikasi saat sedang pairing
     */
    private val workingNotification: Notification
        get() = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Sedang Menghubungkan...")
            .setContentText("Memverifikasi kode pairing...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        adbMdns?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
