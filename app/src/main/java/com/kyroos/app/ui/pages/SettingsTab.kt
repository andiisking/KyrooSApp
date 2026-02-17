package com.kyroos.app.ui.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

// Config Page, Tweaks Page, AppDetail (Di-merge agar tidak kepanjangan scriptnya)
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
                // Konfigurasi Sigma (Baca/Tulis file Config)
                var deep by remember{ mutableStateOf(false) }
                var power by remember{ mutableStateOf(false) }
                var cache by remember{ mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val cfg = AdbManager.shell("cat /storage/emulated/0/kikiros")
                    deep = cfg.contains("deep=on"); power = cfg.contains("power=on"); cache = cfg.contains("chace=on")
                }
                fun save() = scope.launch {
                    AdbManager.shell("echo \"deep=\${if(deep) "on" else "off"}\" > /storage/emulated/0/kikiros && echo \"power=\${if(power) "on" else "off"}\" >> /storage/emulated/0/kikiros && echo \"chace=\${if(cache) "on" else "off"}\" >> /storage/emulated/0/kikiros")
                }
                Column(modifier = Modifier.padding(20.dp)) {
                    SettingItem(Icons.Rounded.Bedtime, "Force Deepsleep", "When screen is off", trailing = { Switch(deep, { deep=it; save() }) })
                    SettingItem(Icons.Rounded.BatterySaver, "Auto Powersave", "Automatic power efficiency", trailing = { Switch(power, { power=it; save() }) })
                    SettingItem(Icons.Rounded.CleaningServices, "Auto Clear Cache", "Background cleaning", trailing = { Switch(cache, { cache=it; save() }) })
                    Text("Changes applied instantly", color = KyOutline, modifier = Modifier.padding(top=20.dp).align(Alignment.CenterHorizontally))
                }
            }
            "tweaks" -> {
                var resW by remember { mutableStateOf("") }
                var resH by remember { mutableStateOf("") }
                var showDialog by remember { mutableStateOf(false) }
                if(showDialog) {
                    AlertDialog(
                        onDismissRequest = { showDialog=false },
                        title = { Text("Are you sure?", color=Color.White) },
                        text = { Text("Running brutal appops may affect notifications.", color=KyOutline) },
                        confirmButton = { Button(onClick={ showDialog=false; onNav("opaps") }) { Text("Run") } },
                        dismissButton = { TextButton(onClick={ showDialog=false }) { Text("Cancel", color=Color.White) } },
                        containerColor = KySurface
                    )
                }
                Column(modifier = Modifier.padding(20.dp)) {
                    SettingItem(Icons.Rounded.Security, "Brutal Appops", "Aggressive background restriction", trailing = { Switch(false, { if(it) showDialog=true }) })
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Custom Resolution", color = KyPrimary, modifier = Modifier.padding(bottom=8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = resW, onValueChange = {resW=it}, label = {Text("Width")}, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = resH, onValueChange = {resH=it}, label = {Text("Height")}, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = { scope.launch{ AdbManager.shell("wm size \${resW}x\${resH}") } }, modifier = Modifier.fillMaxWidth().padding(top=8.dp)) { Text("Apply Resolution") }
                    TextButton(onClick = { scope.launch{ AdbManager.shell("wm size reset") } }, modifier = Modifier.fillMaxWidth()) { Text("Reset to Default") }
                }
            }
            "detail" -> {
                var angle by remember{ mutableStateOf(false) }
                var game by remember{ mutableStateOf(false) }
                var dev by remember{ mutableStateOf(false) }
                var white by remember{ mutableStateOf(false) }
                LaunchedEffect(Unit) { white = AdbManager.shell("cmd deviceidle whitelist | grep $pkg").isNotBlank() }
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Graphics Driver", color=KyPrimary, modifier=Modifier.padding(bottom=8.dp))
                    SettingItem(Icons.Rounded._3dRotation, "Angle Driver", "Force OpenGL ES", trailing = { Switch(angle, { angle=it; if(it){game=false;dev=false} }) })
                    SettingItem(Icons.Rounded.SportsEsports, "Game Driver", "Use Game Driver Overlay", trailing = { Switch(game, { game=it; if(it){angle=false;dev=false} }) })
                    SettingItem(Icons.Rounded.Architecture, "Developer Driver", "Use Prerelease Driver", trailing = { Switch(dev, { dev=it; if(it){angle=false;game=false} }) })
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Power Management", color=KyPrimary, modifier=Modifier.padding(bottom=8.dp))
                    SettingItem(Icons.Rounded.BatteryAlert, "Battery Whitelist", "Device Idle Whitelist", trailing = { Switch(white, { white=it; scope.launch{ AdbManager.shell("cmd deviceidle whitelist \${if(it) "+" else "-"}$pkg") } }) })
                }
            }
            "opaps" -> {
                var showOpapsDialog by remember { mutableStateOf<AppInfo?>(null) }
                if(showOpapsDialog != null) {
                    AlertDialog(
                        onDismissRequest = { showOpapsDialog=null },
                        title = { Text("Apply Opaps?", color=Color.White) },
                        text = { Text("Restrict background activity for:\n${showOpapsDialog!!.packageName}", color=KyOutline) },
                        confirmButton = { Button(onClick={ scope.launch{ AdbManager.shell("nohup opaps ${showOpapsDialog!!.packageName}"); showOpapsDialog=null; onBack() } }) { Text("Apply") } },
                        dismissButton = { TextButton(onClick={ showOpapsDialog=null }) { Text("Cancel", color=Color.White) } },
                        containerColor = KySurface
                    )
                }
                AppsListScreen(isThirdPartyOnly = true) { showOpapsDialog = it }
            }
        }
    }
}
