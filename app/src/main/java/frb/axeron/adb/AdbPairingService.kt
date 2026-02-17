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
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL = "adb_pairing"
        private const val TAG = "AdbPairingService"
        private const val NOTIFICATION_ID = 1
        private const val REPLY_REQUEST_CODE = 1
        private const val STOP_REQUEST_CODE = 2
        private const val START_ACTION = "start"
        private const val STOP_ACTION = "stop"
        private const val REPLY_ACTION = "reply"
        private const val REMOTE_INPUT_RESULT_KEY = "pairing_code"
        private const val HOST_KEY = "pairing_host"
    }

    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Observer dari file AdbMdns kamu. Jika port ketemu, langsung ubah Notifikasi!
    private val observer = Observer<Int> { port ->
        Log.i(TAG, "Layanan Wireless Debugging Ditemukan di port=$port")
        nm.notify(NOTIFICATION_ID, createInputNotification(port))
    }
    
    private val adbMdns by lazy { AdbMdns(this, AdbMdns.TLS_CONNECT, observer) }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Wireless Debugging Pairing",
            NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            STOP_ACTION -> {
                adbMdns.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            REPLY_ACTION -> {
                // Menangkap 6 digit kode dari tombol "Reply" di Notifikasi
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_RESULT_KEY)?.toString()
                val port = intent.getIntExtra(HOST_KEY, 0)
                
                if (code.isNullOrBlank()) return START_NOT_STICKY
                
                nm.notify(NOTIFICATION_ID, workingNotification)
                
                scope.launch {
                    try {
                        val pairingClient = AdbPairingClient("127.0.0.1", port, code)
                        val result = pairingClient.pair()
                        pairingClient.close()

                        if (result) {
                            // SUKSES! Simpan status ke SharedPreferences KyrooS
                            val prefs = getSharedPreferences("kyroos_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("paired", true).apply()
                            
                            val successNotif = Notification.Builder(this@AdbPairingService, NOTIFICATION_CHANNEL)
                                .setContentTitle("KyrooS Pairing Sukses!")
                                .setContentText("Silakan buka ulang aplikasi KyrooS.")
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .build()
                            nm.notify(NOTIFICATION_ID, successNotif)
                        } else {
                            nm.notify(NOTIFICATION_ID, failedNotification(port))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Pairing Error", e)
                        nm.notify(NOTIFICATION_ID, failedNotification(port))
                    }
                    
                    adbMdns.stop()
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
            else -> {
                // Saat tombol di App ditekan, mulai mencari via MDNS
                try {
                    startForeground(NOTIFICATION_ID, searchingNotification)
                    adbMdns.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal menjalankan Foreground Service", e)
                }
            }
        }
        return START_NOT_STICKY
    }

    // --- UI NOTIFIKASI AXERON ---
    private val stopNotificationAction by lazy {
        val intent = Intent(this, AdbPairingService::class.java).apply { action = STOP_ACTION }
        val pendingIntent = PendingIntent.getService(this, STOP_REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Notification.Action.Builder(null, "Batalkan", pendingIntent).build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY).setLabel("Masukkan 6 Digit Kode Pairing").build()
        val intent = Intent(this, AdbPairingService::class.java).apply { 
            action = REPLY_ACTION
            putExtra(HOST_KEY, port)
        }
        val pendingIntent = PendingIntent.getService(this, REPLY_REQUEST_CODE, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, "Masukkan Kode", pendingIntent).addRemoteInput(remoteInput).build()
    }

    private val searchingNotification by lazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Mencari Wireless Debugging...")
            .setContentText("Pastikan fitur ini menyala di Opsi Developer")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Layanan Ditemukan! (Port: $port)")
            .setContentText("Klik 'Masukkan Kode' di bawah ini")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(replyNotificationAction(port))
            .addAction(stopNotificationAction)
            .build()
    }

    private val workingNotification by lazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Sedang memverifikasi kode...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setProgress(0, 0, true)
            .build()
    }

    private fun failedNotification(port: Int): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Pairing Gagal")
            .setContentText("Kode salah atau koneksi terputus. Coba lagi.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .addAction(replyNotificationAction(port))
            .addAction(stopNotificationAction)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
