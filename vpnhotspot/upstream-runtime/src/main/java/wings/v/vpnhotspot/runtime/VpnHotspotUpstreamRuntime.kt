package wings.v.vpnhotspot.runtime

import android.content.Context
import be.mygod.vpnhotspot.App

object VpnHotspotUpstreamRuntime {
    @JvmStatic
    fun initialize(context: Context) {
        App.ensureInitialized(context.applicationContext)
    }
}
