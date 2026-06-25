package wings.v.core;

import android.text.TextUtils;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A VK TURN profile. Composition with its transport (WireGuard or AmneziaWG) is
 * BY REFERENCE: this stores transportKind plus transportProfileId pointing at a
 * WireGuardProfile or AmneziaProfile, not an embedded copy. The proxy fields
 * mirror the flat VK TURN keys currently held in ProxySettings/AppPrefs.
 */
@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.ShortVariable",
        "PMD.OnlyOneReturn",
        "PMD.LawOfDemeter",
        "PMD.SimplifyBooleanReturns",
        "PMD.TooManyFields",
        "PMD.ExcessiveParameterList",
    }
)
public final class VkTurnProfile {

    public static final String TRANSPORT_KIND_WG = "wg";
    public static final String TRANSPORT_KIND_AWG = "awg";

    public final String id;
    public final String title;
    public final String transportKind;
    public final String transportProfileId;
    public final String vkTurnEndpoint;

    public final int threads;
    public final int credsGroupSize;
    public final boolean useUdp;
    public final boolean noObfuscation;
    public final boolean manualCaptcha;
    public final String captchaAutoSolver;
    public final String vkAuthMode;
    public final String turnSessionMode;
    public final String dnsMode;
    public final String userDns;
    public final String runtimeMode;
    public final boolean restartOnNetworkChange;
    public final String wrapMode;
    public final String wrapCipher;
    public final String wrapKeyHex;
    public final boolean wrapSendKey;
    public final String localEndpoint;
    public final String turnHost;
    public final String turnPort;

    public VkTurnProfile(
        final String id,
        final String title,
        final String transportKind,
        final String transportProfileId,
        final String vkTurnEndpoint,
        final int threads,
        final int credsGroupSize,
        final boolean useUdp,
        final boolean noObfuscation,
        final boolean manualCaptcha,
        final String captchaAutoSolver,
        final String vkAuthMode,
        final String turnSessionMode,
        final String dnsMode,
        final String userDns,
        final String runtimeMode,
        final boolean restartOnNetworkChange,
        final String wrapMode,
        final String wrapCipher,
        final String wrapKeyHex,
        final boolean wrapSendKey,
        final String localEndpoint,
        final String turnHost,
        final String turnPort
    ) {
        this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        this.title = emptyIfNull(title);
        this.transportKind = normalizeTransportKind(transportKind);
        this.transportProfileId = emptyIfNull(transportProfileId);
        this.vkTurnEndpoint = emptyIfNull(vkTurnEndpoint);
        this.threads = Math.max(threads, 0);
        this.credsGroupSize = Math.max(credsGroupSize, 0);
        this.useUdp = useUdp;
        this.noObfuscation = noObfuscation;
        this.manualCaptcha = manualCaptcha;
        this.captchaAutoSolver = emptyIfNull(captchaAutoSolver);
        this.vkAuthMode = emptyIfNull(vkAuthMode);
        this.turnSessionMode = emptyIfNull(turnSessionMode);
        this.dnsMode = emptyIfNull(dnsMode);
        this.userDns = emptyIfNull(userDns);
        this.runtimeMode = emptyIfNull(runtimeMode);
        this.restartOnNetworkChange = restartOnNetworkChange;
        this.wrapMode = emptyIfNull(wrapMode);
        this.wrapCipher = emptyIfNull(wrapCipher);
        this.wrapKeyHex = emptyIfNull(wrapKeyHex);
        this.wrapSendKey = wrapSendKey;
        this.localEndpoint = emptyIfNull(localEndpoint);
        this.turnHost = emptyIfNull(turnHost);
        this.turnPort = emptyIfNull(turnPort);
    }

    public static String normalizeTransportKind(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return TRANSPORT_KIND_AWG.equals(normalized) ? TRANSPORT_KIND_AWG : TRANSPORT_KIND_WG;
    }

    public boolean usesAmneziaTransport() {
        return TRANSPORT_KIND_AWG.equals(transportKind);
    }

    public String stableDedupKey() {
        return (transportKind + "|" + transportProfileId + "|" + vkTurnEndpoint).trim().toLowerCase(Locale.ROOT);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("title", title);
        object.put("transport_kind", transportKind);
        object.put("transport_profile_id", transportProfileId);
        object.put("vk_turn_endpoint", vkTurnEndpoint);
        object.put("threads", threads);
        object.put("creds_group_size", credsGroupSize);
        object.put("use_udp", useUdp);
        object.put("no_obfuscation", noObfuscation);
        object.put("manual_captcha", manualCaptcha);
        object.put("captcha_auto_solver", captchaAutoSolver);
        object.put("vk_auth_mode", vkAuthMode);
        object.put("turn_session_mode", turnSessionMode);
        object.put("dns_mode", dnsMode);
        object.put("user_dns", userDns);
        object.put("runtime_mode", runtimeMode);
        object.put("restart_on_network_change", restartOnNetworkChange);
        object.put("wrap_mode", wrapMode);
        object.put("wrap_cipher", wrapCipher);
        object.put("wrap_key_hex", wrapKeyHex);
        object.put("wrap_send_key", wrapSendKey);
        object.put("local_endpoint", localEndpoint);
        object.put("turn_host", turnHost);
        object.put("turn_port", turnPort);
        return object;
    }

    public static VkTurnProfile fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new VkTurnProfile(
            object.optString("id"),
            object.optString("title"),
            object.optString("transport_kind"),
            object.optString("transport_profile_id"),
            object.optString("vk_turn_endpoint"),
            object.optInt("threads"),
            object.optInt("creds_group_size"),
            object.optBoolean("use_udp", true),
            object.optBoolean("no_obfuscation", false),
            object.optBoolean("manual_captcha", false),
            object.optString("captcha_auto_solver"),
            object.optString("vk_auth_mode"),
            object.optString("turn_session_mode"),
            object.optString("dns_mode"),
            object.optString("user_dns"),
            object.optString("runtime_mode"),
            object.optBoolean("restart_on_network_change", true),
            object.optString("wrap_mode"),
            object.optString("wrap_cipher"),
            object.optString("wrap_key_hex"),
            object.optBoolean("wrap_send_key", true),
            object.optString("local_endpoint"),
            object.optString("turn_host"),
            object.optString("turn_port")
        );
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VkTurnProfile)) {
            return false;
        }
        final VkTurnProfile profile = (VkTurnProfile) other;
        return (
            threads == profile.threads &&
            credsGroupSize == profile.credsGroupSize &&
            useUdp == profile.useUdp &&
            noObfuscation == profile.noObfuscation &&
            manualCaptcha == profile.manualCaptcha &&
            restartOnNetworkChange == profile.restartOnNetworkChange &&
            wrapSendKey == profile.wrapSendKey &&
            Objects.equals(id, profile.id) &&
            Objects.equals(title, profile.title) &&
            Objects.equals(transportKind, profile.transportKind) &&
            Objects.equals(transportProfileId, profile.transportProfileId) &&
            Objects.equals(vkTurnEndpoint, profile.vkTurnEndpoint) &&
            Objects.equals(captchaAutoSolver, profile.captchaAutoSolver) &&
            Objects.equals(vkAuthMode, profile.vkAuthMode) &&
            Objects.equals(turnSessionMode, profile.turnSessionMode) &&
            Objects.equals(dnsMode, profile.dnsMode) &&
            Objects.equals(userDns, profile.userDns) &&
            Objects.equals(runtimeMode, profile.runtimeMode) &&
            Objects.equals(wrapMode, profile.wrapMode) &&
            Objects.equals(wrapCipher, profile.wrapCipher) &&
            Objects.equals(wrapKeyHex, profile.wrapKeyHex) &&
            Objects.equals(localEndpoint, profile.localEndpoint) &&
            Objects.equals(turnHost, profile.turnHost) &&
            Objects.equals(turnPort, profile.turnPort)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            title,
            transportKind,
            transportProfileId,
            vkTurnEndpoint,
            threads,
            credsGroupSize,
            useUdp,
            turnSessionMode,
            localEndpoint,
            turnHost,
            turnPort
        );
    }

    private static String emptyIfNull(final String value) {
        return value == null ? "" : value;
    }
}
