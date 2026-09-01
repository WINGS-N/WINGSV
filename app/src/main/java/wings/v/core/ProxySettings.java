package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.amnezia.awg.config.Config;
import wings.v.R;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.OnlyOneReturn",
        "PMD.LawOfDemeter",
        "PMD.AtLeastOneConstructor",
        "PMD.TooManyFields",
        "PMD.LongVariable",
        "PMD.CognitiveComplexity",
        "PMD.CyclomaticComplexity",
    }
)
public class ProxySettings {

    public BackendType backendType = BackendType.VK_TURN_WIREGUARD;
    public String endpoint;
    public String vkLink;
    public java.util.List<String> vkLinks = new java.util.ArrayList<>();

    public String vkLinkSecondary = "";
    /** Многострочный список user-defined DNS-резолверов для vk-turn-proxy.
     *  Каждая строка - entry в формате URL: https://host/dns-query (DoH),
     *  udp://ip[:port] или просто ip[:port] (plain). Парсится самим прокси
     *  через флаг -user-dns; здесь храним сырой текст как ввёл админ/юзер. */
    public String vkTurnUserDns = "";
    public int threads;
    public int credsGroupSize = 12;
    public boolean useUdp;
    public boolean noObfuscation;
    public boolean manualCaptcha;
    public String captchaAutoSolver = AppPrefs.CAPTCHA_AUTO_SOLVER_DEFAULT;
    /** VK account auth mode for vk-turn-proxy TURN creds.
     *  "account" = obtain TURN creds via a logged-in VK web session WebView;
     *  "anonymous" = default behaviour (relay uses its own anonymous flow). */
    public String vkAuthMode = "anonymous";
    public boolean vkTurnRestartOnNetworkChange = true;
    public ProxyRuntimeMode vkTurnRuntimeMode = ProxyRuntimeMode.VPN;
    /** WRAP per-packet obfuscation mode: "off" / "preferred" / "required". */
    public String vkTurnWrapMode = "preferred";
    /** WRAP SRTP-mimicry AEAD: "srtp-aes-gcm" (default) or "srtp-chacha20-poly1305". */
    public String vkTurnWrapCipher = "srtp-aes-gcm";
    /** Hex-encoded 32-byte WRAP shared key (64 chars). Empty = auto-generate. */
    public String vkTurnWrapKeyHex = "";
    /** Transmit wrap key in-band via mu/v1 SessionHello (default true). */
    public boolean vkTurnWrapSendKey = true;
    public String turnSessionMode;
    public String localEndpoint;
    public String turnHost;
    public String turnPort;
    public String wgPrivateKey;
    public String wgAddresses;
    public String wgDns;
    public int wgMtu;
    public String wgPublicKey;
    public String wgPresharedKey;
    public String wgAllowedIps;
    public String awgQuickConfig;
    // Managed profile: the wg/awg transport is provisioned by the vk-turn-proxy
    // node on connect over its DTLS PROVISION exchange. The wg* fields above are
    // then left empty and the relay is launched with the provision credentials
    // instead, minting and applying the config itself.
    public boolean wgProvisioned;
    public String provisionClientId;
    public String provisionToken;
    public boolean rootModeEnabled;
    public boolean kernelWireguardEnabled;
    public XrayProfile activeXrayProfile;
    public XraySettings xraySettings;
    public ByeDpiSettings byeDpiSettings;

    public String validate(Context context) {
        if (backendType != null && backendType.usesXrayCore()) {
            if (activeXrayProfile == null || TextUtils.isEmpty(activeXrayProfile.rawLink)) {
                return context.getString(R.string.proxy_xray_profile_not_selected);
            }
            if (
                xraySettings != null && xraySettings.transportMode != null && xraySettings.transportMode.usesTurnProxy()
            ) {
                if (TextUtils.isEmpty(endpoint)) {
                    return context.getString(R.string.proxy_endpoint_required);
                }
                if (TextUtils.isEmpty(vkLink)) {
                    return context.getString(R.string.proxy_vk_link_required);
                }
                if (TextUtils.isEmpty(localEndpoint)) {
                    return context.getString(R.string.proxy_local_endpoint_required);
                }
            }
            if (xraySettings != null && xraySettings.runtimeMode != null && xraySettings.runtimeMode.isProxyOnly()) {
                if (!xraySettings.localProxyEnabled || xraySettings.localProxyPort <= 0) {
                    return context.getString(R.string.proxy_only_mode_requires_socks);
                }
            }
            return null;
        }
        if (backendType != null && backendType.usesAmneziaSettings()) {
            if (backendType.usesTurnProxy()) {
                if (TextUtils.isEmpty(endpoint)) {
                    return context.getString(R.string.proxy_endpoint_required);
                }
                if (TextUtils.isEmpty(vkLink)) {
                    return context.getString(R.string.proxy_vk_link_required);
                }
                if (TextUtils.isEmpty(localEndpoint)) {
                    return context.getString(R.string.proxy_local_endpoint_required);
                }
                if (vkTurnRuntimeMode != null && vkTurnRuntimeMode.isProxyOnly()) {
                    return null;
                }
            }
            // Managed profiles provision their tunnel config over DTLS PROVISION at
            // connect time, so an empty quick-config here is expected - skip it or the
            // connect aborts before it can provision.
            if (wgProvisioned) {
                return null;
            }
            if (TextUtils.isEmpty(awgQuickConfig)) {
                return context.getString(R.string.proxy_amneziawg_config_required);
            }
            try {
                Config.parse(new ByteArrayInputStream(awgQuickConfig.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception error) {
                return context.getString(R.string.proxy_amneziawg_config_invalid, error.getMessage());
            }
            return null;
        }
        if (TextUtils.isEmpty(endpoint)) {
            return context.getString(R.string.proxy_endpoint_required);
        }
        if (backendType != null && backendType.usesTurnProxy()) {
            if (TextUtils.isEmpty(vkLink)) {
                return context.getString(R.string.proxy_vk_link_required);
            }
            if (TextUtils.isEmpty(localEndpoint)) {
                return context.getString(R.string.proxy_local_endpoint_required);
            }
            if (vkTurnRuntimeMode != null && vkTurnRuntimeMode.isProxyOnly()) {
                return null;
            }
        }
        // A managed profile carries no wg keys: they are minted server-side and
        // delivered over DTLS PROVISION at connect time. Requiring them here would
        // abort the connect before the provisioning step runs, so a freshly imported
        // managed profile could never fetch its first config. The provisioning step
        // itself surfaces a real failure.
        if (wgProvisioned) {
            return null;
        }
        if (TextUtils.isEmpty(wgPrivateKey)) {
            return context.getString(R.string.proxy_wireguard_private_key_required);
        }
        if (TextUtils.isEmpty(wgAddresses)) {
            return context.getString(R.string.proxy_wireguard_addresses_required);
        }
        if (TextUtils.isEmpty(wgPublicKey)) {
            return context.getString(R.string.proxy_wireguard_public_key_required);
        }
        if (TextUtils.isEmpty(wgAllowedIps)) {
            return context.getString(R.string.proxy_wireguard_allowed_ips_required);
        }
        return null;
    }
}
