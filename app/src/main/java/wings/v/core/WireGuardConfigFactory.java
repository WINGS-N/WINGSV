package wings.v.core;

import android.content.Context;

import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

public final class WireGuardConfigFactory {
    private WireGuardConfigFactory() {
    }

    public static Config build(Context context, ProxySettings settings) throws Exception {
        return build(context, settings, true);
    }

    public static Config build(Context context, ProxySettings settings, boolean includeAppRouting) throws Exception {
        StringBuilder builder = new StringBuilder();
        builder.append("[Interface]\n");
        builder.append("PrivateKey = ").append(settings.wgPrivateKey).append('\n');
        builder.append("Address = ").append(settings.wgAddresses).append('\n');
        if (!isBlank(settings.wgDns)) {
            builder.append("DNS = ").append(settings.wgDns).append('\n');
        }
        if (includeAppRouting) {
            Set<String> routedPackages = context != null
                    ? new TreeSet<>(AppPrefs.getAppRoutingPackages(context))
                    : new TreeSet<>();
            if (!routedPackages.isEmpty()) {
                String joinedPackages = String.join(", ", routedPackages);
                if (AppPrefs.isAppRoutingBypassEnabled(context)) {
                    builder.append("ExcludedApplications = ").append(joinedPackages).append('\n');
                } else {
                    builder.append("IncludedApplications = ").append(joinedPackages).append('\n');
                }
            }
        }
        builder.append("MTU = ").append(settings.wgMtu).append("\n\n");
        builder.append("[Peer]\n");
        builder.append("PublicKey = ").append(settings.wgPublicKey).append('\n');
        builder.append("AllowedIPs = ").append(settings.wgAllowedIps).append('\n');
        builder.append("Endpoint = ").append(settings.localEndpoint).append('\n');

        return Config.parse(new ByteArrayInputStream(
                builder.toString().getBytes(StandardCharsets.UTF_8)
        ));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
