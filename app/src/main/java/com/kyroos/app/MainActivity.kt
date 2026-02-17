package com.kyroos.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.kyroos.app.ui.SetupScreen
import com.kyroos.app.ui.KyroosDashboard
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("kyroos_prefs", MODE_PRIVATE)
        setContent {
            var paired by remember { mutableStateOf(prefs.getBoolean("paired", false)) }
            val scope = rememberCoroutineScope()
            if (paired) {
                LaunchedEffect(Unit) { AdbManager.connect(this@MainActivity) }
                KyroosDashboard()
            } else {
                SetupScreen { port, code ->
                    scope.launch {
                        if (AdbManager.pair(this@MainActivity, port, code)) {
                            prefs.edit().putBoolean("paired", true).apply()
                            paired = true
                        } else {
                            Toast.makeText(this@MainActivity, "Gagal! Periksa Port/Kode", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
