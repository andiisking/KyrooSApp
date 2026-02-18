package com.kyroos.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {
    internal lateinit var webView: WebView
    private var isReceiverRegistered = false
    internal val activeClients = ConcurrentHashMap<String, AdbClient>()
    
    // Handler untuk main thread
    private val mainHandler = Handler(Looper.getMainLooper())

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

                        runOnUiThread {
                            webView.evaluateJavascript(
                                "if(window.onPairingSuccess) window.onPairingSuccess($port)", 
                                null
                            )
                        }

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
            javaScriptCanOpenWindowsAutomatically = true
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        
        webView.webViewClient = WebViewClient()
        
        // PENTING: Tambah interface SEBELUM load URL
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
        
        activeClients.forEach { (_, client) ->
            try { client.close() } catch (_: Exception) {}
        }
        activeClients.clear()
        
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(pairingReceiver)
            } catch (_: IllegalArgumentException) {}
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
            Toast.makeText(this, "Menggunakan port: $pairedPort", Toast.LENGTH_SHORT).show()
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
                    Log.w("MainActivity", "Port $port not reachable, restarting pairing")
                    prefs.edit().remove("paired_port").apply()
                    Toast.makeText(this@MainActivity, 
                        "ADB terputus, pairing ulang...", 
                        Toast.LENGTH_LONG).show()
                    startPairing()
                } else {
                    Log.d("MainActivity", "Port $port ready")
                    // Delay sedikit agar JS siap
                    mainHandler.postDelayed({
                        webView.evaluateJavascript(
                            "if(window.onAdbReady) window.onAdbReady($port)", 
                            null
                        )
                    }, 500)
                }
            }
        }
    }

    private suspend fun isPortReachable(port: Int, timeoutMs: Int = 2000): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

    // Method untuk kirim callback ke JS dengan aman
    fun sendJsCallback(callbackId: String, base64Result: String, isError: Boolean) {
        mainHandler.post {
            try {
                // Escape single quotes
                val safeId = callbackId.replace("'", "\\'")
                val safeBase64 = base64Result.replace("'", "\\'")
                
                val js = "javascript:if(window.adbCallback){window.adbCallback('$safeId','$safeBase64',$isError)}else{console.error('adbCallback not found')}"
                
                webView.evaluateJavascript(js) { result ->
                    Log.d("MainActivity", "Callback result: $result")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to send callback: ${e.message}")
            }
        }
    }
}

class KyroosAdbInterface(private val activity: MainActivity) {

    @JavascriptInterface
    fun executeShell(command: String): String {
        Log.d("KyroosAdb", "Sync: ${command.take(50)}")

        val prefs = activity.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("paired_port", -1)

        if (port == -1) return "Error: Belum dipairing!"

        return try {
            val key = AdbKey(activity, "kyroos_device") 
            val client = AdbClient(key, port, "127.0.0.1")
            client.connect() 
            val stream = client.open("shell:$command")
            val result = stream.readAll().toString(Charsets.UTF_8)
            stream.close()
            client.close()
            result
        } catch (e: Exception) {
            Log.e("KyroosAdb", "Sync error: ${e.message}")
            handleConnectionError(e)
            "Error: ${e.message}"
        }
    }

    @JavascriptInterface
    fun executeShell(command: String, callbackId: String) {
        Log.d("KyroosAdb", "Async: ${command.take(50)}, ID: $callbackId")

        val prefs = activity.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("paired_port", -1)

        if (port == -1) {
            activity.sendJsCallback(callbackId, 
                Base64.encodeToString("Error: Belum dipairing".toByteArray(), Base64.NO_WRAP), 
                true)
            return
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            var result = ""
            var isError = false
            
            try {
                withTimeout(20000) {
                    val key = AdbKey(activity, "kyroos_device") 
                    val client = AdbClient(key, port, "127.0.0.1")
                    
                    activity.activeClients[callbackId] = client
                    
                    try {
                        client.connect() 
                        val stream = client.open("shell:$command")
                        result = stream.readAll().toString(Charsets.UTF_8)
                        stream.close()
                    } finally {
                        client.close()
                        activity.activeClients.remove(callbackId)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                isError = true
                result = "Error: Timeout (20s)"
                Log.e("KyroosAdb", "Timeout: $command")
            } catch (e: Exception) {
                isError = true
                result = "Error: ${e.message}"
                Log.e("KyroosAdb", "Error: ${e.message}")
                handleConnectionError(e)
            }

            // Kirim callback ke main thread
            val base64Result = Base64.encodeToString(
                result.toByteArray(Charsets.UTF_8), 
                Base64.NO_WRAP
            )
            
            activity.sendJsCallback(callbackId, base64Result, isError)
        }
    }

    private fun handleConnectionError(e: Exception) {
        if (e.message?.contains("Connection refused") == true || 
            e.message?.contains("Broken pipe") == true ||
            e.message?.contains("reset by peer") == true) {
            activity.runOnUiThread {
                activity.verifyStoredPort()
            }
        }
    }

    @JavascriptInterface
    fun isPaired(): Boolean {
        val prefs = activity.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("paired_port", -1) != -1
    }

    @JavascriptInterface
    fun getPairedPort(): Int {
        val prefs = activity.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("paired_port", -1)
    }

    @JavascriptInterface
    fun resetPairing() {
        val prefs = activity.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("paired_port").apply()

        val intent = AdbPairingService.startIntent(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
    }
}
