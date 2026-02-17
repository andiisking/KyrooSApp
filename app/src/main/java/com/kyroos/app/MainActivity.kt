package com.kyroos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.kyroos.app.ui.SetupScreen
import com.kyroos.app.ui.KyroosDashboard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("kyroos_prefs", MODE_PRIVATE)
        setContent {
            var paired by remember { mutableStateOf(prefs.getBoolean("paired", false)) }
            if (paired) {
                LaunchedEffect(Unit) { AdbManager.connect(this@MainActivity) }
                KyroosDashboard()
            } else {
                SetupScreen { AdbManager.startPairingService(this@MainActivity) }
            }
        }
    }
}
