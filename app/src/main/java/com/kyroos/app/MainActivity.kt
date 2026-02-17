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

        webView = findViewById(R.id.webView)
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webView.webViewClient = WebViewClient()

        // Registrasi jembatan KyroosApp ke JavaScript
        webView.addJavascriptInterface(KyroosAdbInterface(this), "KyroosApp")
        
        webView.loadUrl("file:///android_asset/index.html")

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

class KyroosAdbInterface(private val context: Context) {
    @JavascriptInterface
    fun executeShell(command: String): String {
        return try {
            val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            val port = prefs.getInt("paired_port", -1)
            
            if (port == -1) return "Error: ADB belum dipairing!"

            val adbKey = AdbKey(prefs)
            val client = AdbClient(adbKey, "127.0.0.1", port)
            
            // Eksekusi dan ambil outputnya
            client.execute(command)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
