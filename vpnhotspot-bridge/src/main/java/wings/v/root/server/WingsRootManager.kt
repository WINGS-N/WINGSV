package wings.v.root.server

import android.content.Context
import android.util.Log
import be.mygod.librootkotlinx.AppProcess
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.RootServer
import be.mygod.librootkotlinx.RootSession

object WingsRootManager : RootSession(), Logger {
    private const val TAG = "WINGSV-Root"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override suspend fun initServer(server: RootServer) {
        val context = checkNotNull(appContext) { "WingsRootManager not initialized" }
        Logger.me = this
        server.init(context, AppProcess.shouldRelocateHeuristics)
        server.execute(RootInitCommand())
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
