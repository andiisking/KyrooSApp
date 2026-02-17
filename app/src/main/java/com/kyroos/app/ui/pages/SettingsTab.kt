package com.kyroos.app.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyroos.app.AdbManager
import com.kyroos.app.data.*
import com.kyroos.app.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun SettingsTab(prefs: android.content.SharedPreferences, onNav: (String) -> Unit) {
    var sigmaActive by remember { mutableStateOf(false) }
    var appConfigActive by remember { mutableStateOf(prefs.getBoolean("advAppConfig", false)) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { sigmaActive = AdbManager.shell("pgrep -f sigma").isNotBlank() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        SettingItem(Icons.Rounded.RocketLaunch, "Sigma Service", "Main module binary", trailing = {
            Switch(checked = sigmaActive, onCheckedChange = { 
                sigmaActive = it
                scope.launch {
                    if(it) AdbManager.shell("nohup sigma > /dev/null 2>&1 &") else AdbManager.shell("pkill -f sigma")
                }
            }, colors = SwitchDefaults.colors(checkedTrackColor = KyPrimary))
        })
        SettingItem(Icons.Rounded.Apps, "Advanced App Config", "Enable App Manager", trailing = {
            Switch(checked = appConfigActive, onCheckedChange = { 
                appConfigActive = it; prefs.edit().putBoolean("advAppConfig", it).apply()
            }, colors = SwitchDefaults.colors(checkedTrackColor = KyPrimary))
        })
        SettingItem(Icons.Rounded.Build, "Additional Tweaks", "Advanced Optimization", onClick = { onNav("tweaks") }, trailing = { Icon(Icons.Rounded.ArrowForwardIos, null, tint=KyOutline) })
        SettingItem(Icons.Rounded.Tune, "Sigma Configuration", "Behavior & power tweaks", onClick = { onNav("config") }, trailing = { Icon(Icons.Rounded.ArrowForwardIos, null, tint=KyOutline) })
        SettingItem(Icons.Rounded.Code, "Developer", "@koneko_dev", onClick = { 
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Koneko_dev")))
        }, trailing = { Icon(Icons.Rounded.OpenInNew, null, tint=KyOutline) })
    }
}

@Composable
fun KyroosSubPages(page: String, pkg: String, onBack: () -> Unit, onNav: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().background(KyBg)) {
        KyroosTopBar(
            title = when(page) { "config"->"Configuration"; "tweaks"->"Additional Tweaks"; "detail"->"App Settings"; "opaps"->"Select App (Opaps)"; else->"" },
            icon = Icons.Rounded.ArrowBack, onBack = onBack
        )
        when(page) {
            "config" -> {
                var deep by remember{ mutableStateOf(false) }
                var power by remember{ mutableStateOf(false) }
                var cache by remember{ mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val cfg = AdbManager.shell("cat /storage/emulated/0/kikiros")
                    deep = cfg.contains("deep=on"); power = cfg.contains("power=on"); cache = cfg.contains("chace=on")
                }
                fun save() = scope.launch {
                    val d = if(deep) "on" else "off"
                    val p = if(power) "on" else "off"
                    val c = if(cache) "on" else "off"
                    AdbManager.shell("echo \"deep=$d\" > /storage/emulated/0/kikiros && echo \"power=$p\" >> /storage/emulated/0/kikiros && echo \"chace=$c\" >> /storage/emulated/0/kikiros")
                }
                Column(modifier = Modifier.padding(20.dp)) {
                    SettingItem(Icons.Rounded.Bedtime, "Force Deepsleep", "When screen is off", trailing = { Switch(deep, { deep=it; save() }) })
                    SettingItem(Icons.Rounded.BatterySaver, "Auto Powersave", "Automatic power efficiency", trailing = { Switch(power, { power=it; save() }) })
                    SettingItem(Icons.Rounded.CleaningServices, "Auto Clear Cache", "Background cleaning", trailing = { Switch(cache, { cache=it; save() }) })
                }
            }
            "tweaks" -> {
                var resW by remember { mutableStateOf("") }
                var resH by remember { mutableStateOf("") }
                Column(modifier = Modifier.padding(20.dp)) {
                    SettingItem(Icons.Rounded.Security, "Brutal Appops", "Aggressive background restriction", onClick = { onNav("opaps") })
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = resW, onValueChange = {resW=it}, label = {Text("Width", color = Color.White)}, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = resH, onValueChange = {resH=it}, label = {Text("Height", color = Color.White)}, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = { scope.launch{ AdbManager.shell("wm size ${resW}x${resH}") } }, modifier = Modifier.fillMaxWidth().padding(top=8.dp)) { Text("Apply Resolution") }
                    TextButton(onClick = { scope.launch{ AdbManager.shell("wm size reset") } }, modifier = Modifier.fillMaxWidth()) { Text("Reset") }
                }
            }
            "detail" -> {
                var white by remember{ mutableStateOf(false) }
                LaunchedEffect(Unit) { white = AdbManager.shell("cmd deviceidle whitelist | grep $pkg").isNotBlank() }
                Column(modifier = Modifier.padding(20.dp)) {
                    SettingItem(Icons.Rounded.BatteryAlert, "Battery Whitelist", "Device Idle Whitelist", trailing = { Switch(white, { white=it; scope.launch{ AdbManager.shell("cmd deviceidle whitelist ${if(it) "+" else "-"}$pkg") } }) })
                }
            }
            "opaps" -> {
                AppsListScreen(isThirdPartyOnly = true) { app ->
                    scope.launch { AdbManager.shell("nohup opaps ${app.packageName}"); onBack() }
                }
            }
        }
    }
}
