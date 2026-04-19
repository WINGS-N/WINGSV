package wings.v.root.server

import android.annotation.SuppressLint
import android.content.Context

object RootServices {
    @Volatile
    private var contextProvider: (() -> Context)? = null

    fun init(provider: () -> Context) {
        contextProvider = provider
    }

    val context: Context
        get() = checkNotNull(contextProvider) { "RootServices not initialized" }.invoke()

    val netd by lazy @SuppressLint("WrongConstant") { context.getSystemService("netd")!! }
}
