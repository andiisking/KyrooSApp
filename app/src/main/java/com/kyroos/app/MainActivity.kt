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
    
    internal lateinit var webView: WebView  // ← UBAH ke internal
    
    internal val SHIZUKU_PERMISSION_CODE = 1001  // ← UBAH ke internal val
    
    internal var isShizukuAvailable = false  // ← UBAH ke internal var
    internal var isShizukuPermissionGranted = false  // ← UBAH ke internal var
    
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_CODE) {
            isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                if (isShizukuPermissionGranted) {
                    Toast.makeText(this, "✅ Shizuku permission granted!", Toast.LENGTH_SHORT).show()
                    webView.evaluateJavascript(
                        "if(window.onShizukuStatus) window.onShizukuStatus('ready')",
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

        webView = findViewById(R.id.webView)
        setupWebView()
        
        checkNotificationPermission()
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
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("Kyroos_WebView", "${message.message()} (${message.lineNumber()})")
                return true
            }
        }
        
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(KyroosShellInterface(this), "KyroosApp")
        webView.loadUrl("file:///android_asset/index.html")
    }
    
    override fun onResume() {
        super.onResume()
        checkShizukuStatus()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) { }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            101 -> { } // Notification permission
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
    
    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        checkShizukuStatus()
    }
    
    private fun checkShizukuStatus() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                isShizukuAvailable = false
                showShizukuError("Shizuku version too old. Please update Shizuku.")
            } else {
                isShizukuAvailable = true
                
                val permission = Shizuku.checkSelfPermission()
                if (permission == PackageManager.PERMISSION_GRANTED) {
                    isShizukuPermissionGranted = true
                    onShizukuReady()
                } else {
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
            webView.evaluateJavascript(
                "if(window.onShizukuStatus) window.onShizukuStatus('ready')",
                null
            )
        }
    }
    
    suspend fun executeShellCommand(command: String): Shell.Result {
        return withContext(Dispatchers.IO) {
            Shell.cmd(command).exec()
        }
    }
}

class KyroosShellInterface(private val activity: MainActivity) {
    
    @JavascriptInterface
    fun executeShell(command: String, callbackId: String) {
        Log.d("Kyroos_Shell", "Async command: $command, callbackId: $callbackId")
        
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
        
        if (!activity.isShizukuAvailable || !activity.isShizukuPermissionGranted) {
            return "Error: Shizuku tidak tersedia"
        }

        return try {
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
}