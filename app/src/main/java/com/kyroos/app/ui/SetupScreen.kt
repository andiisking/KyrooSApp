package com.kyroos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SetupScreen(onStartPairing: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A120E)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("KyrooS ADB", fontSize = 32.sp, color = Color.White)
        Text("Silakan lakukan pairing ADB", color = Color.Gray)
        Spacer(modifier = Modifier.height(40.dp))
        Button(
            onClick = onStartPairing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB596))
        ) {
            Text("Cari Layanan Pairing", color = Color(0xFF1A120E))
        }
    }
}
