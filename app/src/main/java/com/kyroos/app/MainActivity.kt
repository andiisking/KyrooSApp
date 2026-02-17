package com.kyroos.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import frb.axeron.adb.AdbClient
import frb.axeron.adb.AdbKey
import frb.axeron.adb.AdbPairingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    internal lateinit var webView: WebView
    
    private var isReceiverRegistered = false
    
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AdbPairingService.PAIRING_SUCCESS_ACTION -> {
                    val port = intent.getIntExtra(AdbPairingService.EXTRA_PORT, -1)
                    if (port != -1) {
                        val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putInt("paired_port", port).apply()
                        
                        Toast.makeText(this@MainActivity, 
                            "Pairing berhasil! Port: $port", 
                            Toast.LENGTH_LONG).show()
                        
                        if (::webView.isInitialized) {
                            webView.evaluateJavascript(
                                "if(window.onPairingSuccess) window.onPairingSuccess($port)", 
                                null
                            )
                        }
                    }
                }
                AdbPairingService.PAIRING_FAILED_ACTION -> {
                    val error = intent.getStringExtra(AdbPairingService.EXTRA_ERROR) ?: "Unknown error"
                    Toast.makeText(this@MainActivity, 
                        "Pairing gagal: $error", 
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(KyroosAdbInterface(this), "KyroosApp")
        webView.loadUrl("file:///android_asset/index.html")

        val filter = IntentFilter().apply {
            addAction(AdbPairingService.PAIRING_SUCCESS_ACTION)
            addAction(AdbPairingService.PAIRING_FAILED_ACTION)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pairingReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to register receiver", e)
        }

        checkPermissions()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(pairingReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver sudah unregistered, ignore
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startPairing()
                } else {
                    Toast.makeText(this, 
                        "Izin notifikasi diperlukan untuk pairing", 
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, 
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                    101
                )
            } else { 
                startPairing() 
            }
        } else { 
            startPairing() 
        }
    }

    private fun startPairing() {
        val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val pairedPort = prefs.getInt("paired_port", -1)
        
        if (pairedPort == -1) {
            val intent = AdbPairingService.startIntent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            Toast.makeText(this, "Menggunakan port tersimpan: $pairedPort", Toast.LENGTH_SHORT).show()
        }
    }
}

class KyroosAdbInterface(private val context: Context) {
    
    @JavascriptInterface
    fun executeShell(command: String, callbackId: String) {
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("paired_port", -1)
        
        if (port == -1) {
            (context as? MainActivity)?.runOnUiThread {
                (context as? MainActivity)?.webView?.evaluateJavascript(
                    "if(window.adbCallback) window.adbCallback('$callbackId', 'Error: Belum dipairing', true)",
                    null
                )
            }
            return
        }

        (context as? MainActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
            val result = try {
                val key = AdbKey(context, "kyroos_device") 
                val client = AdbClient(key, port, "127.0.0.1")
                
                client.connect() 
                val stream = client.open("shell:$command")
                val output = stream.readAll().toString(Charsets.UTF_8)
                
                stream.close()
                client.close()
                output
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            
            withContext(Dispatchers.Main) {
                val escapedResult = result.replace("'", "\\'").replace("\n", "\\n")
                (context as? MainActivity)?.webView?.evaluateJavascript(
                    "if(window.adbCallback) window.adbCallback('$callbackId', '$escapedResult', false)",
                    null
                )
            }
        }
    }
    
    @JavascriptInterface
    fun executeShellSync(command: String): String {
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("paired_port", -1)
        
        if (port == -1) return "Error: Perangkat belum dipairing!"

        return try {
            val key = AdbKey(context, "kyroos_device") 
            val client = AdbClient(key, port, "127.0.0.1")
            
            client.connect() 
            val stream = client.open("shell:$command")
            val result = stream.readAll().toString(Charsets.UTF_8)
            
            stream.close()
            client.close()
            result
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    @JavascriptInterface
    fun isPaired(): Boolean {
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("paired_port", -1) != -1
    }
    
    @JavascriptInterface
    fun getPairedPort(): Int {
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("paired_port", -1)
    }
    
    @JavascriptInterface
    fun resetPairing() {
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("paired_port").apply()
        
        val intent = AdbPairingService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
