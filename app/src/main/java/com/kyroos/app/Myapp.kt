package com.kyroos.app

import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.Shell.Initializer

class KyroosApp : Application() {
    
    companion object {
        // Static agar bisa diakses dari mana saja
        lateinit var instance: KyroosApp
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Setup Shell untuk menggunakan Shizuku
        Shell.Config.setFlags(Shell.FLAG_USE_SHIZUKU)
        Shell.Config.setInitializers(KyroosInitializer::class.java)
        
        // Set timeout untuk shell (10 detik)
        Shell.Config.setTimeout(10)
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