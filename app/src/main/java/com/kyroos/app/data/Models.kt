package com.kyroos.app.data
import androidx.compose.ui.graphics.Color

val KyBg = Color(0xFF1A120E)
val KySurface = Color(0xFF271E1A)
val KySurfaceHigh = Color(0xFF322824)
val KyPrimary = Color(0xFFFFB596)
val KyPrimaryContainer = Color(0xFF723523)
val KyOutline = Color(0xFFA08D85)

data class AppInfo(val packageName: String, val label: String = "")
