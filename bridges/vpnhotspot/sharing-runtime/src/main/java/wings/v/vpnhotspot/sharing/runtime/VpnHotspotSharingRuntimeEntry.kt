package wings.v.vpnhotspot.sharing.runtime

import android.content.Context
import be.mygod.vpnhotspot.net.TetherOffloadManager
import be.mygod.vpnhotspot.root.daemon.DaemonController
import kotlinx.coroutines.runBlocking
import wings.v.vpnhotspot.runtime.VpnHotspotUpstreamRuntime

object VpnHotspotSharingRuntimeEntry {
    @JvmStatic
    fun isTetherOffloadEnabled(context: Context): Boolean {
        VpnHotspotUpstreamRuntime.initialize(context)
        return TetherOffloadManager.enabled
    }

    @JvmStatic
    fun setTetherOffloadEnabled(context: Context, enabled: Boolean) {
        VpnHotspotUpstreamRuntime.initialize(context)
        runBlocking {
            TetherOffloadManager.setEnabled(enabled)
        }
    }

    @JvmStatic
    fun syncSharing(context: Context, activeInterfaces: Set<String>, config: VpnHotspotSharingRuntimeConfig) {
        VpnHotspotUpstreamRuntime.initialize(context)
        VpnHotspotSharingRuntime.sync(context, activeInterfaces, config)
    }

    @JvmStatic
    fun stopSharing(context: Context) {
        VpnHotspotUpstreamRuntime.initialize(context)
        VpnHotspotSharingRuntime.stop(context)
    }

    @JvmStatic
    fun readTrafficCounters(context: Context): List<VpnHotspotTrafficCounter> {
        VpnHotspotUpstreamRuntime.initialize(context)
        return try {
            runBlocking { DaemonController.readTrafficCounters() }.map { counter ->
                VpnHotspotTrafficCounter(
                    counter.mac.toByteArray(),
                    counter.downstream,
                    counter.sent_bytes.toLong(),
                    counter.sent_packets.toLong(),
                    counter.received_bytes.toLong(),
                    counter.received_packets.toLong(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
