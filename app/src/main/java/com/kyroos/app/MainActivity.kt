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
                if (isShizukuPermissionGranted) {
                    Toast.makeText(this, "Shizuku Ready", Toast.LENGTH_SHORT).show()
                    grantSelfWriteSecureSettings()
                } else {
                    Toast.makeText(this, "Shizuku Denied", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        context = this
        webView = findViewById(R.id.webView)
        
        copyAssets()
        setupWebView()
        checkPermissions()
        setupShizuku()
        checkWriteSecureSettings()
    }
    
    private fun copyAssets() {
        Thread {
            try {
                val assetManager = assets
                val files = assetManager.list("scripts") ?: return@Thread
                
                val scriptDir = File(filesDir, "scripts")
                if (!scriptDir.exists()) scriptDir.mkdirs()

                for (filename in files) {
                    val outFile = File(scriptDir, filename)
                    
                    val inStream = assetManager.open("scripts/$filename")
                    val outStream = FileOutputStream(outFile)
                    inStream.copyTo(outStream)
                    inStream.close()
                    outStream.flush()
                    outStream.close()
                    
                    outFile.setExecutable(true, false)
                    outFile.setReadable(true, false)
                    outFile.setWritable(true, false)
                }
            } catch (e: Exception) { 
            }
        }.start()
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
        webView.webChromeClient = WebChromeClient()
        
        webView.addJavascriptInterface(KyroosShellInterface(this), "KyroosApp")
        webView.loadUrl("file:///android_asset/index.html")
    }
    
    private fun checkWriteSecureSettings() {
        try {
            val result = executeCommand(arrayOf("settings", "get", "global", "angle_gl_driver_selection_pkgs"))
            hasWriteSecureSettings = !result.contains("SecurityException") && !result.contains("Permission Denial")
        } catch (e: Exception) {
            hasWriteSecureSettings = false
        }
    }

    private fun createShizukuProcess(cmdArray: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmdArray, null, null) as Process
    }
    
    private fun grantSelfWriteSecureSettings() {
        Thread {
            try {
                val process = createShizukuProcess(
                    arrayOf("pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS")
                )
                
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val error = errorReader.readText()
                val exitCode = process.waitFor()
                process.destroy()
                
                if (exitCode == 0 && error.isEmpty()) {
                    hasWriteSecureSettings = true
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "WRITE_SECURE_SETTINGS granted", Toast.LENGTH_SHORT).show()
                    }
                }
                
                checkWriteSecureSettings()
            } catch (e: Exception) { }
        }.start()
    }
    
    private fun checkPermissions() {
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
                    grantSelfWriteSecureSettings()
                }
            }
        } catch (e: Exception) { }
    }
    
    fun executeShell(command: String, callbackId: String) {
        Thread {
            try {
                val result = executeCommand(arrayOf("sh", "-c", command))
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

    fun runScript(scriptName: String, args: String, callbackId: String) {
        val scriptPath = "${context.filesDir.path}/scripts/$scriptName"
        executeShell("sh \"$scriptPath\" $args", callbackId)
    }
    
    fun getScriptDir(): String {
        return "${context.filesDir.path}/scripts"
    }
    
    private fun executeCommand(cmdArray: Array<String>): String {
        if (!isShizukuAvailable || !isShizukuPermissionGranted) {
            return "Error: Shizuku not ready"
        }
        
        return try {
            val process = createShizukuProcess(cmdArray)
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
            process.destroy()
            
            if (exitCode == 0 || output.isNotEmpty()) {
                output.toString().trim()
            } else {
                error.toString().trim()
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    fun executeShellSync(command: String): String = executeCommand(arrayOf("sh", "-c", command))
    fun isShizukuAvailable(): Boolean = isShizukuAvailable && isShizukuPermissionGranted
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
