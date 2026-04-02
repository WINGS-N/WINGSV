package wings.v.core;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WingsImportParser {
    private static final Pattern LINK_PATTERN = Pattern.compile("wingsv://[A-Za-z0-9_\\-+/=]+");

    private WingsImportParser() {
    }

    public static String buildLink(ProxySettings settings) throws Exception {
        JSONObject root = new JSONObject();
        root.put("ver", 1);
        root.put("type", "vk");

        JSONObject turn = new JSONObject();
        turn.put("endpoint", nullToEmpty(settings != null ? settings.endpoint : null));
        turn.put("link", nullToEmpty(settings != null ? settings.vkLink : null));
        turn.put("threads", settings != null ? settings.threads : 8);
        turn.put("use_udp", settings == null || settings.useUdp);
        turn.put("no_obfuscation", settings != null && settings.noObfuscation);
        turn.put("local_endpoint", nullToEmpty(settings != null ? settings.localEndpoint : null));
        turn.put("host", nullToEmpty(settings != null ? settings.turnHost : null));
        turn.put("port", nullToEmpty(settings != null ? settings.turnPort : null));
        root.put("turn", turn);

        JSONObject wg = new JSONObject();
        JSONObject iface = new JSONObject();
        iface.put("private_key", nullToEmpty(settings != null ? settings.wgPrivateKey : null));
        iface.put("addrs", nullToEmpty(settings != null ? settings.wgAddresses : null));
        iface.put("dns", nullToEmpty(settings != null ? settings.wgDns : null));
        iface.put("mtu", settings != null ? settings.wgMtu : 1280);
        wg.put("if", iface);

        JSONObject peer = new JSONObject();
        peer.put("public_key", nullToEmpty(settings != null ? settings.wgPublicKey : null));
        peer.put("allowed_ips", nullToEmpty(settings != null ? settings.wgAllowedIps : null));
        wg.put("peer", peer);
        root.put("wg", wg);

        byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
        return "wingsv://" + Base64.encodeToString(encoded, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    public static ImportedConfig parseFromText(String rawText) throws Exception {
        String link = extractLink(rawText);
        if (TextUtils.isEmpty(link)) {
            throw new IllegalArgumentException("WINGSV ссылка не найдена");
        }

        String payload = link.substring("wingsv://".length()).trim();
        while (payload.startsWith("/")) {
            payload = payload.substring(1);
        }
        payload = payload.replaceAll("\\s+", "");

        byte[] decoded;
        try {
            decoded = Base64.decode(normalizePadding(payload), Base64.URL_SAFE);
        } catch (Exception ignored) {
            decoded = Base64.decode(normalizePadding(payload), Base64.DEFAULT);
        }

        JSONObject root = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        int version = root.optInt("ver", -1);
        if (version <= 0) {
            throw new IllegalArgumentException("Отсутствует или некорректен ver");
        }
        String type = root.optString("type");
        if (!"vk".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Поддерживается только type=vk");
        }

        JSONObject turn = root.optJSONObject("turn");
        if (turn == null) {
            throw new IllegalArgumentException("Отсутствует turn объект");
        }

        ImportedConfig importedConfig = new ImportedConfig();
        importedConfig.endpoint = turn.optString("endpoint");
        importedConfig.link = turn.optString("link");
        if (turn.has("threads")) {
            importedConfig.threads = turn.optInt("threads");
        }
        if (turn.has("use_udp")) {
            importedConfig.useUdp = turn.optBoolean("use_udp");
        }
        if (turn.has("no_obfuscation")) {
            importedConfig.noObfuscation = turn.optBoolean("no_obfuscation");
        }
        importedConfig.localEndpoint = turn.optString("local_endpoint");
        importedConfig.turnHost = turn.optString("host");
        importedConfig.turnPort = turn.optString("port");

        JSONObject wg = root.optJSONObject("wg");
        if (wg != null) {
            JSONObject iface = wg.optJSONObject("if");
            if (iface != null) {
                importedConfig.wgPrivateKey = iface.optString("private_key");
                importedConfig.wgAddresses = iface.optString("addrs");
                importedConfig.wgDns = iface.optString("dns");
                if (iface.has("mtu")) {
                    importedConfig.wgMtu = iface.optInt("mtu");
                }
            }

            JSONObject peer = wg.optJSONObject("peer");
            if (peer != null) {
                importedConfig.wgPublicKey = peer.optString("public_key");
                importedConfig.wgAllowedIps = peer.optString("allowed_ips");
            }
        }

        return importedConfig;
    }

    public static String extractLink(String rawText) {
        if (TextUtils.isEmpty(rawText)) {
            return null;
        }
        Matcher matcher = LINK_PATTERN.matcher(rawText);
        if (matcher.find()) {
            return matcher.group();
        }
        if (rawText.startsWith("wingsv://")) {
            return rawText.trim();
        }
        return null;
    }

    private static String normalizePadding(String payload) {
        int mod = payload.length() % 4;
        if (mod == 0) {
            return payload;
        }
        StringBuilder builder = new StringBuilder(payload);
        for (int i = mod; i < 4; i++) {
            builder.append('=');
        }
        return builder.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static final class ImportedConfig {
        public String endpoint;
        public String link;
        public Integer threads;
        public Boolean useUdp;
        public Boolean noObfuscation;
        public String localEndpoint;
        public String turnHost;
        public String turnPort;
        public String wgPrivateKey;
        public String wgAddresses;
        public String wgDns;
        public Integer wgMtu;
        public String wgPublicKey;
        public String wgAllowedIps;
    }
}
