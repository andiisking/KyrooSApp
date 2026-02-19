package com.kyroos.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
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
    
    private val iconCache = mutableMapOf<String, String>()
    private val labelCache = mutableMapOf<String, String>()
    
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
                
                val scriptDir = File(getExternalFilesDir(null), "scripts")
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
                executeCommand(arrayOf("chmod", "-R", "777", scriptDir.absolutePath))
            } catch (e: Exception) { }
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
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(KyroosShellInterface(this), "KyroosApp")
        
        webView.loadUrl("file:///android_asset/index.html")
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

    fun getScriptDir(): String {
        return File(getExternalFilesDir(null), "scripts").absolutePath
    }

    fun runScript(scriptName: String, args: String, callbackId: String) {
        val scriptPath = "${getScriptDir()}/$scriptName"
        executeShell("sh \"$scriptPath\" $args", callbackId)
    }

    private fun checkWriteSecureSettings() {
        try {
            val result = executeCommand(arrayOf("settings", "get", "global", "angle_gl_driver_selection_pkgs"))
            hasWriteSecureSettings = !result.contains("SecurityException") && !result.contains("Permission Denial")
        } catch (e: Exception) {
            hasWriteSecureSettings = false
        }
    }
    
    private fun grantSelfWriteSecureSettings() {
        Thread {
            try {
                val process = createShizukuProcess(
                    arrayOf("pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS")
                )
                process.waitFor()
                checkWriteSecureSettings()
            } catch (e: Exception) { }
        }.start()
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isStoragePermissionGranted = Environment.isExternalStorageManager()
        } else {
            isStoragePermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
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

    private fun executeCommand(cmdArray: Array<String>): String {
        if (!isShizukuAvailable || !isShizukuPermissionGranted) return "Error: Shizuku not ready"
        return try {
            val process = createShizukuProcess(cmdArray)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (output.isNotEmpty()) output.trim() else error.trim()
        } catch (e: Exception) { "Error: ${e.message}" }
    }
    
    fun executeShellSync(command: String): String = executeCommand(arrayOf("sh", "-c", command))
    fun isShizukuAvailable(): Boolean = isShizukuAvailable && isShizukuPermissionGranted
    fun hasSettingsPermission(): Boolean = hasWriteSecureSettings
    fun getShizukuStatus(): String = if (isShizukuAvailable && isShizukuPermissionGranted) "ready" else "no_permission"
    
    fun requestShizukuPermission() {
        if (isShizukuAvailable) Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
    }
    
    fun getAppIcon(packageName: String): Bitmap? {
        return try {
            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val drawable = applicationInfo.loadIcon(packageManager)
            
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth, 
                drawable.intrinsicHeight, 
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    
    fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }
    
    fun getAppLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        
        val label = try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.split('.').lastOrNull() ?: packageName
        }
        
        labelCache[packageName] = label
        return label
    }
    
    fun getAppLabelsBatch(packages: Array<String>): String {
        val json = JSONObject()
        for (pkg in packages) {
            json.put(pkg, getAppLabel(pkg))
        }
        return json.toString()
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
    
    @JavascriptInterface 
    fun getAppIconBase64(pkg: String): String {
        if (activity.iconCache.containsKey(pkg)) {
            return activity.iconCache[pkg] ?: ""
        }
        
        val bitmap = activity.getAppIcon(pkg)
        val base64 = if (bitmap != null) {
            "data:image/png;base64," + activity.bitmapToBase64(bitmap)
        } else {
            ""
        }
        
        activity.iconCache[pkg] = base64
        return base64
    }
    
    @JavascriptInterface
    fun getAppLabel(pkg: String): String {
        return activity.getAppLabel(pkg)
    }
    
    @JavascriptInterface
    fun getAppLabelsBatch(packages: Array<String>): String {
        return activity.getAppLabelsBatch(packages)
    }
}