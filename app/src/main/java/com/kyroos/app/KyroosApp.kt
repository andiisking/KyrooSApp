package com.kyroos.app

import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.Shell.Initializer

class KyroosApp : Application() {
    
    companion object {
        lateinit var instance: KyroosApp
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Setup Shell untuk menggunakan Shizuku - CARA BARU untuk libsu 5.2.0
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create()
            .setFlags(Shell.FLAG_USE_SHIZUKU)  // FLAG_USE_SHIZUKU tersedia di sini
            .setInitializers(KyroosInitializer::class.java)
        )
    }
    
    class KyroosInitializer : Initializer() {
        override fun onInit(context: Context, shell: Shell): Boolean {
            // Inisialisasi environment variables jika diperlukan
            shell.newJob()
                .add("export PATH=\$PATH:/system/bin:/system/xbin")
                .add("export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:/system/lib64:/system/lib")
                .exec()
            return true
        }
    }
}