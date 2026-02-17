package com.kyroos.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import frb.axeron.adb.AdbClient
import frb.axeron.adb.AdbKey
import frb.axeron.adb.AdbPairingService

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Konfigurasi WebView
        webView = findViewById(R.id.webView)
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        // Menghubungkan script.js dengan Kotlin
        webView.addJavascriptInterface(KyroosAdbInterface(this), "KyroosApp")
        
        // Memuat Web UI dari folder assets
        webView.loadUrl("file:///android_asset/index.html")

        // Meminta izin notifikasi & jalankan Service Pairing
        checkPermissions()
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

    private fun startPairingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(this, AdbPairingService::class.java).apply { action = "start" }
            startForegroundService(intent)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}

// Jembatan untuk mengeksekusi perintah dari Web UI (script.js)
class KyroosAdbInterface(private val context: Context) {
    @JavascriptInterface
    fun executeShell(command: String): String {
        return try {
            val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            val port = prefs.getInt("paired_port", -1)
            if (port == -1) return "Error: Belum ada pairing ADB!"

            val adbKey = AdbKey(prefs)
            val client = AdbClient(adbKey, port)
            client.connect()
            // Di sini kamu memanggil fungsi execute dari AdbClient.kt
            "Success execute: $command" 
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
