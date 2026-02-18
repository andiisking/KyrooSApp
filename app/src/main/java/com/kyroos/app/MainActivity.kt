package com.kyroos.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    
    internal lateinit var webView: WebView
    internal lateinit var context: Context
    
    internal val SHIZUKU_PERMISSION_CODE = 1001
    internal var isShizukuAvailable = false
    internal var isShizukuPermissionGranted = false
    
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_CODE) {
            isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                if (isShizukuPermissionGranted) {
                    Toast.makeText(this, "✅ Shizuku Ready!", Toast.LENGTH_SHORT).show()
                    webView.evaluateJavascript(
                        "if(window.onShizukuStatus) window.onShizukuStatus('ready')",
                        null
                    )
                } else {
                    Toast.makeText(this, "❌ Shizuku Denied", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        context = this
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
            loadWithOverviewMode = true
            useWideViewPort = true
            
            // Izinkan network
            blockNetworkImage = false
            blockNetworkLoads = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        
        webView.webViewClient = WebViewClient()
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("WebView", "${message.message()} (${message.lineNumber()})")
                return true
            }
        }
        
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
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            checkShizukuStatus()
        } catch (e: Exception) {
            Log.e("Shizuku", "Setup error", e)
        }
    }
    
    private fun checkShizukuStatus() {
        try {
            if (Shizuku.pingBinder()) {
                Log.d("Shizuku", "✅ Shizuku connected")
                isShizukuAvailable = true
                
                val permission = Shizuku.checkSelfPermission()
                if (permission == PackageManager.PERMISSION_GRANTED) {
                    isShizukuPermissionGranted = true
                    onShizukuReady()
                } else {
                    Log.d("Shizuku", "Requesting permission...")
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
                }
            } else {
                Log.e("Shizuku", "❌ Shizuku not running")
                isShizukuAvailable = false
                showShizukuError("Shizuku is not running")
            }
        } catch (e: Exception) {
            Log.e("Shizuku", "Error checking status", e)
            isShizukuAvailable = false
        }
    }
    
    private fun showShizukuError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun onShizukuReady() {
        Log.d("Shizuku", "✅ Shizuku ready!")
        runOnUiThread {
            Toast.makeText(this, "✅ Shizuku Ready!", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ========== EXECUTE SHELL ==========
    
    fun executeShell(command: String, callbackId: String) {
        Log.d("Shell", "🚀 Executing: $command")
        
        Thread {
            try {
                // 🔥 SEDERHANA: Langsung exec tanpa sh -c
                val process = Runtime.getRuntime().exec(command)
                
                // Baca output
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                
                // Baca error
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val error = StringBuilder()
                while (errorReader.readLine().also { line = it } != null) {
                    error.append(line).append("\n")
                }
                
                val exitCode = process.waitFor()
                
                val result = if (exitCode == 0) {
                    output.toString().trim()
                } else {
                    "Error($exitCode): ${error.toString().trim()}"
                }
                
                Log.d("Shell", "✅ Result: ${result.take(100)}...")
                
                // Kirim ke JavaScript
                runOnUiThread {
                    val escaped = result
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                    
                    val jsCode = "window.shellCallback('$callbackId', '$escaped', false)"
                    webView.evaluateJavascript(jsCode, null)
                }
                
            } catch (e: Exception) {
                Log.e("Shell", "❌ Error: ${e.message}")
                runOnUiThread {
                    val jsCode = "window.shellCallback('$callbackId', 'Error: ${e.message}', true)"
                    webView.evaluateJavascript(jsCode, null)
                }
            }
        }.start()
    }
    
    fun executeShellSync(command: String): String {
        Log.d("Shell", "🚀 Sync: $command")
        
        return try {
            val process = Runtime.getRuntime().exec(command)
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val error = StringBuilder()
            while (errorReader.readLine().also { line = it } != null) {
                error.append(line).append("\n")
            }
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                output.toString().trim()
            } else {
                "Error($exitCode): ${error.toString().trim()}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    // ========== FUNGSI UNTUK JS ==========
    
    fun isShizukuAvailable(): Boolean {
        return isShizukuAvailable && isShizukuPermissionGranted
    }
    
    fun getShizukuStatus(): String {
        return when {
            !isShizukuAvailable -> "not_installed"
            !isShizukuPermissionGranted -> "no_permission"
            else -> "ready"
        }
    }
    
    fun requestShizukuPermission() {
        runOnUiThread {
            if (!isShizukuAvailable) {
                Toast.makeText(this, "Shizuku not running", Toast.LENGTH_SHORT).show()
            } else if (!isShizukuPermissionGranted) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            }
        }
    }
}

// ========== JAVASCRIPT INTERFACE ==========
class KyroosShellInterface(private val activity: MainActivity) {
    
    @JavascriptInterface
    fun executeShell(command: String, callbackId: String) {
        activity.executeShell(command, callbackId)
    }
    
    @JavascriptInterface
    fun executeShellSync(command: String): String {
        return activity.executeShellSync(command)
    }
    
    @JavascriptInterface
    fun isShizukuAvailable(): Boolean {
        return activity.isShizukuAvailable()
    }
    
    @JavascriptInterface
    fun getShizukuStatus(): String {
        return activity.getShizukuStatus()
    }
    
    @JavascriptInterface
    fun requestShizukuPermission() {
        activity.requestShizukuPermission()
    }
}