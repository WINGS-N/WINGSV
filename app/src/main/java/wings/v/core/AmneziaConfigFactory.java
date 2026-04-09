package wings.v.core;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetEndpoint;
import org.amnezia.awg.config.Interface;
import org.amnezia.awg.config.Peer;

public final class AmneziaConfigFactory {

    private AmneziaConfigFactory() {}

    public static Config build(Context context, ProxySettings settings) throws Exception {
        if (settings == null || settings.backendType == null || !settings.backendType.usesAmneziaSettings()) {
            throw new IllegalArgumentException("AmneziaWG settings required");
        }
        String raw = settings.awgQuickConfig == null ? "" : settings.awgQuickConfig.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("AmneziaWG config is empty");
        }
        Config parsed = Config.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        if (context == null) {
            return parsed;
        }
        boolean overridePeerEndpoint = settings.backendType.usesTurnProxy();
        String localEndpoint = settings.localEndpoint == null ? "" : settings.localEndpoint.trim();
        if (overridePeerEndpoint && localEndpoint.isEmpty()) {
            throw new IllegalArgumentException("Локальный endpoint не заполнен");
        }
        Interface iface = parsed.getInterface();
        Interface.Builder ifaceBuilder = new Interface.Builder().setKeyPair(iface.getKeyPair());
        ifaceBuilder.addAddresses(iface.getAddresses());
        ifaceBuilder.addDnsServers(iface.getDnsServers());
        ifaceBuilder.addDnsSearchDomains(iface.getDnsSearchDomains());
        ifaceBuilder.setListenPort(iface.getListenPort().orElse(0));
        ifaceBuilder.setMtu(iface.getMtu().orElse(0));
        ifaceBuilder.setJunkPacketCount(iface.getJunkPacketCount().orElse(0));
        ifaceBuilder.setJunkPacketMinSize(iface.getJunkPacketMinSize().orElse(0));
        ifaceBuilder.setJunkPacketMaxSize(iface.getJunkPacketMaxSize().orElse(0));
        ifaceBuilder.setInitPacketJunkSize(iface.getInitPacketJunkSize().orElse(0));
        ifaceBuilder.setResponsePacketJunkSize(iface.getResponsePacketJunkSize().orElse(0));
        ifaceBuilder.setCookieReplyPacketJunkSize(iface.getCookieReplyPacketJunkSize().orElse(0));
        ifaceBuilder.setTransportPacketJunkSize(iface.getTransportPacketJunkSize().orElse(0));
        ifaceBuilder.setInitPacketMagicHeader(iface.getInitPacketMagicHeader().orElse(null));
        ifaceBuilder.setResponsePacketMagicHeader(iface.getResponsePacketMagicHeader().orElse(null));
        ifaceBuilder.setUnderloadPacketMagicHeader(iface.getUnderloadPacketMagicHeader().orElse(null));
        ifaceBuilder.setTransportPacketMagicHeader(iface.getTransportPacketMagicHeader().orElse(null));
        ifaceBuilder.setSpecialJunkI1(iface.getSpecialJunkI1().orElse(null));
        ifaceBuilder.setSpecialJunkI2(iface.getSpecialJunkI2().orElse(null));
        ifaceBuilder.setSpecialJunkI3(iface.getSpecialJunkI3().orElse(null));
        ifaceBuilder.setSpecialJunkI4(iface.getSpecialJunkI4().orElse(null));
        ifaceBuilder.setSpecialJunkI5(iface.getSpecialJunkI5().orElse(null));
        Set<String> appRoutingPackages = AppPrefs.getAppRoutingPackages(context);
        if (!appRoutingPackages.isEmpty()) {
            if (AppPrefs.isAppRoutingBypassEnabled(context)) {
                ifaceBuilder.excludeApplications(appRoutingPackages);
            } else {
                ifaceBuilder.includeApplications(appRoutingPackages);
            }
        } else {
            ifaceBuilder.excludeApplications(iface.getExcludedApplications());
            ifaceBuilder.includeApplications(iface.getIncludedApplications());
        }

        Config.Builder configBuilder = new Config.Builder().setInterface(ifaceBuilder.build());
        for (Peer peer : parsed.getPeers()) {
            Peer.Builder peerBuilder = new Peer.Builder()
                .setPublicKey(peer.getPublicKey())
                .addAllowedIps(peer.getAllowedIps());
            if (overridePeerEndpoint) {
                peerBuilder.setEndpoint(InetEndpoint.parse(localEndpoint));
            } else if (peer.getEndpoint().isPresent()) {
                peerBuilder.setEndpoint(peer.getEndpoint().get());
            }
            if (peer.getPreSharedKey().isPresent()) {
                peerBuilder.setPreSharedKey(peer.getPreSharedKey().get());
            }
            if (peer.getPersistentKeepalive().isPresent()) {
                peerBuilder.setPersistentKeepalive(peer.getPersistentKeepalive().get());
            }
            configBuilder.addPeer(peerBuilder.build());
        }
        return configBuilder.build();
    }
}
