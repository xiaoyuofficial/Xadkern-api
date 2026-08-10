package dev.frb.demo_xadkern

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import xadkern.Xadkern

class Application : android.app.Application() {

    init {
        Xadkern.initialize(BuildConfig.APPLICATION_ID)
        Log.d("Xadkern","Xadkern initialized: ${Xadkern.axrun_path.absolutePath}")
        Shell.enableLegacyStderrRedirection = true
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().run {
            setCommands("sh", Xadkern.axrun_path.absolutePath)
        })
    }

//    override fun attachBaseContext(base: Context?) {
//        super.attachBaseContext(base)
//        Xadkern.initialize(this)
//        Log.d("Xadkern","Xadkern initialized: ${Xadkern.axerish_path.absolutePath}")
//        Shell.enableLegacyStderrRedirection = true
//        Shell.enableVerboseLogging = BuildConfig.DEBUG
//        Shell.setDefaultBuilder(Shell.Builder.create().run {
//            setCommands("sh", Xadkern.axerish_path.absolutePath)
//        })
//    }
}