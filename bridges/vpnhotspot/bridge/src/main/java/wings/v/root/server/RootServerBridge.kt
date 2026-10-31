package wings.v.root.server

import android.content.Context
import kotlinx.coroutines.runBlocking

object RootServerBridge {
    @JvmStatic
    fun initialize(context: Context) {
        WingsRootManager.initialize(context)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun run(context: Context, command: String, redirect: Boolean = false): String {
        val result = runQuiet(context, command, redirect)
        if (result.exitCode != 0) {
            throw IllegalStateException(result.primaryMessage())
        }
        return result.stdout
    }

    @JvmStatic
    @Throws(Exception::class)
    fun runQuiet(context: Context, command: String, redirect: Boolean = false): RootProcessResult {
        initialize(context)
        return runBlocking {
            val result = WingsRootManager.use { server ->
                server.execute(RootProcessCommand(command, redirect))
            } as RootProcessResult
            result
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun closeExisting() {
        runBlocking {
            WingsRootManager.closeExisting()
        }
    }
}
