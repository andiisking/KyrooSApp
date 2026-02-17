package frb.axeron.adb.util

import android.os.Build
import android.content.Context
import androidx.annotation.ChecksSdkIntAtLeast

object AdbEnvironment {
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isTlsSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isWifiRequired(context: Context): Boolean {
        val prefs = context.getSharedPreferences("kyroos_prefs", Context.MODE_PRIVATE)
        val tcpMode = prefs.getBoolean("tcp_mode", false)
        return (getAdbTcpPort() <= 0 || !tcpMode)
    }

    fun getAdbTcpPort(): Int {
        var port = getSystemPropertyInt("service.adb.tcp.port", -1)
        if (port <= 0) port = getSystemPropertyInt("persist.adb.tcp.port", -1)
        if (port <= 0 && !isTlsSupported()) port = 5555 
        return port
    }

    // Fungsi pembantu menggunakan Reflection
    private fun getSystemPropertyInt(key: String, def: Int): Int {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("getInt", String::class.java, Int::class.java)
            method.invoke(null, key, def) as Int
        } catch (e: Exception) {
            def
        }
    }
}
