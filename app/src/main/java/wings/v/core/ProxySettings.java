package wings.v.core;

import android.text.TextUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.amnezia.awg.config.Config;

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
    public int threads;
    public boolean useUdp;
    public boolean noObfuscation;
    public boolean manualCaptcha;
    public boolean vkTurnRestartOnNetworkChange = true;
    public ProxyRuntimeMode vkTurnRuntimeMode = ProxyRuntimeMode.VPN;
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
    public boolean rootModeEnabled;
    public boolean kernelWireguardEnabled;
    public XrayProfile activeXrayProfile;
    public XraySettings xraySettings;
    public ByeDpiSettings byeDpiSettings;

    public String validate() {
        if (backendType != null && backendType.usesXrayCore()) {
            if (activeXrayProfile == null || TextUtils.isEmpty(activeXrayProfile.rawLink)) {
                return "Xray профиль не выбран";
            }
            if (
                xraySettings != null && xraySettings.transportMode != null && xraySettings.transportMode.usesTurnProxy()
            ) {
                if (TextUtils.isEmpty(endpoint)) {
                    return "Endpoint не заполнен";
                }
                if (TextUtils.isEmpty(vkLink)) {
                    return "VK Link не заполнен";
                }
                if (TextUtils.isEmpty(localEndpoint)) {
                    return "Локальный endpoint не заполнен";
                }
            }
            if (xraySettings != null && xraySettings.runtimeMode != null && xraySettings.runtimeMode.isProxyOnly()) {
                if (!xraySettings.localProxyEnabled || xraySettings.localProxyPort <= 0) {
                    return "Для режима только proxy включите локальный SOCKS proxy Xray";
                }
            }
            return null;
        }
        if (backendType != null && backendType.usesAmneziaSettings()) {
            if (backendType.usesTurnProxy()) {
                if (TextUtils.isEmpty(endpoint)) {
                    return "Endpoint не заполнен";
                }
                if (TextUtils.isEmpty(vkLink)) {
                    return "VK Link не заполнен";
                }
                if (TextUtils.isEmpty(localEndpoint)) {
                    return "Локальный endpoint не заполнен";
                }
                if (vkTurnRuntimeMode != null && vkTurnRuntimeMode.isProxyOnly()) {
                    return null;
                }
            }
            if (TextUtils.isEmpty(awgQuickConfig)) {
                return "AmneziaWG config не заполнен";
            }
            try {
                Config.parse(new ByteArrayInputStream(awgQuickConfig.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception error) {
                return "AmneziaWG config некорректен: " + error.getMessage();
            }
            return null;
        }
        if (TextUtils.isEmpty(endpoint)) {
            return "Endpoint не заполнен";
        }
        if (backendType != null && backendType.usesTurnProxy()) {
            if (TextUtils.isEmpty(vkLink)) {
                return "VK Link не заполнен";
            }
            if (TextUtils.isEmpty(localEndpoint)) {
                return "Локальный endpoint не заполнен";
            }
            if (vkTurnRuntimeMode != null && vkTurnRuntimeMode.isProxyOnly()) {
                return null;
            }
        }
        if (TextUtils.isEmpty(wgPrivateKey)) {
            return "WireGuard private key не заполнен";
        }
        if (TextUtils.isEmpty(wgAddresses)) {
            return "WireGuard addresses не заполнены";
        }
        if (TextUtils.isEmpty(wgPublicKey)) {
            return "WireGuard public key не заполнен";
        }
        if (TextUtils.isEmpty(wgAllowedIps)) {
            return "WireGuard allowed IPs не заполнены";
        }
        return null;
    }
}
