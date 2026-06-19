package wings.v.root.server

import android.content.Context
import android.util.Log
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.RootServer
import be.mygod.librootkotlinx.RootSession
import be.mygod.librootkotlinx.systemContext
import be.mygod.vpnhotspot.util.UnblockCentral

object WingsRootManager : RootSession(), Logger {
    private const val TAG = "WINGSV-Root"

    @Volatile
    private var appContext: Context? = null

    override val context: Context
        get() = appContext ?: systemContext

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override suspend fun initServer(server: RootServer) {
        Logger.me = this
        UnblockCentral.openPidFd
        super.initServer(server)
    }

    override fun d(m: String?, t: Throwable?) {
        Log.d(TAG, m, t)
    }

    override fun e(m: String?, t: Throwable?) {
        Log.e(TAG, m, t)
    }

    override fun i(m: String?, t: Throwable?) {
        Log.i(TAG, m, t)
    }

    override fun w(m: String?, t: Throwable?) {
        Log.w(TAG, m, t)
    }
}
