package frb.axeron.adb.util

import android.os.Build
import android.os.SystemProperties
import androidx.annotation.ChecksSdkIntAtLeast

object AdbEnvironment {
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isTlsSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    fun getAdbTcpPort(): Int {
        // Mengambil port dari properti sistem Android murni
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port <= 0) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        return port
    }
}
