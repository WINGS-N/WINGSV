package wings.v.core;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import wings.v.proto.WingsvProto;

public final class WingsImportParser {
    private static final Pattern LINK_PATTERN = Pattern.compile("wingsv://[A-Za-z0-9_\\-+/=]+");
    private static final String SCHEME_PREFIX = "wingsv://";
    private static final int CURRENT_VERSION = 1;
    private static final byte FORMAT_PROTOBUF_DEFLATE = 0x12;

    private static final int DEFAULT_THREADS = 8;
    private static final boolean DEFAULT_USE_UDP = true;
    private static final boolean DEFAULT_NO_OBFUSCATION = false;
    private static final String DEFAULT_SESSION_MODE = "auto";
    private static final String DEFAULT_LOCAL_ENDPOINT = "127.0.0.1:9000";
    private static final String DEFAULT_WG_DNS = "1.1.1.1, 1.0.0.1";
    private static final int DEFAULT_WG_MTU = 1280;
    private static final String DEFAULT_ALLOWED_IPS = "0.0.0.0/0, ::/0";

    private WingsImportParser() {
    }

    public static String buildLink(ProxySettings settings) throws Exception {
        WingsvProto.Config config = buildProtoConfig(settings);
        byte[] protobufPayload = config.toByteArray();
        byte[] compressedPayload = deflate(protobufPayload);
        byte[] framedPayload = new byte[compressedPayload.length + 1];
        framedPayload[0] = FORMAT_PROTOBUF_DEFLATE;
        System.arraycopy(compressedPayload, 0, framedPayload, 1, compressedPayload.length);
        return SCHEME_PREFIX + Base64.encodeToString(framedPayload, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    public static ImportedConfig parseFromText(String rawText) throws Exception {
        String link = extractLink(rawText);
        if (TextUtils.isEmpty(link)) {
            throw new IllegalArgumentException("WINGSV ссылка не найдена");
        }

        byte[] decodedPayload = decodePayload(link);
        if (decodedPayload.length == 0) {
            throw new IllegalArgumentException("WINGSV payload пуст");
        }

        if (decodedPayload[0] == FORMAT_PROTOBUF_DEFLATE) {
            byte[] protobufPayload = inflate(slice(decodedPayload, 1, decodedPayload.length));
            return parseProtoConfig(WingsvProto.Config.parseFrom(protobufPayload));
        }
        if (isLikelyJsonPayload(decodedPayload)) {
            return parseJsonPayload(decodedPayload);
        }
        throw new IllegalArgumentException("Неподдерживаемый формат WINGSV ссылки");
    }

    public static String extractLink(String rawText) {
        if (TextUtils.isEmpty(rawText)) {
            return null;
        }
        Matcher matcher = LINK_PATTERN.matcher(rawText);
        if (matcher.find()) {
            return matcher.group();
        }
        if (rawText.startsWith(SCHEME_PREFIX)) {
            return rawText.trim();
        }
        return null;
    }

    private static WingsvProto.Config buildProtoConfig(ProxySettings settings) throws Exception {
        WingsvProto.Config.Builder builder = WingsvProto.Config.newBuilder()
                .setVer(CURRENT_VERSION)
                .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK);

        if (settings != null) {
            WingsvProto.Turn turn = buildTurn(settings);
            if (!turn.equals(WingsvProto.Turn.getDefaultInstance())) {
                builder.setTurn(turn);
            }
            WingsvProto.WireGuard wg = buildWireGuard(settings);
            if (!wg.equals(WingsvProto.WireGuard.getDefaultInstance())) {
                builder.setWg(wg);
            }
        }
        return builder.build();
    }

    private static WingsvProto.Turn buildTurn(ProxySettings settings) throws Exception {
        WingsvProto.Turn.Builder builder = WingsvProto.Turn.newBuilder();
        setEndpoint(builder, value(settings.endpoint), true);
        if (!TextUtils.isEmpty(value(settings.vkLink))) {
            builder.setLink(value(settings.vkLink));
        }
        if (settings.threads > 0 && settings.threads != DEFAULT_THREADS) {
            builder.setThreads(settings.threads);
        }
        if (settings.useUdp != DEFAULT_USE_UDP) {
            builder.setUseUdp(settings.useUdp);
        }
        if (settings.noObfuscation != DEFAULT_NO_OBFUSCATION) {
            builder.setNoObfuscation(settings.noObfuscation);
        }
        String sessionMode = value(settings.turnSessionMode);
        WingsvProto.TurnSessionMode protoSessionMode = toProtoSessionMode(sessionMode);
        if (protoSessionMode != WingsvProto.TurnSessionMode.TURN_SESSION_MODE_AUTO
                && protoSessionMode != WingsvProto.TurnSessionMode.TURN_SESSION_MODE_UNSPECIFIED) {
            builder.setSessionMode(protoSessionMode);
        }
        String localEndpoint = value(settings.localEndpoint);
        if (!TextUtils.isEmpty(localEndpoint) && !DEFAULT_LOCAL_ENDPOINT.equals(localEndpoint)) {
            setEndpoint(builder, localEndpoint, false);
        }
        if (!TextUtils.isEmpty(value(settings.turnHost))) {
            builder.setHost(value(settings.turnHost));
        }
        if (!TextUtils.isEmpty(value(settings.turnPort))) {
            try {
                builder.setPort(Integer.parseInt(value(settings.turnPort)));
            } catch (NumberFormatException ignored) {
            }
        }
        return builder.build();
    }

    private static void setEndpoint(WingsvProto.Turn.Builder builder, String endpoint, boolean remote)
            throws Exception {
        WingsvProto.Endpoint parsed = parseEndpoint(endpoint);
        if (parsed == null) {
            return;
        }
        if (remote) {
            builder.setEndpoint(parsed);
        } else {
            builder.setLocalEndpoint(parsed);
        }
    }

    private static WingsvProto.WireGuard buildWireGuard(ProxySettings settings) throws Exception {
        WingsvProto.WireGuard.Builder builder = WingsvProto.WireGuard.newBuilder();

        WingsvProto.Interface.Builder iface = WingsvProto.Interface.newBuilder();
        if (!TextUtils.isEmpty(value(settings.wgPrivateKey))) {
            iface.setPrivateKey(com.google.protobuf.ByteString.copyFrom(decodeWireGuardKey(value(settings.wgPrivateKey))));
        }
        for (String address : splitCsv(value(settings.wgAddresses))) {
            iface.addAddrs(address);
        }
        String dns = value(settings.wgDns);
        if (!TextUtils.isEmpty(dns) && !DEFAULT_WG_DNS.equals(dns)) {
            for (String entry : splitCsv(dns)) {
                iface.addDns(entry);
            }
        }
        if (settings.wgMtu > 0 && settings.wgMtu != DEFAULT_WG_MTU) {
            iface.setMtu(settings.wgMtu);
        }
        WingsvProto.Interface ifaceMessage = iface.build();
        if (!ifaceMessage.equals(WingsvProto.Interface.getDefaultInstance())) {
            builder.setIface(ifaceMessage);
        }

        WingsvProto.Peer.Builder peer = WingsvProto.Peer.newBuilder();
        if (!TextUtils.isEmpty(value(settings.wgPublicKey))) {
            peer.setPublicKey(com.google.protobuf.ByteString.copyFrom(decodeWireGuardKey(value(settings.wgPublicKey))));
        }
        if (!TextUtils.isEmpty(value(settings.wgPresharedKey))) {
            peer.setPresharedKey(com.google.protobuf.ByteString.copyFrom(decodeWireGuardKey(value(settings.wgPresharedKey))));
        }
        String allowedIps = value(settings.wgAllowedIps);
        if (!TextUtils.isEmpty(allowedIps) && !DEFAULT_ALLOWED_IPS.equals(allowedIps)) {
            for (String cidr : splitCsv(allowedIps)) {
                WingsvProto.Cidr parsed = parseCidr(cidr);
                if (parsed != null) {
                    peer.addAllowedIps(parsed);
                }
            }
        }
        WingsvProto.Peer peerMessage = peer.build();
        if (!peerMessage.equals(WingsvProto.Peer.getDefaultInstance())) {
            builder.setPeer(peerMessage);
        }

        return builder.build();
    }

    private static ImportedConfig parseProtoConfig(WingsvProto.Config config) throws Exception {
        if (config.getVer() <= 0) {
            throw new IllegalArgumentException("Отсутствует или некорректен ver");
        }
        if (config.getType() != WingsvProto.ConfigType.CONFIG_TYPE_VK) {
            throw new IllegalArgumentException("Поддерживается только type=vk");
        }

        ImportedConfig importedConfig = new ImportedConfig();
        if (config.hasTurn()) {
            WingsvProto.Turn turn = config.getTurn();
            if (turn.hasEndpoint()) {
                importedConfig.endpoint = formatEndpoint(turn.getEndpoint());
            }
            importedConfig.link = value(turn.getLink());
            if (turn.hasThreads()) {
                importedConfig.threads = turn.getThreads();
            }
            if (turn.hasUseUdp()) {
                importedConfig.useUdp = turn.getUseUdp();
            }
            if (turn.hasNoObfuscation()) {
                importedConfig.noObfuscation = turn.getNoObfuscation();
            }
            if (turn.getSessionMode() != WingsvProto.TurnSessionMode.TURN_SESSION_MODE_UNSPECIFIED) {
                importedConfig.turnSessionMode = fromProtoSessionMode(turn.getSessionMode());
            }
            if (turn.hasLocalEndpoint()) {
                importedConfig.localEndpoint = formatEndpoint(turn.getLocalEndpoint());
            }
            importedConfig.turnHost = value(turn.getHost());
            if (turn.hasPort()) {
                importedConfig.turnPort = String.valueOf(turn.getPort());
            }
        }

        if (config.hasWg()) {
            WingsvProto.WireGuard wg = config.getWg();
            if (wg.hasIface()) {
                WingsvProto.Interface iface = wg.getIface();
                if (!iface.getPrivateKey().isEmpty()) {
                    importedConfig.wgPrivateKey = encodeWireGuardKey(iface.getPrivateKey().toByteArray());
                }
                if (iface.getAddrsCount() > 0) {
                    importedConfig.wgAddresses = TextUtils.join(", ", iface.getAddrsList());
                }
                if (iface.getDnsCount() > 0) {
                    importedConfig.wgDns = TextUtils.join(", ", iface.getDnsList());
                }
                if (iface.hasMtu()) {
                    importedConfig.wgMtu = iface.getMtu();
                }
            }
            if (wg.hasPeer()) {
                WingsvProto.Peer peer = wg.getPeer();
                if (!peer.getPublicKey().isEmpty()) {
                    importedConfig.wgPublicKey = encodeWireGuardKey(peer.getPublicKey().toByteArray());
                }
                if (!peer.getPresharedKey().isEmpty()) {
                    importedConfig.wgPresharedKey = encodeWireGuardKey(peer.getPresharedKey().toByteArray());
                }
                if (peer.getAllowedIpsCount() > 0) {
                    List<String> cidrs = new ArrayList<>(peer.getAllowedIpsCount());
                    for (WingsvProto.Cidr cidr : peer.getAllowedIpsList()) {
                        cidrs.add(formatCidr(cidr));
                    }
                    importedConfig.wgAllowedIps = TextUtils.join(", ", cidrs);
                }
            }
        }
        return importedConfig;
    }

    private static ImportedConfig parseJsonPayload(byte[] decodedPayload) throws Exception {
        JSONObject root = new JSONObject(new String(decodedPayload, StandardCharsets.UTF_8));
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
        importedConfig.turnSessionMode = turn.optString("session_mode");
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
                importedConfig.wgPresharedKey = peer.optString("preshared_key");
                importedConfig.wgAllowedIps = peer.optString("allowed_ips");
            }
        }

        return importedConfig;
    }

    private static WingsvProto.Endpoint parseEndpoint(String endpoint) throws Exception {
        if (TextUtils.isEmpty(endpoint) || !endpoint.contains(":")) {
            return null;
        }
        int separator = endpoint.lastIndexOf(':');
        String host = endpoint.substring(0, separator).trim();
        String portRaw = endpoint.substring(separator + 1).trim();
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(portRaw)) {
            return null;
        }
        try {
            return WingsvProto.Endpoint.newBuilder()
                    .setHost(host)
                    .setPort(Integer.parseInt(portRaw))
                    .build();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatEndpoint(WingsvProto.Endpoint endpoint) {
        if (TextUtils.isEmpty(endpoint.getHost())) {
            return "";
        }
        return endpoint.getHost() + ":" + endpoint.getPort();
    }

    private static WingsvProto.Cidr parseCidr(String rawValue) throws Exception {
        if (TextUtils.isEmpty(rawValue)) {
            return null;
        }
        int separator = rawValue.indexOf('/');
        if (separator <= 0 || separator >= rawValue.length() - 1) {
            return null;
        }
        String address = rawValue.substring(0, separator).trim();
        String prefixRaw = rawValue.substring(separator + 1).trim();
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            int prefix = Integer.parseInt(prefixRaw);
            return WingsvProto.Cidr.newBuilder()
                    .setAddr(com.google.protobuf.ByteString.copyFrom(inetAddress.getAddress()))
                    .setPrefix(prefix)
                    .build();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatCidr(WingsvProto.Cidr cidr) throws Exception {
        return InetAddress.getByAddress(cidr.getAddr().toByteArray()).getHostAddress() + "/" + cidr.getPrefix();
    }

    private static byte[] decodePayload(String link) {
        String payload = link.substring(SCHEME_PREFIX.length()).trim();
        while (payload.startsWith("/")) {
            payload = payload.substring(1);
        }
        payload = payload.replaceAll("\\s+", "");

        try {
            return Base64.decode(normalizePadding(payload), Base64.URL_SAFE);
        } catch (Exception ignored) {
            return Base64.decode(normalizePadding(payload), Base64.DEFAULT);
        }
    }

    private static boolean isLikelyJsonPayload(byte[] payload) {
        for (byte value : payload) {
            char c = (char) value;
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '{';
        }
        return false;
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            if (count <= 0) {
                break;
            }
            output.write(buffer, 0, count);
        }
        deflater.end();
        return output.toByteArray();
    }

    private static byte[] inflate(byte[] input) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (!inflater.finished()) {
            int count = inflater.inflate(buffer);
            if (count > 0) {
                output.write(buffer, 0, count);
                continue;
            }
            if (inflater.needsInput()) {
                break;
            }
            throw new IllegalArgumentException("Не удалось распаковать payload");
        }
        inflater.end();
        return output.toByteArray();
    }

    private static List<String> splitCsv(String rawValue) {
        List<String> values = new ArrayList<>();
        if (TextUtils.isEmpty(rawValue)) {
            return values;
        }
        String[] parts = rawValue.split(",");
        for (String part : parts) {
            String normalized = part == null ? "" : part.trim();
            if (!TextUtils.isEmpty(normalized)) {
                values.add(normalized);
            }
        }
        return values;
    }

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(source, from, result, 0, result.length);
        return result;
    }

    private static byte[] decodeWireGuardKey(String value) {
        try {
            return Base64.decode(value, Base64.DEFAULT);
        } catch (Exception ignored) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String encodeWireGuardKey(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
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

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static WingsvProto.TurnSessionMode toProtoSessionMode(String rawValue) {
        String normalized = value(rawValue);
        if (TextUtils.isEmpty(normalized) || DEFAULT_SESSION_MODE.equals(normalized)) {
            return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_AUTO;
        }
        if ("legacy".equals(normalized)) {
            return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_LEGACY;
        }
        if ("mux".equals(normalized)) {
            return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MUX;
        }
        return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_AUTO;
    }

    private static String fromProtoSessionMode(WingsvProto.TurnSessionMode value) {
        if (value == WingsvProto.TurnSessionMode.TURN_SESSION_MODE_LEGACY) {
            return "legacy";
        }
        if (value == WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MUX) {
            return "mux";
        }
        return DEFAULT_SESSION_MODE;
    }

    public static final class ImportedConfig {
        public String endpoint;
        public String link;
        public Integer threads;
        public Boolean useUdp;
        public Boolean noObfuscation;
        public String turnSessionMode;
        public String localEndpoint;
        public String turnHost;
        public String turnPort;
        public String wgPrivateKey;
        public String wgAddresses;
        public String wgDns;
        public Integer wgMtu;
        public String wgPublicKey;
        public String wgPresharedKey;
        public String wgAllowedIps;
    }
}
