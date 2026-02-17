package com.kyroos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyroos.app.data.*

@Composable
fun KyroosTopBar(title: String, icon: ImageVector, onBack: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 8.dp)) {
                Icon(androidx.compose.material.icons.Icons.Rounded.ArrowBack, null, tint = Color.White)
            }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White)
        } else {
            Icon(icon, null, tint = KyPrimary, modifier = Modifier.size(34.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun SettingItem(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Card(
        onClick = onClick ?: {}, enabled = onClick != null,
        colors = CardDefaults.cardColors(containerColor = KySurface),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = KyOutline, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, color = Color.White)
                Text(subtitle, fontSize = 14.sp, color = KyOutline)
            }
            if (trailing != null) trailing()
        }
    }
}
