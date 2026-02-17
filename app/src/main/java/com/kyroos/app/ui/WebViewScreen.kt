package com.kyroos.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kyroos.app.AdbManager
import kotlinx.coroutines.runBlocking

class WebAppInterface(private val context: Context) {
    // Fungsi ini bisa dipanggil dari JavaScript!
    @JavascriptInterface
    fun execShell(cmd: String): String {
        return runBlocking {
            AdbManager.shell(cmd)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen() {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                
                // Menempelkan bridge Kotlin ke JS dengan nama "AndroidBridge"
                addJavascriptInterface(WebAppInterface(context), "AndroidBridge")
                
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                
                // Memanggil file index.html dari folder assets
                loadUrl("file:///android_asset/index.html")
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
