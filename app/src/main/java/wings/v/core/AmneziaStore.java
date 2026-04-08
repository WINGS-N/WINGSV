package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import androidx.preference.PreferenceManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetEndpoint;
import org.amnezia.awg.config.Interface;
import org.amnezia.awg.config.Peer;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public final class AmneziaStore {

    public static final String KEY_OPEN_SETTINGS = "pref_open_amnezia_settings";
    public static final String KEY_IMPORT_FROM_CLIPBOARD = "pref_awg_import_from_clipboard";
    public static final String KEY_INFO = "pref_awg_info";
    public static final String KEY_INTERFACE_PRIVATE_KEY = "pref_awg_interface_private_key";
    public static final String KEY_INTERFACE_ADDRESSES = "pref_awg_interface_addresses";
    public static final String KEY_INTERFACE_DNS = "pref_awg_interface_dns";
    public static final String KEY_INTERFACE_LISTEN_PORT = "pref_awg_interface_listen_port";
    public static final String KEY_INTERFACE_MTU = "pref_awg_interface_mtu";
    public static final String KEY_INTERFACE_JC = "pref_awg_interface_jc";
    public static final String KEY_INTERFACE_JMIN = "pref_awg_interface_jmin";
    public static final String KEY_INTERFACE_JMAX = "pref_awg_interface_jmax";
    public static final String KEY_INTERFACE_S1 = "pref_awg_interface_s1";
    public static final String KEY_INTERFACE_S2 = "pref_awg_interface_s2";
    public static final String KEY_INTERFACE_S3 = "pref_awg_interface_s3";
    public static final String KEY_INTERFACE_S4 = "pref_awg_interface_s4";
    public static final String KEY_INTERFACE_H1 = "pref_awg_interface_h1";
    public static final String KEY_INTERFACE_H2 = "pref_awg_interface_h2";
    public static final String KEY_INTERFACE_H3 = "pref_awg_interface_h3";
    public static final String KEY_INTERFACE_H4 = "pref_awg_interface_h4";
    public static final String KEY_INTERFACE_I1 = "pref_awg_interface_i1";
    public static final String KEY_INTERFACE_I2 = "pref_awg_interface_i2";
    public static final String KEY_INTERFACE_I3 = "pref_awg_interface_i3";
    public static final String KEY_INTERFACE_I4 = "pref_awg_interface_i4";
    public static final String KEY_INTERFACE_I5 = "pref_awg_interface_i5";
    public static final String KEY_PEER_PUBLIC_KEY = "pref_awg_peer_public_key";
    public static final String KEY_PEER_PRESHARED_KEY = "pref_awg_peer_preshared_key";
    public static final String KEY_PEER_ALLOWED_IPS = "pref_awg_peer_allowed_ips";
    public static final String KEY_PEER_ENDPOINT = "pref_awg_peer_endpoint";
    public static final String KEY_PEER_PERSISTENT_KEEPALIVE = "pref_awg_peer_persistent_keepalive";

    private AmneziaStore() {}

    public static void applyRawConfig(Context context, String rawConfig) throws Exception {
        Context appContext = context.getApplicationContext();
        String normalized = normalize(rawConfig);
        SharedPreferences.Editor editor = prefs(appContext).edit();
        editor.putString(AppPrefs.KEY_AWG_QUICK_CONFIG, normalized);
        StructuredConfig structured = parseRawConfig(normalized);
        writeStructuredConfig(editor, structured);
        editor.apply();
    }

    public static void syncRawConfigFromStructuredPrefs(Context context) {
        Context appContext = context.getApplicationContext();
        prefs(appContext)
            .edit()
            .putString(AppPrefs.KEY_AWG_QUICK_CONFIG, buildRawConfigFromStructuredPrefs(appContext))
            .apply();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static void maybeBackfillStructuredPrefs(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences sharedPreferences = prefs(appContext);
        if (!TextUtils.isEmpty(trim(sharedPreferences.getString(KEY_INTERFACE_PRIVATE_KEY, "")))) {
            return;
        }
        String raw = trim(sharedPreferences.getString(AppPrefs.KEY_AWG_QUICK_CONFIG, ""));
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            StructuredConfig structured = parseRawConfig(raw);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            writeStructuredConfig(editor, structured);
            editor.apply();
        } catch (Exception ignored) {}
    }

    public static String getEffectiveQuickConfig(Context context) {
        String raw = trim(prefs(context).getString(AppPrefs.KEY_AWG_QUICK_CONFIG, ""));
        if (!TextUtils.isEmpty(raw)) {
            return raw;
        }
        return buildRawConfigFromStructuredPrefs(context);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static String getConfiguredEndpoint(Context context) {
        SharedPreferences sharedPreferences = prefs(context);
        String endpoint = trim(sharedPreferences.getString(KEY_PEER_ENDPOINT, ""));
        if (!TextUtils.isEmpty(endpoint)) {
            return endpoint;
        }
        try {
            Config parsed = Config.parse(
                new ByteArrayInputStream(getEffectiveQuickConfig(context).getBytes(StandardCharsets.UTF_8))
            );
            if (!parsed.getPeers().isEmpty()) {
                return parsed.getPeers().get(0).getEndpoint().map(InetEndpoint::toString).orElse("");
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static String getConfiguredDns(Context context) {
        return trim(prefs(context).getString(KEY_INTERFACE_DNS, ""));
    }

    public static boolean isStructuredPreferenceKey(String key) {
        return (
            KEY_INTERFACE_PRIVATE_KEY.equals(key) ||
            KEY_INTERFACE_ADDRESSES.equals(key) ||
            KEY_INTERFACE_DNS.equals(key) ||
            KEY_INTERFACE_LISTEN_PORT.equals(key) ||
            KEY_INTERFACE_MTU.equals(key) ||
            KEY_INTERFACE_JC.equals(key) ||
            KEY_INTERFACE_JMIN.equals(key) ||
            KEY_INTERFACE_JMAX.equals(key) ||
            KEY_INTERFACE_S1.equals(key) ||
            KEY_INTERFACE_S2.equals(key) ||
            KEY_INTERFACE_S3.equals(key) ||
            KEY_INTERFACE_S4.equals(key) ||
            KEY_INTERFACE_H1.equals(key) ||
            KEY_INTERFACE_H2.equals(key) ||
            KEY_INTERFACE_H3.equals(key) ||
            KEY_INTERFACE_H4.equals(key) ||
            KEY_INTERFACE_I1.equals(key) ||
            KEY_INTERFACE_I2.equals(key) ||
            KEY_INTERFACE_I3.equals(key) ||
            KEY_INTERFACE_I4.equals(key) ||
            KEY_INTERFACE_I5.equals(key) ||
            KEY_PEER_PUBLIC_KEY.equals(key) ||
            KEY_PEER_PRESHARED_KEY.equals(key) ||
            KEY_PEER_ALLOWED_IPS.equals(key) ||
            KEY_PEER_ENDPOINT.equals(key) ||
            KEY_PEER_PERSISTENT_KEEPALIVE.equals(key)
        );
    }

    private static StructuredConfig parseRawConfig(String rawConfig) throws Exception {
        String normalized = normalize(rawConfig);
        Config config = Config.parse(new ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8)));
        StructuredConfig result = new StructuredConfig();
        Interface iface = config.getInterface();
        result.privateKey = iface.getKeyPair().getPrivateKey().toBase64();
        result.addresses = join(iface.getAddresses());
        result.dns = joinDns(iface.getDnsServers(), iface.getDnsSearchDomains());
        result.listenPort = iface.getListenPort().map(String::valueOf).orElse("");
        result.mtu = iface.getMtu().map(String::valueOf).orElse("");
        result.jc = iface.getJunkPacketCount().map(String::valueOf).orElse("");
        result.jmin = iface.getJunkPacketMinSize().map(String::valueOf).orElse("");
        result.jmax = iface.getJunkPacketMaxSize().map(String::valueOf).orElse("");
        result.s1 = iface.getInitPacketJunkSize().map(String::valueOf).orElse("");
        result.s2 = iface.getResponsePacketJunkSize().map(String::valueOf).orElse("");
        result.s3 = iface.getCookieReplyPacketJunkSize().map(String::valueOf).orElse("");
        result.s4 = iface.getTransportPacketJunkSize().map(String::valueOf).orElse("");
        result.h1 = iface.getInitPacketMagicHeader().orElse("");
        result.h2 = iface.getResponsePacketMagicHeader().orElse("");
        result.h3 = iface.getUnderloadPacketMagicHeader().orElse("");
        result.h4 = iface.getTransportPacketMagicHeader().orElse("");
        result.i1 = iface.getSpecialJunkI1().orElse("");
        result.i2 = iface.getSpecialJunkI2().orElse("");
        result.i3 = iface.getSpecialJunkI3().orElse("");
        result.i4 = iface.getSpecialJunkI4().orElse("");
        result.i5 = iface.getSpecialJunkI5().orElse("");
        if (!config.getPeers().isEmpty()) {
            Peer peer = config.getPeers().get(0);
            result.peerPublicKey = peer.getPublicKey().toBase64();
            result.peerPresharedKey = peer
                .getPreSharedKey()
                .map(key -> key.toBase64())
                .orElse("");
            result.peerAllowedIps = join(peer.getAllowedIps());
            result.peerEndpoint = peer.getEndpoint().map(InetEndpoint::toString).orElse("");
            result.peerPersistentKeepalive = peer.getPersistentKeepalive().map(String::valueOf).orElse("");
        }
        return result;
    }

    private static void writeStructuredConfig(SharedPreferences.Editor editor, StructuredConfig config) {
        editor.putString(KEY_INTERFACE_PRIVATE_KEY, config.privateKey);
        editor.putString(KEY_INTERFACE_ADDRESSES, config.addresses);
        editor.putString(KEY_INTERFACE_DNS, config.dns);
        editor.putString(KEY_INTERFACE_LISTEN_PORT, config.listenPort);
        editor.putString(KEY_INTERFACE_MTU, config.mtu);
        editor.putString(KEY_INTERFACE_JC, config.jc);
        editor.putString(KEY_INTERFACE_JMIN, config.jmin);
        editor.putString(KEY_INTERFACE_JMAX, config.jmax);
        editor.putString(KEY_INTERFACE_S1, config.s1);
        editor.putString(KEY_INTERFACE_S2, config.s2);
        editor.putString(KEY_INTERFACE_S3, config.s3);
        editor.putString(KEY_INTERFACE_S4, config.s4);
        editor.putString(KEY_INTERFACE_H1, config.h1);
        editor.putString(KEY_INTERFACE_H2, config.h2);
        editor.putString(KEY_INTERFACE_H3, config.h3);
        editor.putString(KEY_INTERFACE_H4, config.h4);
        editor.putString(KEY_INTERFACE_I1, config.i1);
        editor.putString(KEY_INTERFACE_I2, config.i2);
        editor.putString(KEY_INTERFACE_I3, config.i3);
        editor.putString(KEY_INTERFACE_I4, config.i4);
        editor.putString(KEY_INTERFACE_I5, config.i5);
        editor.putString(KEY_PEER_PUBLIC_KEY, config.peerPublicKey);
        editor.putString(KEY_PEER_PRESHARED_KEY, config.peerPresharedKey);
        editor.putString(KEY_PEER_ALLOWED_IPS, config.peerAllowedIps);
        editor.putString(KEY_PEER_ENDPOINT, config.peerEndpoint);
        editor.putString(KEY_PEER_PERSISTENT_KEEPALIVE, config.peerPersistentKeepalive);
    }

    private static String buildRawConfigFromStructuredPrefs(Context context) {
        SharedPreferences sharedPreferences = prefs(context);
        StringBuilder builder = new StringBuilder("[Interface]\n");
        appendLine(builder, "PrivateKey", sharedPreferences.getString(KEY_INTERFACE_PRIVATE_KEY, ""));
        appendLine(builder, "Address", sharedPreferences.getString(KEY_INTERFACE_ADDRESSES, ""));
        appendLine(builder, "DNS", sharedPreferences.getString(KEY_INTERFACE_DNS, ""));
        appendLine(builder, "ListenPort", sharedPreferences.getString(KEY_INTERFACE_LISTEN_PORT, ""));
        appendLine(builder, "MTU", sharedPreferences.getString(KEY_INTERFACE_MTU, ""));
        appendLine(builder, "Jc", sharedPreferences.getString(KEY_INTERFACE_JC, ""));
        appendLine(builder, "Jmin", sharedPreferences.getString(KEY_INTERFACE_JMIN, ""));
        appendLine(builder, "Jmax", sharedPreferences.getString(KEY_INTERFACE_JMAX, ""));
        appendLine(builder, "S1", sharedPreferences.getString(KEY_INTERFACE_S1, ""));
        appendLine(builder, "S2", sharedPreferences.getString(KEY_INTERFACE_S2, ""));
        appendLine(builder, "S3", sharedPreferences.getString(KEY_INTERFACE_S3, ""));
        appendLine(builder, "S4", sharedPreferences.getString(KEY_INTERFACE_S4, ""));
        appendLine(builder, "H1", sharedPreferences.getString(KEY_INTERFACE_H1, ""));
        appendLine(builder, "H2", sharedPreferences.getString(KEY_INTERFACE_H2, ""));
        appendLine(builder, "H3", sharedPreferences.getString(KEY_INTERFACE_H3, ""));
        appendLine(builder, "H4", sharedPreferences.getString(KEY_INTERFACE_H4, ""));
        appendLine(builder, "I1", sharedPreferences.getString(KEY_INTERFACE_I1, ""));
        appendLine(builder, "I2", sharedPreferences.getString(KEY_INTERFACE_I2, ""));
        appendLine(builder, "I3", sharedPreferences.getString(KEY_INTERFACE_I3, ""));
        appendLine(builder, "I4", sharedPreferences.getString(KEY_INTERFACE_I4, ""));
        appendLine(builder, "I5", sharedPreferences.getString(KEY_INTERFACE_I5, ""));
        builder.append('\n').append("[Peer]\n");
        appendLine(builder, "PublicKey", sharedPreferences.getString(KEY_PEER_PUBLIC_KEY, ""));
        appendLine(builder, "PreSharedKey", sharedPreferences.getString(KEY_PEER_PRESHARED_KEY, ""));
        appendLine(builder, "AllowedIPs", sharedPreferences.getString(KEY_PEER_ALLOWED_IPS, ""));
        appendLine(builder, "Endpoint", sharedPreferences.getString(KEY_PEER_ENDPOINT, ""));
        appendLine(builder, "PersistentKeepalive", sharedPreferences.getString(KEY_PEER_PERSISTENT_KEEPALIVE, ""));
        return builder.toString().trim();
    }

    private static void appendLine(StringBuilder builder, String key, String value) {
        String normalized = trim(value);
        if (!TextUtils.isEmpty(normalized)) {
            builder.append(key).append(" = ").append(normalized).append('\n');
        }
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

    private static String joinDns(Collection<?> dnsServers, Collection<String> searchDomains) {
        List<String> items = new ArrayList<>();
        if (dnsServers != null) {
            for (Object server : dnsServers) {
                if (server != null) {
                    items.add(String.valueOf(server));
                }
            }
        }
        if (searchDomains != null) {
            items.addAll(searchDomains);
        }
        return TextUtils.join(", ", items);
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static String normalize(String value) {
        return trim(value).replace("\r\n", "\n");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class StructuredConfig {

        private String privateKey = "";
        private String addresses = "";
        private String dns = "";
        private String listenPort = "";
        private String mtu = "";
        private String jc = "";
        private String jmin = "";
        private String jmax = "";
        private String s1 = "";
        private String s2 = "";
        private String s3 = "";
        private String s4 = "";
        private String h1 = "";
        private String h2 = "";
        private String h3 = "";
        private String h4 = "";
        private String i1 = "";
        private String i2 = "";
        private String i3 = "";
        private String i4 = "";
        private String i5 = "";
        private String peerPublicKey = "";
        private String peerPresharedKey = "";
        private String peerAllowedIps = "";
        private String peerEndpoint = "";
        private String peerPersistentKeepalive = "";
    }
}
