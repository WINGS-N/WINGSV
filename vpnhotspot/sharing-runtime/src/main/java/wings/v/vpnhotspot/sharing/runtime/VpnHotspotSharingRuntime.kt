package wings.v.vpnhotspot.sharing.runtime

import android.content.Context
import android.text.TextUtils
import androidx.core.content.edit
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.RoutingManager
import be.mygod.vpnhotspot.net.Routing
import be.mygod.vpnhotspot.net.monitor.Upstreams
import be.mygod.vpnhotspot.root.daemon.MasqueradeMode
import kotlinx.coroutines.runBlocking
import wings.v.vpnhotspot.runtime.VpnHotspotUpstreamRuntime

object VpnHotspotSharingRuntime {
    private const val KEY_DISABLE_IPV6 = "service.disableIpv6"
    private const val KEY_ROOT_DNS = "service.upstream.rootDns"
    private const val KEY_SYNTHETIC_ROOT_UPSTREAM = "service.upstream.syntheticRoot"
    private const val KEY_MASQUERADE_MODE = "service.masqueradeMode"
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
            runBlocking { entry.value.stop() }
            iterator.remove()
        }

        for (iface in desired) {
            val routing = DownstreamRouting(this, iface)
            val started = runBlocking { routing.start() }
            if (started) {
                active[iface] = routing
            }
        }

        if (active.isEmpty()) {
            cleanRuntime()
        }
    }

    @Synchronized
    fun stop(context: Context) {
        VpnHotspotUpstreamRuntime.initialize(context)
        for (manager in active.values) runBlocking { manager.stop() }
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
        app.pref.edit {
            putString(Upstreams.KEY_PRIMARY, normalizedUpstream)
            putString(Upstreams.KEY_FALLBACK, normalizeInterface(config.fallbackUpstreamInterface))
            putString(KEY_ROOT_DNS, normalizeDns(config.explicitDnsServers))
            putBoolean(KEY_SYNTHETIC_ROOT_UPSTREAM, syntheticRootUpstream)
            putString(KEY_MASQUERADE_MODE, masqueradeModeName(mapMasqueradeMode(config.masqueradeMode)))
            putBoolean(KEY_DISABLE_IPV6, config.isDisableIpv6)
        }
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
        app.pref.edit {
            remove(Upstreams.KEY_PRIMARY)
            remove(Upstreams.KEY_FALLBACK)
            remove(KEY_ROOT_DNS)
            remove(KEY_SYNTHETIC_ROOT_UPSTREAM)
        }
    }

    private fun normalizeInterface(value: String?): String? {
        return if (TextUtils.isEmpty(value) || "auto".equals(value, ignoreCase = true)) null else value?.trim()
    }

    private fun normalizeDns(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        return if (normalized.isEmpty()) null else normalized
    }

    private fun mapMasqueradeMode(value: String?): MasqueradeMode {
        return when (value?.trim()?.lowercase()) {
            "none" -> MasqueradeMode.MASQUERADE_MODE_NONE
            "netd" -> MasqueradeMode.MASQUERADE_MODE_NETD
            else -> MasqueradeMode.MASQUERADE_MODE_SIMPLE
        }
    }

    private fun masqueradeModeName(mode: MasqueradeMode): String = when (mode) {
        MasqueradeMode.MASQUERADE_MODE_NONE -> "None"
        MasqueradeMode.MASQUERADE_MODE_NETD -> "Netd"
        else -> "Simple"
    }

    private class DownstreamRouting(caller: Any, downstream: String) : RoutingManager(caller, downstream) {
        override fun Routing.configure() {
            ipForward = true
            masqueradeMode = RoutingManager.masqueradeMode
            ipv6Mode = if (app.pref.getBoolean(KEY_DISABLE_IPV6, true)) {
                Routing.Ipv6Mode.Block
            } else {
                Routing.Ipv6Mode.System
            }
        }
    }
}
