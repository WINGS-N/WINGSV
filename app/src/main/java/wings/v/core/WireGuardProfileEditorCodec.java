package wings.v.core;

import android.text.TextUtils;
import com.wireguard.config.Config;
import com.wireguard.config.InetEndpoint;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Converts a WireGuardProfile to and from an editable wg-quick text block so the
 * profile text editor can round-trip it. toEditableQuickConfig renders the
 * profile fields as a [Interface]/[Peer] config; parseQuickConfig validates a
 * pasted/edited config via com.wireguard.config.Config (throws on invalid) and
 * returns a new WireGuardProfile that keeps the base profile's id and title.
 * Mirrors XrayProfileEditorCodec for the WireGuard backend.
 */
@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.OnlyOneReturn",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
    }
)
public final class WireGuardProfileEditorCodec {

    private static final int DEFAULT_MTU = 1280;

    private WireGuardProfileEditorCodec() {}

    public static String toEditableQuickConfig(WireGuardProfile profile) {
        if (profile == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder("[Interface]\n");
        appendLine(builder, "PrivateKey", profile.privateKey);
        appendLine(builder, "Address", profile.addresses);
        appendLine(builder, "DNS", profile.dns);
        appendLine(builder, "MTU", profile.mtu > 0 ? String.valueOf(profile.mtu) : "");
        builder.append('\n').append("[Peer]\n");
        appendLine(builder, "PublicKey", profile.publicKey);
        appendLine(builder, "PresharedKey", profile.presharedKey);
        appendLine(builder, "AllowedIPs", profile.allowedIps);
        appendLine(builder, "Endpoint", profile.endpoint);
        return builder.toString().trim();
    }

    public static WireGuardProfile parseQuickConfig(WireGuardProfile base, String rawConfig) throws Exception {
        String normalized = normalize(rawConfig);
        if (TextUtils.isEmpty(normalized)) {
            throw new IllegalArgumentException("Empty WireGuard config");
        }
        Config config = Config.parse(new ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8)));
        Interface iface = config.getInterface();
        if (config.getPeers().isEmpty()) {
            throw new IllegalArgumentException("WireGuard config has no [Peer]");
        }
        Peer peer = config.getPeers().get(0);
        String id = base == null ? null : base.id;
        String title = base == null ? "" : base.title;
        return new WireGuardProfile(
            id,
            title,
            iface.getKeyPair().getPrivateKey().toBase64(),
            join(iface.getAddresses()),
            joinDns(iface.getDnsServers(), iface.getDnsSearchDomains()),
            iface.getMtu().orElse(DEFAULT_MTU),
            peer.getPublicKey().toBase64(),
            peer
                .getPreSharedKey()
                .map(key -> key.toBase64())
                .orElse(""),
            join(peer.getAllowedIps()),
            peer.getEndpoint().map(InetEndpoint::toString).orElse("")
        );
    }

    private static void appendLine(StringBuilder builder, String key, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!TextUtils.isEmpty(normalized)) {
            builder.append(key).append(" = ").append(normalized).append('\n');
        }
    }

    private static String joinDns(Collection<?> dnsServers, Collection<String> searchDomains) {
        List<String> items = new ArrayList<>();
        if (dnsServers != null) {
            for (Object server : dnsServers) {
                if (server != null) {
                    String text = String.valueOf(server);
                    // InetAddress.toString may render as "/1.1.1.1"; keep the IP.
                    items.add(text.startsWith("/") ? text.substring(1) : text);
                }
            }
        }
        if (searchDomains != null) {
            items.addAll(searchDomains);
        }
        return TextUtils.join(", ", items);
    }

    private static String join(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> items = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                items.add(String.valueOf(value));
            }
        }
        return TextUtils.join(", ", items);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n");
    }
}
