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
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
import frb.axeron.adb.PreferenceAdbKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

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

                        // Setelah pairing sukses, verifikasi koneksi
                        verifyStoredPort()
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
        
        // WebChromeClient untuk menangkap console.log dari JavaScript
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("JS_Console", "${message.message()} -- Dari baris ${message.lineNumber()} dari ${message.sourceId()}")
                return true
            }
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

    override fun onResume() {
        super.onResume()
        verifyStoredPort()
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
            // Tidak ada port tersimpan, mulai pairing service
            val intent = AdbPairingService.startIntent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            Toast.makeText(this, "Menggunakan port tersimpan: $pairedPort", Toast.LENGTH_SHORT).show()
            verifyStoredPort()
        }
    }

    internal fun verifyStoredPort() {
        val prefs = getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("paired_port", -1)
        if (port == -1) return

        lifecycleScope.launch(Dispatchers.IO) {
            val reachable = isPortReachable(port)
            withContext(Dispatchers.Main) {
                if (!reachable) {
                    Log.w("MainActivity", "Stored port $port is not reachable, clearing and restarting pairing")
                    prefs.edit().remove("paired_port").apply()
                    Toast.makeText(this@MainActivity, 
                        "Koneksi ADB terputus, memulai pairing ulang...", 
                        Toast.LENGTH_LONG).show()
                    startPairing()
                } else {
                    Log.d("MainActivity", "Stored port $port is reachable")
                }
            }
        }
    }

    private suspend fun isPortReachable(port: Int, timeoutMs: Int = 2000): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}

class KyroosAdbInterface(private val context: Context) {
    
    @JavascriptInterface
    fun executeShell(command: String): String {
        Log.d("KyroosAdb", "executeShell (sync) called: $command")
        
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("paired_port", -1)
        
        if (port == -1) {
            return "Error: Perangkat belum dipairing!"
        }

        return try {
            // Buat AdbKeyStore menggunakan PreferenceAdbKeyStore dari Axeron
            val keyStore = PreferenceAdbKeyStore(prefs)
            val key = AdbKey(keyStore, "kyroos_device")
            val client = AdbClient(key, port, "127.0.0.1")
            
            client.connect()
            
            // Gunakan shellCommand dengan listener untuk mengumpulkan output
            val result = StringBuilder()
            val latch = java.util.concurrent.CountDownLatch(1)
            
            client.shellCommand(command) { chunk ->
                result.append(String(chunk, Charsets.UTF_8))
            }
            
            // Karena shellCommand tidak sinkron, kita perlu menunggu.
            // Dalam implementasi Axeron, command akan menunggu sampai selesai.
            // Tapi karena kita pakai stream, kita bisa pakai open seperti sebelumnya.
            // Untuk amannya, kita tetap pakai stream karena sudah diperbaiki.
            
            // Alternatif: pakai stream seperti sebelumnya
            val stream = client.open("shell:$command")
            val output = stream.readAll().toString(Charsets.UTF_8)
            
            stream.close()
            client.close()
            Log.d("KyroosAdb", "Result: $output")
            output
        } catch (e: Exception) {
            Log.e("KyroosAdb", "executeShell error: ${e.message}")
            if (e.message?.contains("Connection refused") == true) {
                (context as? MainActivity)?.verifyStoredPort()
            }
            "Error: ${e.message}"
        }
    }
    
    @JavascriptInterface
    fun executeShell(command: String, callbackId: String) {
        Log.d("KyroosAdb", "executeShell (async) called: $command, callbackId: $callbackId")
        
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
                val keyStore = PreferenceAdbKeyStore(prefs)
                val key = AdbKey(keyStore, "kyroos_device")
                val client = AdbClient(key, port, "127.0.0.1")
                
                client.connect()
                val stream = client.open("shell:$command")
                val output = stream.readAll().toString(Charsets.UTF_8)
                
                stream.close()
                client.close()
                Log.d("KyroosAdb", "Result for $callbackId: $output")
                output
            } catch (e: Exception) {
                Log.e("KyroosAdb", "Async error: ${e.message}")
                if (e.message?.contains("Connection refused") == true) {
                    withContext(Dispatchers.Main) {
                        (context as? MainActivity)?.verifyStoredPort()
                    }
                }
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
        return executeShell(command)
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