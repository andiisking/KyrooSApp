package com.kyroos.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import frb.axeron.adb.AdbClient
import frb.axeron.adb.AdbKey
import frb.axeron.adb.AdbPairingService
import com.kyroos.app.R 

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inisialisasi WebView untuk memuat Web UI KyrooS
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        webView.webViewClient = WebViewClient()
        
        // Menambahkan interface JavaScript agar script.js bisa memanggil fungsi Android
        webView.addJavascriptInterface(KyroosAdbInterface(this), "KyroosApp")
        webView.loadUrl("file:///android_asset/index.html")

        // 1. Memeriksa izin yang diperlukan (terutama notifikasi untuk Android 13+)
        checkPermissions()

        // 2. Memeriksa status pairing saat aplikasi dibuka
        checkPairingStatus()
    }

    /**
     * Memeriksa apakah data pairing sudah ada di penyimpanan lokal.
     * Jika belum ada, otomatis membuka pengaturan Wireless Debugging.
     */
    private fun checkPairingStatus() {
        val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("paired_port", -1)

        if (port == -1) {
            openWirelessDebuggingSettings()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else { 
                startPairingService() 
            }
        } else { 
            startPairingService() 
        }
    }

    /**
     * Menjalankan AdbPairingService yang menggunakan AdbMdns untuk scanning port.
     */
    private fun startPairingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(this, AdbPairingService::class.java).apply { action = "start" }
            startForegroundService(intent)
        }
    }

    /**
     * Mengarahkan user secara otomatis ke menu Wireless Debugging atau Developer Options.
     */
    fun openWirelessDebuggingSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // Mencoba membuka halaman Wireless Debugging secara langsung
                val intent = Intent(Settings.ACTION_WIFI_ADB_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                // Jika shortcut tidak tersedia, arahkan ke Developer Options
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                startActivity(intent)
            }
        }
    }
}

/**
 * Interface penghubung antara JavaScript di WebView dan fungsi sistem di Android.
 */
class KyroosAdbInterface(private val context: Context) {

    @JavascriptInterface
    fun executeShell(command: String): String {
        return try {
            val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            val port = prefs.getInt("paired_port", -1)
            
            if (port == -1) return "Error: Perangkat belum ter-pairing!"
            
            // Menggunakan AdbClient untuk eksekusi shell command
            val client = AdbClient(AdbKey(prefs), "127.0.0.1", port)
            client.execute(command)
        } catch (e: Exception) { 
            "Error: ${e.message}" 
        }
    }

    @JavascriptInterface
    fun resetPairing() {
        // Menghapus data pairing lama dan memicu alur pairing ulang
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        
        if (context is MainActivity) {
            context.runOnUiThread {
                context.openWirelessDebuggingSettings()
            }
        }
    }
}
