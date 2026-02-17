package com.kyroos.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forest
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import com.kyroos.app.data.*

@Composable
fun SetupScreen(onStartPairing: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(KyBg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Forest, null, tint = KyPrimary, modifier = Modifier.size(100.dp))
        Spacer(modifier = Modifier.height(20.dp))
        Text("KyrooS ADB", fontSize = 32.sp, color = Color.White)
        Text("Gunakan fitur notifikasi untuk pairing", color = KyOutline)
        
        Spacer(modifier = Modifier.height(40.dp))
        
        Button(
            onClick = onStartPairing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KyPrimary)
        ) {
            Text("Cari Layanan Pairing", color = KyBg)
        }
    }
}
