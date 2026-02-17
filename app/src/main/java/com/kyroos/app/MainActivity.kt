package com.kyroos.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import frb.axeron.adb.AdbClient
import frb.axeron.adb.AdbKey
import frb.axeron.adb.AdbPairingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(KyroosAdbInterface(this), "KyroosApp")
        webView.loadUrl("file:///android_asset/index.html")

        checkPermissions()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else { startPairing() }
        } else { startPairing() }
    }

    private fun startPairing() {
        val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("paired_port", -1) == -1) {
            val intent = Intent(this, AdbPairingService::class.java).apply { action = "start" }
            startForegroundService(intent)
        }
    }
}

class KyroosAdbInterface(private val context: Context) {
    @JavascriptInterface
    fun executeShell(command: String): String {
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("paired_port", -1)
        if (port == -1) return "Error: Belum Pairing!"

        return try {
            runBlocking(Dispatchers.IO) {
                val key = AdbKey(prefs)
                // Urutan parameter asli Axeron: (AdbKey, Port, Host)
                val client = AdbClient(key, port, "127.0.0.1")
                
                // Membuka stream shell sesuai AdbClient.kt asli
                val stream = client.open("shell:$command")
                val result = stream.readAll().toString(Charsets.UTF_8)
                
                stream.close()
                client.close()
                result
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
