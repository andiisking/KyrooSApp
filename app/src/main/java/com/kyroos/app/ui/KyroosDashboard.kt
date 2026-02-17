package com.kyroos.app.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kyroos.app.data.*
import com.kyroos.app.ui.components.KyroosTopBar
import com.kyroos.app.ui.pages.*

@Composable
fun KyroosDashboard() {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("kyroos_prefs", Context.MODE_PRIVATE)
    var activeTab by remember { mutableStateOf("home") }
    var subPage by remember { mutableStateOf<String?>(null) }
    var selectedPkg by remember { mutableStateOf("") }
    
    // Listen to SharedPreferences changes for tab visibility
    var showAppsTab by remember { mutableStateOf(prefs.getBoolean("advAppConfig", false)) }
    LaunchedEffect(activeTab) { showAppsTab = prefs.getBoolean("advAppConfig", false) }

    Scaffold(
        containerColor = KyBg,
        topBar = { if(subPage == null) KyroosTopBar("KyrooS", Icons.Rounded.Forest) },
        bottomBar = {
            if (subPage == null) {
                NavigationBar(containerColor = KySurfaceHigh) {
                    NavigationBarItem(selected = activeTab == "home", onClick = { activeTab = "home" }, icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") }, colors = NavigationBarItemDefaults.colors(indicatorColor = KySecondaryContainer, selectedIconColor = KyOnSecondaryContainer, selectedTextColor = KyOnBg()))
                    if (showAppsTab) NavigationBarItem(selected = activeTab == "apps", onClick = { activeTab = "apps" }, icon = { Icon(Icons.Rounded.Apps, null) }, label = { Text("Apps") }, colors = NavigationBarItemDefaults.colors(indicatorColor = KySecondaryContainer, selectedIconColor = KyOnSecondaryContainer, selectedTextColor = KyOnBg()))
                    NavigationBarItem(selected = activeTab == "settings", onClick = { activeTab = "settings" }, icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("Config") }, colors = NavigationBarItemDefaults.colors(indicatorColor = KySecondaryContainer, selectedIconColor = KyOnSecondaryContainer, selectedTextColor = KyOnBg()))
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(KyBg)) {
            when(activeTab) {
                "home" -> HomeTab()
                "apps" -> AppsListScreen { selectedPkg = it.packageName; subPage = "detail" }
                "settings" -> SettingsTab(prefs) { subPage = it }
            }
        }
        AnimatedVisibility(visible = subPage != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            subPage?.let { page -> KyroosSubPages(page, selectedPkg, onBack = { subPage = null }, onNav = { subPage = it }) }
        }
    }
}
@Composable fun KyOnBg() = Color(0xFFEFE0DA)
