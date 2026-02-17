package com.kyroos.app.ui.pages

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyroos.app.AdbManager
import com.kyroos.app.data.*
import kotlinx.coroutines.launch

@Composable
fun AppsListScreen(isThirdPartyOnly: Boolean = false, onAppClick: (AppInfo) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (apps.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if(isThirdPartyOnly) Icons.Rounded.Security else Icons.Rounded.Apps, null, tint = KyOutline, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        isScanning = true
                        scope.launch {
                            val cmd = if(isThirdPartyOnly) "pm list packages -3 -u" else "pm list packages -u"
                            val raw = AdbManager.shell(cmd)
                            val pm = ctx.packageManager
                            apps = raw.lines()
                                .filter { it.startsWith("package:") && !it.contains("overlay") }
                                .map { pkgLine -> 
                                    val pkg = pkgLine.removePrefix("package:")
                                    val label = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (e:Exception) { pkg }
                                    AppInfo(pkg, label)
                                }.sortedBy { it.label.lowercase() }
                            isScanning = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KyPrimaryContainer)
                ) { Text(if(isScanning) "Scanning..." else "Scan Apps", color = KyOnPrimaryContainer) }
            }
        } else {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                placeholder = { Text("Search apps...") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(50.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KyPrimary, unfocusedBorderColor = KySurfaceHigh)
            )
            val filtered = apps.filter { it.packageName.contains(search, true) || it.label.contains(search, true) }
            Text("${filtered.size} apps found", fontSize = 12.sp, color = KyOutline, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            LazyColumn {
                items(filtered) { app ->
                    Card(
                        onClick = { onAppClick(app) },
                        colors = CardDefaults.cardColors(containerColor = KySurface),
                        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(48.dp).background(KySurfaceHigh, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Android, null, tint = KyPrimary)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, color = Color.White, fontSize = 16.sp)
                                Text(app.packageName, color = KyOutline, fontSize = 12.sp)
                            }
                            if(isThirdPartyOnly) Icon(Icons.Rounded.PlayCircle, null, tint = KyOutline)
                        }
                    }
                }
            }
        }
    }
}
