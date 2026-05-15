package wings.v.core;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class XraySettingsBundle {

    private XraySettingsBundle() {}

    static void write(@NonNull Bundle bundle, @NonNull String prefix, @Nullable XraySettings settings) {
        if (settings == null) {
            bundle.putBoolean(prefix + "present", false);
            return;
        }
        bundle.putBoolean(prefix + "present", true);
        bundle.putBoolean(prefix + "allow_lan", settings.allowLan);
        bundle.putBoolean(prefix + "allow_insecure", settings.allowInsecure);
        bundle.putBoolean(prefix + "local_proxy_enabled", settings.localProxyEnabled);
        bundle.putBoolean(prefix + "local_proxy_auth_enabled", settings.localProxyAuthEnabled);
        bundle.putString(prefix + "local_proxy_username", settings.localProxyUsername);
        bundle.putString(prefix + "local_proxy_password", settings.localProxyPassword);
        bundle.putInt(prefix + "local_proxy_port", settings.localProxyPort);
        bundle.putString(prefix + "local_proxy_listen_address", settings.localProxyListenAddress);
        bundle.putBoolean(prefix + "http_proxy_enabled", settings.httpProxyEnabled);
        bundle.putBoolean(prefix + "http_proxy_auth_enabled", settings.httpProxyAuthEnabled);
        bundle.putString(prefix + "http_proxy_username", settings.httpProxyUsername);
        bundle.putString(prefix + "http_proxy_password", settings.httpProxyPassword);
        bundle.putInt(prefix + "http_proxy_port", settings.httpProxyPort);
        bundle.putString(prefix + "http_proxy_listen_address", settings.httpProxyListenAddress);
        bundle.putString(prefix + "remote_dns", settings.remoteDns);
        bundle.putString(prefix + "direct_dns", settings.directDns);
        bundle.putBoolean(prefix + "ipv6", settings.ipv6);
        bundle.putBoolean(prefix + "sniffing_enabled", settings.sniffingEnabled);
        bundle.putBoolean(prefix + "proxy_quic_enabled", settings.proxyQuicEnabled);
        bundle.putBoolean(prefix + "restart_on_network_change", settings.restartOnNetworkChange);
        bundle.putString(prefix + "runtime_mode", settings.runtimeMode != null ? settings.runtimeMode.name() : null);
        bundle.putString(
            prefix + "transport_mode",
            settings.transportMode != null ? settings.transportMode.name() : null
        );
    }

    @Nullable
    static XraySettings read(@NonNull Bundle bundle, @NonNull String prefix) {
        if (!bundle.getBoolean(prefix + "present", false)) {
            return null;
        }
        XraySettings settings = new XraySettings();
        settings.allowLan = bundle.getBoolean(prefix + "allow_lan", false);
        settings.allowInsecure = bundle.getBoolean(prefix + "allow_insecure", false);
        settings.localProxyEnabled = bundle.getBoolean(prefix + "local_proxy_enabled", false);
        settings.localProxyAuthEnabled = bundle.getBoolean(prefix + "local_proxy_auth_enabled", true);
        settings.localProxyUsername = bundle.getString(prefix + "local_proxy_username");
        settings.localProxyPassword = bundle.getString(prefix + "local_proxy_password");
        settings.localProxyPort = bundle.getInt(prefix + "local_proxy_port", 0);
        settings.localProxyListenAddress = bundle.getString(prefix + "local_proxy_listen_address");
        settings.httpProxyEnabled = bundle.getBoolean(prefix + "http_proxy_enabled", false);
        settings.httpProxyAuthEnabled = bundle.getBoolean(prefix + "http_proxy_auth_enabled", true);
        settings.httpProxyUsername = bundle.getString(prefix + "http_proxy_username");
        settings.httpProxyPassword = bundle.getString(prefix + "http_proxy_password");
        settings.httpProxyPort = bundle.getInt(prefix + "http_proxy_port", 0);
        settings.httpProxyListenAddress = bundle.getString(prefix + "http_proxy_listen_address");
        settings.remoteDns = bundle.getString(prefix + "remote_dns");
        settings.directDns = bundle.getString(prefix + "direct_dns");
        settings.ipv6 = bundle.getBoolean(prefix + "ipv6", false);
        settings.sniffingEnabled = bundle.getBoolean(prefix + "sniffing_enabled", false);
        settings.proxyQuicEnabled = bundle.getBoolean(prefix + "proxy_quic_enabled", false);
        settings.restartOnNetworkChange = bundle.getBoolean(prefix + "restart_on_network_change", false);
        String runtime = bundle.getString(prefix + "runtime_mode");
        if (runtime != null) {
            try {
                settings.runtimeMode = ProxyRuntimeMode.valueOf(runtime);
            } catch (IllegalArgumentException ignored) {}
        }
        String transport = bundle.getString(prefix + "transport_mode");
        if (transport != null) {
            try {
                settings.transportMode = XrayTransportMode.valueOf(transport);
            } catch (IllegalArgumentException ignored) {}
        }
        return settings;
    }
}
