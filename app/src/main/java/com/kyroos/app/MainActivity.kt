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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    
    internal lateinit var webView: WebView
    internal lateinit var context: Context
    
    internal val SHIZUKU_PERMISSION_CODE = 1001
    internal val STORAGE_PERMISSION_CODE = 1002
    
    internal var isShizukuAvailable = false
    internal var isShizukuPermissionGranted = false
    internal var isStoragePermissionGranted = false
    internal var hasWriteSecureSettings = false
    
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_CODE) {
            isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                webView.evaluateJavascript("if(window.onShizukuPermissionChanged) window.onShizukuPermissionChanged($isShizukuPermissionGranted);", null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context = this
        
        webView = WebView(this)
        setContentView(webView)
        
        setupWebView()
        checkPermissions()
        copyAssets()
        
        isShizukuAvailable = isShizukuInstalled()
        if (isShizukuAvailable) {
            isShizukuPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(KyroosShellInterface(this), "KyroosApp")
    }

    private fun checkPermissions() {
        isStoragePermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        
        hasWriteSecureSettings = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun copyAssets() {
        Thread {
            try {
                val assetManager = assets
                val files = assetManager.list("scripts") ?: return@Thread
                val targetDir = File(getScriptDir())
                
                if (!targetDir.exists()) targetDir.mkdirs()

                for (filename in files) {
                    val outFile = File(targetDir, filename)
                    assetManager.open("scripts/$filename").use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    outFile.setExecutable(true, false)
                    outFile.setReadable(true, false)
                    outFile.setWritable(true, false)
                }
                executeShellSync("chmod -R 777 ${targetDir.absolutePath}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun getScriptDir(): String {
        val externalDir = context.getExternalFilesDir(null)
        val scriptsDir = File(externalDir, "scripts")
        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        return scriptsDir.absolutePath
    }

    fun executeShell(cmd: String, callbackId: String) {
        if (!isShizukuPermissionGranted) {
            webView.post { webView.evaluateJavascript("window.shellCallbacks['$callbackId'].reject('Shizuku permission not granted');", null) }
            return
        }

        Thread {
            try {
                val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val output = StringBuilder()
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                while (errorReader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                
                process.waitFor()
                val result = output.toString().trim()
                webView.post { webView.evaluateJavascript("window.shellCallbacks['$callbackId'].resolve(`${result.replace("`", "\\`")}`);", null) }
            } catch (e: Exception) {
                webView.post { webView.evaluateJavascript("window.shellCallbacks['$callbackId'].reject(`${e.message}`);", null) }
            }
        }.start()
    }

    fun runScript(name: String, args: String, callbackId: String) {
        val scriptPath = File(getScriptDir(), name).absolutePath
        val fullCmd = "sh $scriptPath $args"
        executeShell(fullCmd, callbackId)
    }

    fun executeShellSync(cmd: String): String {
        if (!isShizukuPermissionGranted) return "Error: Shizuku permission not granted"
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasSettingsPermission(): Boolean = hasWriteSecureSettings
    
    fun getShizukuStatus(): String = when {
        !isShizukuAvailable -> "not_installed"
        !isShizukuPermissionGranted -> "no_permission"
        else -> "ready"
    }
    
    fun requestShizukuPermission() {
        if (isShizukuAvailable && !isShizukuPermissionGranted) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
        } else if (!isShizukuAvailable) {
            Toast.makeText(this, "Shizuku not running", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun isStorageGranted(): Boolean = isStoragePermissionGranted

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }
}

class KyroosShellInterface(private val activity: MainActivity) {
    @JavascriptInterface fun executeShell(cmd: String, id: String) = activity.executeShell(cmd, id)
    @JavascriptInterface fun runScript(name: String, args: String, id: String) = activity.runScript(name, args, id)
    @JavascriptInterface fun getScriptDir(): String = activity.getScriptDir()
    @JavascriptInterface fun executeShellSync(cmd: String): String = activity.executeShellSync(cmd)
    @JavascriptInterface fun isShizukuAvailable(): Boolean = activity.isShizukuAvailable()
    @JavascriptInterface fun hasSettingsPermission(): Boolean = activity.hasSettingsPermission()
    @JavascriptInterface fun getShizukuStatus(): String = activity.getShizukuStatus()
    @JavascriptInterface fun requestShizukuPermission() = activity.requestShizukuPermission()
    @JavascriptInterface fun isStorageGranted(): Boolean = activity.isStorageGranted()
}
