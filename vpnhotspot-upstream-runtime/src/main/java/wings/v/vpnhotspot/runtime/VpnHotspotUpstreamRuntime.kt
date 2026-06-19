package wings.v.vpnhotspot.runtime

import android.content.Context
import be.mygod.vpnhotspot.App
import be.mygod.vpnhotspot.net.VpnFirewallManager
import be.mygod.vpnhotspot.util.RootSession

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
}
