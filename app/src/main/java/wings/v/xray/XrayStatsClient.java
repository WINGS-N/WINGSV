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
 * <p>Exact and per-direction, and for the modes without a tun interface there is no other
 * source: in TPROXY mode xray lives in a root subprocess, so neither a tun device nor this
 * app's uid counters ever see the relayed bytes.
 *
 * <p>No token anywhere: xray's commander authenticates nothing at all, so the socket is
 * the access control - a filesystem socket owned by this app, chmodded 0600 by xray, and
 * relabelled to this app's SELinux context by the helper on the root path. The kernel
 * refuses a foreign app at connect(), instead of a check we would have to get right
 */
public final class XrayStatsClient implements Closeable {

    // Minted by app/proxyman/outbound/handler.go once policy.system.statsOutboundUplink
    // and statsOutboundDownlink are set
    private static final String UPLINK_COUNTER = "outbound>>>proxy>>>traffic>>>uplink";
    private static final String DOWNLINK_COUNTER = "outbound>>>proxy>>>traffic>>>downlink";
    private static final long CALL_TIMEOUT_MS = 800L;

    private final ManagedChannel channel;
    private final StatsServiceGrpc.StatsServiceBlockingStub stub;
    private volatile String lastError = "";

    public XrayStatsClient(String socketPath) {
        // An IP-literal target keeps grpc's dns resolver away from "localhost", which it
        // cannot resolve under the active VPN; the socket factory ignores the target
        // anyway and connects the unix socket. Same reasoning as AppControlClient
        this.channel = OkHttpChannelBuilder.forTarget("127.0.0.1:1")
            .socketFactory(new LocalSocketChannelFactory(socketPath))
            .overrideAuthority("localhost")
            .usePlaintext()
            .build();
        this.stub = StatsServiceGrpc.newBlockingStub(channel);
    }

    /** Absolute bytes sent through the proxy outbound, or -1 when unavailable */
    public long readUplinkBytes() {
        return readCounter(UPLINK_COUNTER);
    }

    /** Absolute bytes received through the proxy outbound, or -1 when unavailable */
    public long readDownlinkBytes() {
        return readCounter(DOWNLINK_COUNTER);
    }

    /** Why the last read failed, for the caller to log. Empty while things work */
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
            // A counter that has seen no traffic does not exist yet and comes back as
            // NOT_FOUND, not as a zero: report it unavailable so the caller keeps its
            // previous sample instead of a bogus drop to zero. The reason is kept because
            // otherwise a missing socket, a denied one and an idle counter look alike
            lastError = name + ": " + error.getMessage();
            return -1L;
        }
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
