package com.kyroos.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.DisplayMetrics
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
    internal val STORAGE_PERMISSION_CODE = 1002
    
    internal var isShizukuAvailable = false
    internal var isShizukuPermissionGranted = false
    internal var isStoragePermissionGranted = false
    
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_CODE) {
            isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                if (isShizukuPermissionGranted) {
                    Toast.makeText(this, "✅ Shizuku Ready!", Toast.LENGTH_SHORT).show()
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
        checkPermissions()
        setupShizuku()
    }
    
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            blockNetworkImage = false
            blockNetworkLoads = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        
        webView.webViewClient = WebViewClient()
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("WebView", message.message())
                return true
            }
        }
        
        webView.addJavascriptInterface(KyroosShellInterface(this), "KyroosApp")
        webView.loadUrl("file:///android_asset/index.html")
    }
    
    private fun checkPermissions() {
        // Cek Storage Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestManageStoragePermission()
            } else {
                isStoragePermissionGranted = true
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_CODE
                )
            } else {
                isStoragePermissionGranted = true
            }
        }
        
        // Cek Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
    
    private fun requestManageStoragePermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivityForResult(intent, STORAGE_PERMISSION_CODE)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivityForResult(intent, STORAGE_PERMISSION_CODE)
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            isStoragePermissionGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PERMISSION_CODE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isStoragePermissionGranted = Environment.isExternalStorageManager()
        }
    }
    
    private fun setupShizuku() {
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            if (Shizuku.pingBinder()) {
                isShizukuAvailable = true
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
                } else {
                    isShizukuPermissionGranted = true
                }
            }
        } catch (e: Exception) {
            Log.e("Shizuku", "Setup error", e)
        }
    }
    
    // ========== EKSEKUSI SHELL ==========
    
    fun executeShell(command: String, callbackId: String) {
        Thread {
            try {
                val result = when {
                    command.startsWith("wm size") -> getWmSizeFromDisplay()
                    
                    // Semua command dijalankan dengan Runtime
                    else -> executeWithRuntime(command)
                }
                
                runOnUiThread {
                    val escaped = result.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                    webView.evaluateJavascript("window.shellCallback('$callbackId', '$escaped', false)", null)
                }
                
            } catch (e: Exception) {
                runOnUiThread {
                    webView.evaluateJavascript("window.shellCallback('$callbackId', 'Error: ${e.message}', true)", null)
                }
            }
        }.start()
    }
    
    private fun getWmSizeFromDisplay(): String {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        return "Physical size: ${metrics.widthPixels}x${metrics.heightPixels}"
    }
    
    private fun executeWithRuntime(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(command.split(" ").toTypedArray())
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
            
            if (exitCode == 0 || output.isNotEmpty()) {
                output.toString().trim()
            } else {
                error.toString().trim()
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    fun executeShellSync(command: String): String {
        return if (command.startsWith("wm size")) {
            getWmSizeFromDisplay()
        } else {
            executeWithRuntime(command)
        }
    }
    
    fun isShizukuAvailable(): Boolean = isShizukuAvailable && isShizukuPermissionGranted
    fun canAccessSettings(): Boolean = isShizukuAvailable && isShizukuPermissionGranted
    
    fun getShizukuStatus(): String = when {
        !isShizukuAvailable -> "not_installed"
        !isShizukuPermissionGranted -> "no_permission"
        else -> "ready"
    }
    
    fun requestShizukuPermission() {
        if (isShizukuAvailable && !isShizukuPermissionGranted) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
        } else if (!isShizukuAvailable) {
            Toast.makeText(this, "Shizuku tidak berjalan", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun isStorageGranted(): Boolean = isStoragePermissionGranted
}

class KyroosShellInterface(private val activity: MainActivity) {
    @JavascriptInterface fun executeShell(cmd: String, id: String) = activity.executeShell(cmd, id)
    @JavascriptInterface fun executeShellSync(cmd: String): String = activity.executeShellSync(cmd)
    @JavascriptInterface fun isShizukuAvailable(): Boolean = activity.isShizukuAvailable()
    @JavascriptInterface fun canAccessSettings(): Boolean = activity.canAccessSettings()
    @JavascriptInterface fun getShizukuStatus(): String = activity.getShizukuStatus()
    @JavascriptInterface fun requestShizukuPermission() = activity.requestShizukuPermission()
    @JavascriptInterface fun isStorageGranted(): Boolean = activity.isStorageGranted()
}