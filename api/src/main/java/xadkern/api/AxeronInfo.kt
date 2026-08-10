package xadkern.api

import android.os.Parcelable
import xadkern.server.ServerInfo
import xadkern.shared.XadkernApiConstant
import kotlinx.parcelize.Parcelize

@Parcelize
data class XadkernInfo(
    val serverInfo: ServerInfo = ServerInfo()
) : Parcelable {

    fun getVersionCode(): Long {
        return serverInfo.versionCode
    }

    fun isRunning(): Boolean {
        return Xadkern.pingBinder() && XadkernApiConstant.server.VERSION_CODE <= getVersionCode()
    }

    fun isNeedUpdate(): Boolean {
        return XadkernApiConstant.server.VERSION_CODE > getVersionCode() && Xadkern.pingBinder()
    }

    fun isNeedExtraStep(): Boolean {
        return isRunning() && !serverInfo.permission
    }

    fun isRoot(): Boolean {
        return serverInfo.uid == 0
    }

}