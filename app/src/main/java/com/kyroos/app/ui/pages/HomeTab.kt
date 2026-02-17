package com.kyroos.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.kyroos.app.AdbManager
import com.kyroos.app.data.*

@Composable
fun HomeTab() {
    var isActive by remember { mutableStateOf(false) }
    var soc by remember { mutableStateOf("Scanning...") }
    var ram by remember { mutableStateOf("Calculating...") }
    var kernel by remember { mutableStateOf("Checking...") }

    LaunchedEffect(Unit) {
        isActive = AdbManager.shell("pgrep -f sigma").isNotBlank()
        soc = AdbManager.shell("getprop ro.board.platform").trim().takeIf { it.isNotBlank() } ?: "Unknown"
        kernel = AdbManager.shell("uname -r").trim()
        val mem = AdbManager.shell("cat /proc/meminfo | grep MemTotal")
        val match = Regex("(\\d+)").find(mem)
        if (match != null) {
            val gb = match.groupValues[1].toFloat() / 1024 / 1024
            ram = String.format("%.1f GB", gb)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // Hero Card
        Card(
            colors = CardDefaults.cardColors(containerColor = if(isActive) KyPrimaryContainer else KySurfaceHigh),
            shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if(isActive) Icons.Rounded.Verified else Icons.Rounded.HourglassEmpty, null, 
                    tint = if(isActive) KyOnPrimaryContainer else KyOutline, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(20.dp))
                Column {
                    Text(if(isActive) "Kyroos Active" else "Service Idle", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(if(isActive) "Sigma binary running" else "Verifying service status", fontSize = 14.sp, color = Color.White.copy(0.7f))
                }
            }
        }
        // Info Card
        Card(colors = CardDefaults.cardColors(containerColor = KySurface), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(Icons.Rounded.Extension, "Module Version", "8.0")
                InfoRow(Icons.Rounded.DeveloperBoard, "SoC Platform", soc)
                InfoRow(Icons.Rounded.Memory, "RAM Capacity", ram)
                InfoRow(Icons.Rounded.Terminal, "Kernel", kernel)
            }
        }
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(48.dp).background(KySurfaceHigh, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = KyPrimary)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, fontSize = 11.sp, color = KyOutline)
            Text(value, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}
