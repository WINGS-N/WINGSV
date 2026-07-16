package wings.v.xray;

import com.xray.app.stats.command.GetStatsRequest;
import com.xray.app.stats.command.GetStatsResponse;
import com.xray.app.stats.command.StatsServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import wings.v.ipc.LocalSocketChannelFactory;

/**
 * Reads xray's own traffic counters over the StatsService gRPC api.
 *
 * <p>The counters are exact and per-direction, which the modes that have no tun
 * interface cannot get any other way: in TPROXY mode xray runs in a root subprocess, so
 * neither a tun device nor this app's uid counters see the relayed bytes, and the
 * previous reconstruction (loopback tx minus the marked tx) counted background loopback
 * traffic - DNS, binder-over-tcp - as inbound noise.
 *
 * <p>There is no token: xray's commander authenticates nothing at all, so the socket
 * itself is the access control - it is a filesystem socket owned by this app and chmodded
 * 0600 by xray, and on the root path the helper relabels it to this app's SELinux
 * context. A foreign app is refused by the kernel at connect() rather than by a check we
 * would have to get right.
 */
public final class XrayStatsClient implements Closeable {

    // Minted by app/proxyman/outbound/handler.go once policy.system.statsOutboundUplink
    // and statsOutboundDownlink are set.
    private static final String UPLINK_COUNTER = "outbound>>>proxy>>>traffic>>>uplink";
    private static final String DOWNLINK_COUNTER = "outbound>>>proxy>>>traffic>>>downlink";
    private static final long CALL_TIMEOUT_MS = 800L;

    private final ManagedChannel channel;
    private final StatsServiceGrpc.StatsServiceBlockingStub stub;
    private volatile String lastError = "";

    public XrayStatsClient(String socketPath) {
        // An IP-literal target keeps grpc's dns resolver from resolving "localhost",
        // which fails under the active VPN; the socket factory then ignores it and
        // connects the unix socket. Same reasoning as AppControlClient.
        this.channel = OkHttpChannelBuilder.forTarget("127.0.0.1:1")
            .socketFactory(new LocalSocketChannelFactory(socketPath))
            .overrideAuthority("localhost")
            .usePlaintext()
            .build();
        this.stub = StatsServiceGrpc.newBlockingStub(channel);
    }

    /** Absolute bytes sent through the proxy outbound, or -1 when unavailable. */
    public long readUplinkBytes() {
        return readCounter(UPLINK_COUNTER);
    }

    /** Absolute bytes received through the proxy outbound, or -1 when unavailable. */
    public long readDownlinkBytes() {
        return readCounter(DOWNLINK_COUNTER);
    }

    /** Why the last read failed, for the caller to log. Empty while things work. */
    public String lastError() {
        return lastError;
    }

    private long readCounter(String name) {
        try {
            GetStatsResponse response = stub
                .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .getStats(GetStatsRequest.newBuilder().setName(name).setReset(false).build());
            lastError = "";
            return response.hasStat() ? response.getStat().getValue() : 0L;
        } catch (RuntimeException error) {
            // A counter that has seen no traffic yet does not exist, which surfaces as
            // NOT_FOUND rather than a zero - treat it as unavailable and let the caller
            // keep its previous sample instead of reporting a bogus drop to zero.
            // Keep the reason: falling back silently leaves no way to tell a missing
            // socket from a denied one from an idle counter.
            lastError = name + ": " + error.getMessage();
            return -1L;
        }
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
