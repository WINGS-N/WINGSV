package wings.v.vpnhotspot.runtime

import android.content.Context
import be.mygod.vpnhotspot.App
import be.mygod.vpnhotspot.net.TetherOffloadManager
import be.mygod.vpnhotspot.net.VpnFirewallManager
import be.mygod.vpnhotspot.util.RootSession
import kotlinx.coroutines.runBlocking

object VpnHotspotUpstreamRuntime {
    @JvmStatic
    fun initialize(context: Context) {
        App.ensureInitialized(context.applicationContext)
    }

    @JvmStatic
    fun setupVpnFirewall(context: Context) {
        initialize(context)
        val transaction = RootSession.beginTransaction()
        try {
            VpnFirewallManager.setup(transaction)
            transaction.commit()
        } catch (error: Exception) {
            transaction.revert()
            throw error
        }
    }

    @JvmStatic
    fun isTetherOffloadEnabled(context: Context): Boolean {
        initialize(context)
        return TetherOffloadManager.enabled
    }

    @JvmStatic
    fun setTetherOffloadEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        runBlocking {
            TetherOffloadManager.setEnabled(enabled)
        }
    }

    @JvmStatic
    fun syncSharing(context: Context, activeInterfaces: kotlin.collections.Set<String>, config: VpnHotspotSharingRuntimeConfig) {
        initialize(context)
        VpnHotspotSharingRuntime.sync(context, activeInterfaces, config)
    }

    @JvmStatic
    fun stopSharing(context: Context) {
        initialize(context)
        VpnHotspotSharingRuntime.stop(context)
    }
}
