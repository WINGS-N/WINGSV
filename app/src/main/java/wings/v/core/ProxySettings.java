package wings.v.core;

import android.text.TextUtils;

import org.amnezia.awg.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class ProxySettings {
    public BackendType backendType = BackendType.VK_TURN_WIREGUARD;
    public String endpoint;
    public String vkLink;
    public int threads;
    public boolean useUdp;
    public boolean noObfuscation;
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
    public XrayProfile activeXrayProfile;
    public XraySettings xraySettings;

    public String validate() {
        if (backendType == BackendType.XRAY) {
            if (activeXrayProfile == null || TextUtils.isEmpty(activeXrayProfile.rawLink)) {
                return "Xray профиль не выбран";
            }
            return null;
        }
        if (backendType == BackendType.AMNEZIAWG) {
            if (TextUtils.isEmpty(endpoint)) {
                return "Endpoint не заполнен";
            }
            if (TextUtils.isEmpty(vkLink)) {
                return "VK Link не заполнен";
            }
            if (TextUtils.isEmpty(localEndpoint)) {
                return "Локальный endpoint не заполнен";
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
        if (TextUtils.isEmpty(vkLink)) {
            return "VK Link не заполнен";
        }
        if (TextUtils.isEmpty(localEndpoint)) {
            return "Локальный endpoint не заполнен";
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
