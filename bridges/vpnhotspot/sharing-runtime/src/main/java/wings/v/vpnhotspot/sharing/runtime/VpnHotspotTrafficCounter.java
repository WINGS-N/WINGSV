package wings.v.vpnhotspot.sharing.runtime;

public final class VpnHotspotTrafficCounter {
    private final byte[] mac;
    private final String downstream;
    private final long sentBytes;
    private final long sentPackets;
    private final long receivedBytes;
    private final long receivedPackets;

    public VpnHotspotTrafficCounter(
            byte[] mac,
            String downstream,
            long sentBytes,
            long sentPackets,
            long receivedBytes,
            long receivedPackets
    ) {
        this.mac = mac;
        this.downstream = downstream;
        this.sentBytes = sentBytes;
        this.sentPackets = sentPackets;
        this.receivedBytes = receivedBytes;
        this.receivedPackets = receivedPackets;
    }

    public byte[] getMac() {
        return mac;
    }

    public String getDownstream() {
        return downstream;
    }

    public long getSentBytes() {
        return sentBytes;
    }

    public long getSentPackets() {
        return sentPackets;
    }

    public long getReceivedBytes() {
        return receivedBytes;
    }

    public long getReceivedPackets() {
        return receivedPackets;
    }
}
