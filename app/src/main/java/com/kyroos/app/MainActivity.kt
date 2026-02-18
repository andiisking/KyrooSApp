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
                    
                    // Test shell setelah izin diberikan
                    testShell()
                    
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
            
            // JANGAN BLOCK NETWORK
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
    
    // ========== SHIZUKU IMPLEMENTATION ==========
    
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
    
    // ========== SHELL EXECUTION (RUNTIME) ==========
    
    fun executeShellCommand(command: String): String {
        Log.d("Shell", "Executing: $command")
        
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            
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
            
            return if (exitCode == 0) {
                output.toString().trim()
            } else {
                "Error($exitCode): ${error.toString().trim()}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    private fun testShell() {
        Thread {
            try {
                val result = executeShellCommand("echo 'Shell working!' && getprop ro.product.model")
                Log.d("ShellTest", "Result: $result")
                
                runOnUiThread {
                    Toast.makeText(this, "✅ Shell OK: $result", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ShellTest", "Failed", e)
            }
        }.start()
    }
}

// ========== JAVASCRIPT INTERFACE ==========

class KyroosShellInterface(private val activity: MainActivity) {
    
    @JavascriptInterface
    fun executeShell(command: String, callbackId: String) {
        Log.d("JSInterface", "Async: $command")
        
        Thread {
            try {
                val result = activity.executeShellCommand(command)
                
                activity.runOnUiThread {
                    val escaped = result
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                    
                    val jsCode = "window.shellCallback('$callbackId', '$escaped', false)"
                    activity.webView.evaluateJavascript(jsCode, null)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    val jsCode = "window.shellCallback('$callbackId', 'Error: ${e.message}', true)"
                    activity.webView.evaluateJavascript(jsCode, null)
                }
            }
        }.start()
    }
    
    @JavascriptInterface
    fun executeShellSync(command: String): String {
        Log.d("JSInterface", "Sync: $command")
        return activity.executeShellCommand(command)
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
                Toast.makeText(activity, "Shizuku not running", Toast.LENGTH_SHORT).show()
            } else if (!activity.isShizukuPermissionGranted) {
                Shizuku.requestPermission(activity.SHIZUKU_PERMISSION_CODE)
            }
        }
    }
    
    @JavascriptInterface
    fun testShell(): String {
        return activity.executeShellCommand("echo 'JS interface working!'")
    }
}