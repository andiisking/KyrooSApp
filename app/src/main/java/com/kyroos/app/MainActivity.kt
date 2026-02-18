package com.kyroos.app

import android.Manifest
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
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    
    internal lateinit var webView: WebView
    
    // Shizuku permission request code
    private val SHIZUKU_PERMISSION_CODE = 1001
    
    // Status Shizuku
    private var isShizukuAvailable = false
    private var isShizukuPermissionGranted = false
    
    // Listener untuk hasil permission Shizuku
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_CODE) {
            isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                if (isShizukuPermissionGranted) {
                    Toast.makeText(this, "✅ Shizuku permission granted!", Toast.LENGTH_SHORT).show()
                    // Kirim status ke WebView
                    webView.evaluateJavascript(
                        "if(window.onShizukuStatus) window.onShizukuStatus('granted')",
                        null
                    )
                } else {
                    Toast.makeText(this, "❌ Shizuku permission denied", Toast.LENGTH_LONG).show()
                    webView.evaluateJavascript(
                        "if(window.onShizukuStatus) window.onShizukuStatus('denied')",
                        null
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inisialisasi WebView
        webView = findViewById(R.id.webView)
        setupWebView()
        
        // Cek permission notifikasi (untuk Android 13+)
        checkNotificationPermission()
        
        // Setup Shizuku
        setupShizuku()
    }
    
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadsImagesAutomatically = true
        }
        
        // WebChromeClient untuk console.log
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("Kyroos_WebView", "${message.message()} (${message.lineNumber()})")
                return true
            }
        }
        
        webView.webViewClient = WebViewClient()
        
        // Tambahkan JavaScript interface
        webView.addJavascriptInterface(KyroosShellInterface(this), "KyroosApp")
        
        // Load HTML dari assets
        webView.loadUrl("file:///android_asset/index.html")
    }
    
    override fun onResume() {
        super.onResume()
        // Cek ulang status Shizuku setiap kali activity resume
        checkShizukuStatus()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Hapus listener untuk mencegah memory leak
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            101 -> { // Notification permission
                // Tidak perlu action khusus
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, 
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                    101
                )
            }
        }
    }
    
    // ========== SHIZUKU IMPLEMENTATION ==========
    
    private fun setupShizuku() {
        // Tambahkan listener permission
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        
        // Cek status awal
        checkShizukuStatus()
    }
    
    private fun checkShizukuStatus() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                isShizukuAvailable = false
                showShizukuError("Shizuku version too old. Please update Shizuku.")
            } else {
                isShizukuAvailable = true
                
                // Cek permission
                val permission = Shizuku.checkSelfPermission()
                if (permission == PackageManager.PERMISSION_GRANTED) {
                    isShizukuPermissionGranted = true
                    onShizukuReady()
                } else {
                    // Minta permission
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
                }
            }
        } else {
            isShizukuAvailable = false
            showShizukuError("Shizuku is not running. Please install and activate Shizuku first.")
        }
    }
    
    private fun showShizukuError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            Log.e("Kyroos_Shizuku", message)
            
            // Kirim error ke WebView
            if (::webView.isInitialized) {
                val escapedMessage = message.replace("'", "\\'")
                webView.evaluateJavascript(
                    "if(window.onShizukuError) window.onShizukuError('$escapedMessage')",
                    null
                )
            }
        }
    }
    
    private fun onShizukuReady() {
        Log.d("Kyroos_Shizuku", "Shizuku ready!")
        runOnUiThread {
            Toast.makeText(this, "✅ Shizuku siap digunakan!", Toast.LENGTH_SHORT).show()
            
            // Kirim status ke WebView
            webView.evaluateJavascript(
                "if(window.onShizukuStatus) window.onShizukuStatus('ready')",
                null
            )
        }
    }
    
    // Fungsi untuk menjalankan shell command dan mendapatkan hasil
    suspend fun executeShellCommand(command: String): Shell.Result {
        return withContext(Dispatchers.IO) {
            Shell.cmd(command).exec()
        }
    }
}

// ========== JAVASCRIPT INTERFACE UNTUK SHELL ==========

class KyroosShellInterface(private val activity: MainActivity) {
    
    @JavascriptInterface
    fun executeShell(command: String, callbackId: String) {
        Log.d("Kyroos_Shell", "Async command: $command, callbackId: $callbackId")
        
        // Cek ketersediaan Shizuku
        if (!activity.isShizukuAvailable || !activity.isShizukuPermissionGranted) {
            activity.runOnUiThread {
                activity.webView.evaluateJavascript(
                    "window.shellCallback('$callbackId', 'Error: Shizuku tidak tersedia', true)",
                    null
                )
            }
            return
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = activity.executeShellCommand(command)
                
                val output = if (result.isSuccess) {
                    result.out.joinToString("\n")
                } else {
                    "Error: ${result.err.joinToString("\n")}"
                }
                
                withContext(Dispatchers.Main) {
                    // Escape string untuk JavaScript
                    val escapedOutput = output
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                    
                    activity.webView.evaluateJavascript(
                        "window.shellCallback('$callbackId', '$escapedOutput', ${!result.isSuccess})",
                        null
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    activity.webView.evaluateJavascript(
                        "window.shellCallback('$callbackId', 'Error: ${e.message}', true)",
                        null
                    )
                }
            }
        }
    }
    
    @JavascriptInterface
    fun executeShellSync(command: String): String {
        Log.d("Kyroos_Shell", "Sync command: $command")
        
        // Cek ketersediaan Shizuku
        if (!activity.isShizukuAvailable || !activity.isShizukuPermissionGranted) {
            return "Error: Shizuku tidak tersedia"
        }

        return try {
            // Jalankan secara blocking (hati-hati untuk command panjang)
            val result = kotlinx.coroutines.runBlocking {
                activity.executeShellCommand(command)
            }
            
            if (result.isSuccess) {
                result.out.joinToString("\n")
            } else {
                "Error: ${result.err.joinToString("\n")}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    @JavascriptInterface
    fun isShizukuAvailable(): Boolean {
        return activity.isShizukuAvailable && activity.isShizukuPermissionGranted
    }
    
    @JavascriptInterface
    fun getShizukuStatus(): String {
        return when {
            !activity.isShizukuAvailable -> "not_installed"
            !activity.isShizukuPermissionGranted -> "no_permission"
            else -> "ready"
        }
    }
    
    @JavascriptInterface
    fun requestShizukuPermission() {
        activity.runOnUiThread {
            if (!activity.isShizukuAvailable) {
                Toast.makeText(activity, "Shizuku tidak berjalan", Toast.LENGTH_SHORT).show()
            } else if (!activity.isShizukuPermissionGranted) {
                Shizuku.requestPermission(activity.SHIZUKU_PERMISSION_CODE)
            }
        }
    }
    
    @JavascriptInterface
    fun getSystemProperty(key: String): String {
        return try {
            val result = kotlinx.coroutines.runBlocking {
                activity.executeShellCommand("getprop $key")
            }
            if (result.isSuccess) result.out.firstOrNull() ?: "" else ""
        } catch (e: Exception) {
            ""
        }
    }
    
    @JavascriptInterface
    fun getDeviceInfo(): String {
        return try {
            val result = kotlinx.coroutines.runBlocking {
                activity.executeShellCommand("uname -a")
            }
            if (result.isSuccess) result.out.joinToString("\n") else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}