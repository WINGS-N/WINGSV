package wings.v.core;

import android.text.TextUtils;

public class ProxySettings {
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
    public boolean rootModeEnabled;

    public String validate() {
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
