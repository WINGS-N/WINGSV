package wings.v.root.server

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.system.Os
import androidx.annotation.RequiresApi
import be.mygod.librootkotlinx.JniInit
import be.mygod.librootkotlinx.ParcelableBoolean
import kotlinx.coroutines.runBlocking

object WingsVpnFirewallBridge {
    private const val UIDS_ALLOWED_ON_RESTRICTED_NETWORKS = "uids_allowed_on_restricted_networks"
    private const val DUMP_COMMAND = "dumpsys netd trafficcontroller"

    private val firewallMatcher by lazy {
        "^\\s*${Process.myUid()}\\D* IIF_MATCH ".toRegex(RegexOption.MULTILINE)
    }

    @JvmStatic
    fun mayBeAffected(context: Context): Boolean {
        return isBpfSupported() && context.checkSelfPermission(
            "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS"
        ) != PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    @Throws(Exception::class)
    fun setup(context: Context) {
        if (!mayBeAffected(context)) {
            return
        }
        val uid = Process.myUid()
        if (Build.VERSION.SDK_INT < 31) {
            removeUidInterfaceRules(context, uid)
            return
        }

        val command = "settings get global $UIDS_ALLOWED_ON_RESTRICTED_NETWORKS"
        val result = RootServerBridge.runQuiet(context, command)
        if (result.exitCode != 0) {
            throw IllegalStateException(result.primaryMessage())
        }
        val allowed = result.stdout.trim()
            .splitToSequence(';')
            .mapNotNull { it.toIntOrNull() }
            .toMutableSet()
        if (!allowed.contains(uid)) {
            allowed.add(uid)
            val updated = allowed.joinToString(";")
            val writeResult = RootServerBridge.runQuiet(
                context,
                "settings put global $UIDS_ALLOWED_ON_RESTRICTED_NETWORKS '$updated'"
            )
            if (writeResult.exitCode != 0) {
                throw IllegalStateException(writeResult.primaryMessage())
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            removeUidInterfaceRules(context, uid)
        } else {
            val dump = RootServerBridge.runQuiet(context, DUMP_COMMAND)
            if (dump.exitCode == 0 && firewallMatcher.containsMatchIn(dump.stdout)) {
                removeUidInterfaceRules(context, uid)
            }
        }
    }

    private fun isBpfSupported(): Boolean {
        val properties by lazy { Class.forName("android.os.SystemProperties") }
        val firstApiIsHigh = { fallback: Long ->
            properties.getDeclaredMethod("getLong", String::class.java, Long::class.java)(null,
                "ro.product.first_api_level", fallback) as Long >= 28
        }
        return when (Build.VERSION.SDK_INT) {
            28 -> false
            29 -> firstApiIsHigh(29L) && Os.uname().release.split('.', limit = 3).let { version ->
                val major = version[0].toInt()
                major > 4 || major == 4 && version[1].toInt() >= 9
            }
            30 -> {
                val kernel = "^(\\d+)\\.(\\d+)\\.(\\d+).*".toPattern().matcher(Os.uname().release).let { version ->
                    if (!version.matches()) return@let 0
                    version.group(1)!!.toInt() * 65536 + version.group(2)!!.toInt() * 256 + version.group(3)!!.toInt()
                }
                kernel >= 4 * 65536 + 14 * 256 ||
                    properties.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java)(null,
                        "ro.kernel.ebpf.supported", false) as Boolean ||
                    kernel >= 4 * 65536 + 9 * 256 && firstApiIsHigh(30L)
            }
            else -> true
        }
    }

    @RequiresApi(29)
    @Throws(Exception::class)
    private fun removeUidInterfaceRules(context: Context, uid: Int) {
        RootServerBridge.initialize(context)
        val removed = runBlocking {
            val result = WingsRootManager.use { server ->
                if (Build.VERSION.SDK_INT >= 33) {
                    server.execute(JniInit())
                }
                server.execute(RemoveUidInterfaceRuleCommand(uid))
            } as ParcelableBoolean
            result.value
        }
        if (!removed) {
            return
        }
    }
}
