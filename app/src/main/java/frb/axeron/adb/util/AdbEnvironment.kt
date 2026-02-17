package frb.axeron.adb.util

import android.os.Build
import android.os.SystemProperties
import android.content.Context // Tambahkan context untuk baca prefs
import androidx.annotation.ChecksSdkIntAtLeast

object AdbEnvironment {
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isTlsSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    // Ganti AxeronSettings dengan pengecekan manual atau prefs KyrooS
    fun isWifiRequired(context: Context): Boolean {
        val prefs = context.getSharedPreferences("kyroos_prefs", Context.MODE_PRIVATE)
        val tcpMode = prefs.getBoolean("tcp_mode", false)
        return (getAdbTcpPort() <= 0 || !tcpMode)
    }

    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port <= 0) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        
        // Jika port tetap -1, default biasanya 5555 untuk non-TLS
        if (port <= 0 && !isTlsSupported()) port = 5555 
        return port
    }
}
