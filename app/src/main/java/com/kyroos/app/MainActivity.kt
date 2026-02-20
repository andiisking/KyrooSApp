/*
 * Copyright 2024 andiisking
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Credits:
 * This app uses Shizuku API (https://github.com/RikkaApps/Shizuku)
 * by RikkaApps. Licensed under custom terms (Apache-2.0 with additional
 * redistribution restrictions).
 */
package com.kyroos.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    
    internal lateinit var webView: WebView
    internal lateinit var context: Context
    
    internal val SHIZUKU_PERMISSION_CODE = 1001
    internal val STORAGE_PERMISSION_CODE = 1002
    
    internal var isShizukuAvailable = false
    internal var isShizukuPermissionGranted = false
    internal var isStoragePermissionGranted = false
    internal var hasWriteSecureSettings = false
    
    internal val labelCache = LruCache<String, String>(100)
    
    internal val iconCache = object : LruCache<String, String>(5 * 1024 * 1024) {
        override fun sizeOf(key: String, value: String): Int {
            return value.length
        }
    }
    
    private val executor = Executors.newFixedThreadPool(3)
    private var isPreloading = false
    
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
    
    override fun onResume() {
        super.onResume()
        if (webView != null) {
            webView.evaluateJavascript("if(typeof refreshConfig !== 'undefined') refreshConfig();", null)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }
    
    private fun copyAssets() {
        executor.execute {
            try {
                val assetManager = assets
                val files = assetManager.list("scripts") ?: return@execute
                
                val scriptDir = File(getExternalFilesDir(null), "scripts")
                if (!scriptDir.exists()) scriptDir.mkdirs()

                for (filename in files) {
                    val outFile = File(scriptDir, filename)
                    if (outFile.exists() && outFile.length() > 0) continue
                    
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
        }
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
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                
                if (url.startsWith("app-icon://")) {
                    val packageName = url.substringAfter("app-icon://")
                    return getIconWebResource(packageName)
                }
                return super.shouldInterceptRequest(view, request)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                startPreloading()
            }
        }
        
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(KyroosShellInterface(this), "KyroosApp")
        
        webView.loadUrl("file:///android_asset/index.html")
    }
    
    private fun getIconWebResource(packageName: String): WebResourceResponse? {
        return try {
            val base64 = getAppIconBase64(packageName)
            if (base64.isNotEmpty()) {
                val base64Data = base64.substringAfter(",")
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val inputStream = ByteArrayInputStream(imageBytes)
                WebResourceResponse("image/webp", "UTF-8", inputStream)
            } else {
                null
            }
        } catch (e: Exception) {
            null
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
        executor.execute {
            try {
                val process = createShizukuProcess(
                    arrayOf("pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS")
                )
                process.waitFor()
                checkWriteSecureSettings()
            } catch (e: Exception) { }
        }
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
        executor.execute {
            try {
                val result = executeCommand(arrayOf("sh", "-c", command))
                runOnUiThread {
                    val escaped = result.replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\\", "\\\\")
                    webView.evaluateJavascript("window.shellCallback('$callbackId', '$escaped', false)", null)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    webView.evaluateJavascript("window.shellCallback('$callbackId', 'Error: ${e.message}', true)", null)
                }
            }
        }
    }

    private fun executeCommand(cmdArray: Array<String>): String {
        if (!isShizukuAvailable || !isShizukuPermissionGranted) return "Error: Shizuku not ready"
        return try {
            val process = createShizukuProcess(cmdArray)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (output.isNotEmpty()) output.trim() else error.trim()
        } catch (e: Exception) { 
            "Error: ${e.message}"
        }
    }
    
    fun executeShellSync(command: String): String = executeCommand(arrayOf("sh", "-c", command))
    fun isShizukuAvailable(): Boolean = isShizukuAvailable && isShizukuPermissionGranted
    fun hasSettingsPermission(): Boolean = hasWriteSecureSettings
    fun getShizukuStatus(): String = if (isShizukuAvailable && isShizukuPermissionGranted) "ready" else "no_permission"
    
    fun requestShizukuPermission() {
        if (isShizukuAvailable) Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
    }
    
    fun getAppLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        
        val label = try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.split('.').lastOrNull() ?: packageName
        }
        
        labelCache.put(packageName, label)
        return label
    }
    
    fun getAppIconBase64(packageName: String): String {
        iconCache[packageName]?.let { return it }

        val base64 = try {
            val packageManager = packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val drawable = appInfo.loadIcon(packageManager)

            val bitmap = drawableToBitmap(drawable, 64)

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP, 75, stream)
            
            val byteArray = stream.toByteArray()
            val base64String = "data:image/webp;base64," + 
                Base64.encodeToString(byteArray, Base64.NO_WRAP)
            
            bitmap.recycle()
            
            base64String
        } catch (e: Exception) {
            ""
        }

        if (base64.isNotEmpty()) {
            iconCache.put(packageName, base64)
        }
        
        return base64
    }
    
    private fun drawableToBitmap(drawable: Drawable, size: Int = 64): Bitmap {
        return when (drawable) {
            is BitmapDrawable -> {
                val original = drawable.bitmap
                if (original.width > size || original.height > size) {
                    Bitmap.createScaledBitmap(original, size, size, true)
                } else {
                    original
                }
            }
            is VectorDrawable -> {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
            else -> {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
        }
    }
    
    @JavascriptInterface
    fun getAppIconsBatch(packages: Array<String>): String {
        val json = JSONObject()
        
        packages.forEach { pkg ->
            var icon = iconCache[pkg]
            
            if (icon == null) {
                icon = getAppIconBase64(pkg)
            }
            
            json.put(pkg, icon ?: "")
        }
        
        return json.toString()
    }
    
    @JavascriptInterface
    fun getAppInfoBatch(packages: Array<String>): String {
        val json = JSONObject()
        
        packages.forEach { pkg ->
            val info = JSONObject()
            info.put("label", getAppLabel(pkg))
            info.put("icon", iconCache[pkg] ?: getAppIconBase64(pkg))
            json.put(pkg, info)
        }
        
        return json.toString()
    }
    
    private fun startPreloading() {
        if (isPreloading) return
        isPreloading = true
        
        webView.evaluateJavascript("Array.from(document.querySelectorAll('.app-card-pkg')).map(el => el.textContent)", null)
    }
    
    @JavascriptInterface
    fun preloadIcons(packages: Array<String>) {
        executor.execute {
            packages.take(30).forEach { pkg ->
                if (iconCache[pkg] == null) {
                    getAppIconBase64(pkg)
                }
            }
        }
    }
    
    fun trimCache() {
        if (iconCache.size() > 50) {
        }
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
    fun readKyroosConfig(): String {
        return try {
            val file = File(activity.getScriptDir(), "kyroos.conf")
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }
    
    
    @JavascriptInterface
    fun getAppLabel(pkg: String): String {
        return activity.getAppLabel(pkg)
    }
    
    @JavascriptInterface
    fun getAppIconBase64(pkg: String): String {
        return activity.getAppIconBase64(pkg)
    }
    
    @JavascriptInterface
    fun getAppIconsBatch(packages: Array<String>): String {
        return activity.getAppIconsBatch(packages)
    }
    
    @JavascriptInterface
    fun getAppInfoBatch(packages: Array<String>): String {
        return activity.getAppInfoBatch(packages)
    }
    
    @JavascriptInterface
    fun getAppLabelsBatch(packages: Array<String>): String {
        return activity.getAppLabelsBatch(packages)
    }
    
    @JavascriptInterface
    fun preloadIcons(packages: Array<String>) {
        activity.preloadIcons(packages)
    }
}