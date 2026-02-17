package frb.axeron.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL = "adb_pairing"
        private const val TAG = "KyroosPairingService"
        private const val NOTIFICATION_ID = 1
        private const val SUCCESS_NOTIFICATION_ID = 2
        private const val ERROR_NOTIFICATION_ID = 3
        private const val REPLY_REQUEST_CODE = 1
        private const val START_ACTION = "start"
        private const val STOP_ACTION = "stop"
        private const val REPLY_ACTION = "reply"
        private const val REMOTE_INPUT_RESULT_KEY = "pairing_code"
        private const val PORT_KEY = "pairing_port"

        const val PAIRING_SUCCESS_ACTION = "frb.axeron.adb.PAIRING_SUCCESS"
        const val PAIRING_FAILED_ACTION = "frb.axeron.adb.PAIRING_FAILED"
        const val EXTRA_PORT = "port"
        const val EXTRA_ERROR = "error"

        fun startIntent(context: Context): Intent = 
            Intent(context, AdbPairingService::class.java).setAction(START_ACTION)
        
        fun stopIntent(context: Context): Intent = 
            Intent(context, AdbPairingService::class.java).setAction(STOP_ACTION)
    }

    private var pairingMdns: AdbMdns? = null
    private var connectMdns: AdbMdns? = null
    private var isPairingInProgress = false
    private var isServiceStopping = false
    private var currentPairingPort: Int = -1
    private var foundConnectPort: Int = -1

    private val pairingObserver = Observer<Int> { port ->
        if (port > 0 && !isServiceStopping && !isPairingInProgress) {
            Log.d(TAG, "Pairing service found on port $port")
            currentPairingPort = port
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, createInputNotification(port))
        }
    }

    private val connectObserver = Observer<Int> { port ->
        if (port > 0 && !isServiceStopping && foundConnectPort == -1) {
            Log.d(TAG, "Connect service found on port $port")
            foundConnectPort = port
            // Setelah mendapatkan port koneksi, simpan dan beri tahu
            handleConnectFound(port)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
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
        Log.d(TAG, "onStartCommand: action=${intent?.action}, isPairingInProgress=$isPairingInProgress, isStopping=$isServiceStopping")
        
        if (isServiceStopping) {
            Log.w(TAG, "Service is stopping, ignoring action")
            return START_NOT_STICKY
        }
        
        when (intent?.action) {
            START_ACTION -> {
                if (!isPairingInProgress && !isServiceStopping) {
                    startForeground(NOTIFICATION_ID, searchingNotification)
                    startPairingDiscovery()
                }
            }
            
            REPLY_ACTION -> {
                if (isPairingInProgress || isServiceStopping) {
                    Log.w(TAG, "Cannot process reply (inProgress=$isPairingInProgress, stopping=$isServiceStopping)")
                    return START_STICKY
                }
                
                val results = RemoteInput.getResultsFromIntent(intent)
                val code = results?.getCharSequence(REMOTE_INPUT_RESULT_KEY)?.toString()
                val port = intent.getIntExtra(PORT_KEY, -1)
                
                if (!code.isNullOrBlank() && port != -1) {
                    if (code.matches(Regex("^\\d{6}$"))) {
                        isPairingInProgress = true
                        stopPairingDiscovery()
                        
                        startForeground(NOTIFICATION_ID, workingNotification)
                        startPairing(code, port)
                    } else {
                        showErrorNotification("Kode harus 6 digit angka")
                        startForeground(NOTIFICATION_ID, createInputNotification(port))
                    }
                } else {
                    showErrorNotification("Kode atau port tidak valid")
                    startForeground(NOTIFICATION_ID, createInputNotification(currentPairingPort))
                }
            }
            
            STOP_ACTION -> {
                Log.d(TAG, "Stopping service by user request")
                stopService()
            }
        }
        
        return START_STICKY
    }

    private fun startPairingDiscovery() {
        Log.d(TAG, "Starting mDNS pairing discovery")
        stopPairingDiscovery()
        
        isServiceStopping = false
        pairingMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, pairingObserver).apply { 
            start() 
        }
    }

    private fun stopPairingDiscovery() {
        Log.d(TAG, "Stopping mDNS pairing discovery")
        pairingMdns?.stop()
        pairingMdns = null
    }

    private fun startConnectDiscovery() {
        Log.d(TAG, "Starting mDNS connect discovery")
        stopConnectDiscovery()
        
        connectMdns = AdbMdns(this, AdbMdns.TLS_CONNECT, connectObserver).apply { 
            start() 
        }
    }

    private fun stopConnectDiscovery() {
        Log.d(TAG, "Stopping mDNS connect discovery")
        connectMdns?.stop()
        connectMdns = null
    }

    private fun startPairing(code: String, port: Int) {
        Log.d(TAG, "Starting pairing with port=$port")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adbKey = AdbKey(this@AdbPairingService, "kyroos_device")
                val pairingClient = AdbPairingClient("127.0.0.1", port, code, adbKey)
                
                val success = pairingClient.use { it.start() }
                
                withContext(Dispatchers.Main) {
                    if (success) {
                        // Pairing sukses, sekarang cari port koneksi
                        Log.i(TAG, "Pairing successful, now searching for connect port...")
                        isPairingInProgress = false
                        startForeground(NOTIFICATION_ID, searchingConnectNotification)
                        startConnectDiscovery()
                        
                        // Timeout jika tidak menemukan connect port dalam 30 detik
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(30000)
                            if (foundConnectPort == -1 && !isServiceStopping) {
                                Log.e(TAG, "Timeout waiting for connect port")
                                handleFailure("Tidak dapat menemukan port koneksi")
                            }
                        }
                    } else {
                        isPairingInProgress = false
                        handleFailure("Pairing gagal, periksa kode dan coba lagi")
                    }
                }
                
            } catch (e: AdbInvalidPairingCodeException) {
                Log.e(TAG, "Invalid pairing code", e)
                withContext(Dispatchers.Main) {
                    isPairingInProgress = false
                    handleFailure("Kode pairing salah")
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Runtime error (native library?)", e)
                withContext(Dispatchers.Main) {
                    isPairingInProgress = false
                    handleFailure("Library error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing error", e)
                withContext(Dispatchers.Main) {
                    isPairingInProgress = false
                    handleFailure(e.message ?: "Terjadi kesalahan")
                }
            }
        }
    }

    private fun handleConnectFound(port: Int) {
        Log.i(TAG, "Connect port found: $port, stopping service...")
        
        isServiceStopping = true
        stopConnectDiscovery()
        
        // Simpan port koneksi ke SharedPreferences
        val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("paired_port", port).apply()
        
        // Kirim broadcast dengan port yang benar
        val broadcastIntent = Intent(PAIRING_SUCCESS_ACTION).apply {
            putExtra(EXTRA_PORT, port)
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)
        
        // Tampilkan notifikasi sukses
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        
        val successNotification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("KyrooS Berhasil Terhubung! ✅")
            .setContentText("ADB Wireless aktif di port $port")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        nm.notify(SUCCESS_NOTIFICATION_ID, successNotification)
        
        // Hentikan service
        stopServiceInternal()
    }

    private fun handleFailure(message: String) {
        Log.e(TAG, "Pairing failed: $message")
        
        if (isServiceStopping) return
        
        // Kirim broadcast gagal
        val broadcastIntent = Intent(PAIRING_FAILED_ACTION).apply {
            putExtra(EXTRA_ERROR, message)
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)
        
        val nm = getSystemService(NotificationManager::class.java)
        
        val errorNotification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Pairing Gagal ❌")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        nm.notify(ERROR_NOTIFICATION_ID, errorNotification)
        
        // Restart discovery setelah beberapa detik
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isServiceStopping && !isPairingInProgress) {
                Log.d(TAG, "Restarting pairing discovery after failure")
                startPairingDiscovery()
                startForeground(NOTIFICATION_ID, searchingNotification)
            }
        }, 3000)
    }

    private fun stopService() {
        Log.d(TAG, "Stopping service by request...")
        isServiceStopping = true
        stopPairingDiscovery()
        stopConnectDiscovery()
        stopServiceInternal()
    }

    private fun stopServiceInternal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        stopSelf()
    }

    private fun showErrorNotification(message: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Error")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        nm.notify(ERROR_NOTIFICATION_ID, notification)
    }

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
        
        val replyAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_send),
                "INPUT KODE",
                pendingIntent
            ).addRemoteInput(remoteInput).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(
                android.R.drawable.ic_menu_send,
                "INPUT KODE",
                pendingIntent
            ).addRemoteInput(remoteInput).build()
        }

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Layanan Pairing Ditemukan")
            .setContentText("Port: $port - Ketuk untuk memasukkan kode 6 digit")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(replyAction)
            .setOngoing(true)
            .build()
    }

    private val searchingNotification: Notification
        get() = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Mencari Wireless Debugging...")
            .setContentText("Pastikan Wireless Debugging aktif di pengaturan developer")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    private val searchingConnectNotification: Notification
        get() = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Mencari port koneksi...")
            .setContentText("Tunggu sebentar, sedang mengaktifkan koneksi")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    private val workingNotification: Notification
        get() = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Sedang Menghubungkan...")
            .setContentText("Jangan tutup aplikasi...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        isServiceStopping = true
        stopPairingDiscovery()
        stopConnectDiscovery()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}