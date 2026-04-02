package wings.v.vpnhotspot.runtime

import android.content.Context
import android.text.TextUtils
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.RoutingManager
import be.mygod.vpnhotspot.net.DhcpWorkaround
import be.mygod.vpnhotspot.net.Routing
import be.mygod.vpnhotspot.net.monitor.FallbackUpstreamMonitor
import be.mygod.vpnhotspot.net.monitor.UpstreamMonitor
import kotlinx.coroutines.runBlocking

object VpnHotspotSharingRuntime {
    private const val KEY_DISABLE_IPV6 = "service.disableIpv6"
    private const val KEY_ROOT_DNS = "service.upstream.rootDns"
    private const val KEY_SYNTHETIC_ROOT_UPSTREAM = "service.upstream.syntheticRoot"
    private val active = linkedMapOf<String, DownstreamRouting>()
    private var currentConfig: VpnHotspotSharingRuntimeConfig? = null

    @Synchronized
    fun sync(context: Context, activeInterfaces: Set<String>, config: VpnHotspotSharingRuntimeConfig) {
        VpnHotspotUpstreamRuntime.initialize(context)
        applyConfig(config)

        val desired = LinkedHashSet<String>()
        for (iface in activeInterfaces) {
            val trimmed = iface.trim()
            if (trimmed.isNotEmpty()) {
                desired.add(trimmed)
            }
        }

        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (desired.remove(entry.key)) {
                continue
            }
            entry.value.stop()
            iterator.remove()
        }

        for (iface in desired) {
            val routing = DownstreamRouting(this, iface)
            if (routing.start()) {
                active[iface] = routing
            } else {
                routing.stop()
            }
        }

        if (active.isEmpty()) {
            cleanRuntime()
        }
    }

    @Synchronized
    fun stop(context: Context) {
        VpnHotspotUpstreamRuntime.initialize(context)
        active.values.forEach { it.stop() }
        active.clear()
        cleanRuntime()
        clearRuntimePrefs()
        currentConfig = null
    }

    @Synchronized
    private fun applyConfig(config: VpnHotspotSharingRuntimeConfig) {
        if (config == currentConfig) {
            return
        }
        val normalizedUpstream = normalizeInterface(config.upstreamInterface)
        val syntheticRootUpstream = normalizedUpstream?.startsWith("wgv") == true
        app.pref.edit()
            .putString(UpstreamMonitor.KEY, normalizedUpstream)
            .putString(FallbackUpstreamMonitor.KEY, normalizeInterface(config.fallbackUpstreamInterface))
            .putString(KEY_ROOT_DNS, normalizeDns(config.explicitDnsServers))
            .putBoolean(KEY_SYNTHETIC_ROOT_UPSTREAM, syntheticRootUpstream)
            .putString("service.masqueradeMode", mapMasqueradeMode(config.masqueradeMode).name)
            .putBoolean(KEY_DISABLE_IPV6, config.isDisableIpv6)
            .putBoolean("service.dhcpWorkaround", config.isDhcpWorkaroundEnabled)
            .apply()
        DhcpWorkaround.enable(config.isDhcpWorkaroundEnabled)
        RoutingManager.masqueradeMode = mapMasqueradeMode(config.masqueradeMode)
        currentConfig = config
        if (active.isNotEmpty()) {
            RoutingManager.clean()
        }
    }

    private fun cleanRuntime() {
        try {
            runBlocking { Routing.clean() }
        } catch (_: Exception) {
        }
        if (active.isEmpty()) {
            clearRuntimePrefs()
        }
    }

    private fun clearRuntimePrefs() {
        app.pref.edit()
            .remove(UpstreamMonitor.KEY)
            .remove(FallbackUpstreamMonitor.KEY)
            .remove(KEY_ROOT_DNS)
            .remove(KEY_SYNTHETIC_ROOT_UPSTREAM)
            .apply()
    }

    private fun normalizeInterface(value: String?): String? {
        return if (TextUtils.isEmpty(value) || "auto".equals(value, ignoreCase = true)) null else value?.trim()
    }

    private fun normalizeDns(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        return if (normalized.isEmpty()) null else normalized
    }

    private fun mapMasqueradeMode(value: String?): Routing.MasqueradeMode {
        return when (value?.trim()?.lowercase()) {
            "none" -> Routing.MasqueradeMode.None
            "netd" -> Routing.MasqueradeMode.Netd
            else -> Routing.MasqueradeMode.Simple
        }
    }

    private class DownstreamRouting(caller: Any, downstream: String) : RoutingManager(caller, downstream) {
        override fun Routing.configure() {
            forward()
            masquerade(masqueradeMode)
            if (app.pref.getBoolean(KEY_DISABLE_IPV6, true)) {
                disableIpv6()
            }
        }
    }
}
