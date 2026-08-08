package wings.v.root.rootd;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import wings.v.proto.RootdProto;

/**
 * Talks to wingsvd, the optional root helper module.
 *
 * <p>Wire is a big-endian int32 length prefix plus one protobuf message, the same shape
 * the vpnhotspot daemon uses. There is no token: the daemon authenticates us by
 * SO_PEERCRED, which the kernel fills in, so a token would only be a second, weaker
 * copy of a check that already cannot be forged.
 *
 * <p>Every method is best-effort by design. The module is optional, so a missing socket
 * or a broken daemon must read as "not available" and send the caller back to the su
 * path - never as a failure the user sees.
 */
public final class RootdClient implements Closeable {

    private static final String TAG = "WINGSV-Rootd";
    private static final String SOCKET_NAME = "wings.v.rootd";
    private static final int MAX_FRAME_SIZE = 1024 * 1024;
    // A ceiling, not an expected latency: calls are serialized and return the moment the
    // reply lands. It has to clear the worst case - apply_routing running several iptables
    // and ip rule invocations on a slow device under load - or a real success reads as a
    // timeout, drops the daemon connection and forces the su fallback.
    private static final int IO_TIMEOUT_MS = 10000;

    /** Bumped only when semantics change; added fields do not move it. */
    public static final int PROTOCOL_VERSION = 1;

    private final LocalSocket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final AtomicLong nextCallId = new AtomicLong(1);
    // Serializes each write+read round trip. The client is shared across threads
    // (routing apply/clear plus the traffic and net-dev pollers), and interleaving
    // two calls on the one socket desynchronizes the framing - call-id mismatch or a
    // broken pipe - which drops the daemon connection and forces the su fallback.
    private final Object callLock = new Object();

    private RootdClient(LocalSocket socket) throws IOException {
        this.socket = socket;
        this.input = new DataInputStream(socket.getInputStream());
        this.output = new DataOutputStream(socket.getOutputStream());
    }

    /** Connects to the daemon, or returns null when the module is not installed. */
    public static RootdClient connectOrNull() {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(IO_TIMEOUT_MS);
            return new RootdClient(socket);
        } catch (IOException error) {
            // No module, or it is not up yet. Both are normal.
            try {
                socket.close();
            } catch (IOException ignored) {}
            return null;
        }
    }

    public RootdProto.HelloReply hello(String appVersion) throws IOException {
        RootdProto.ClientEnvelope request = envelope()
            .setHello(
                RootdProto.HelloCommand.newBuilder()
                    .setProtocolVersion(PROTOCOL_VERSION)
                    .setAppVersion(appVersion == null ? "" : appVersion)
            )
            .build();
        return call(request).getHello();
    }

    public void applyRouting(RootdProto.RoutingSpec spec) throws IOException {
        call(envelope().setApplyRouting(RootdProto.ApplyRoutingCommand.newBuilder().setSpec(spec)).build());
    }

    public void clearRouting() throws IOException {
        call(envelope().setClearRouting(RootdProto.ClearRoutingCommand.getDefaultInstance()).build());
    }

    public void applyForwardedRedirect(RootdProto.ForwardedRedirectSpec spec) throws IOException {
        call(
            envelope()
                .setApplyForwardedRedirect(RootdProto.ApplyForwardedRedirectCommand.newBuilder().setSpec(spec))
                .build()
        );
    }

    public void applyForwardedExclusion(RootdProto.ForwardedExclusionSpec spec) throws IOException {
        call(
            envelope()
                .setApplyForwardedExclusion(RootdProto.ApplyForwardedExclusionCommand.newBuilder().setSpec(spec))
                .build()
        );
    }

    public RootdProto.SessionState sessionState() throws IOException {
        return call(
            envelope().setSessionState(RootdProto.SessionStateCommand.getDefaultInstance()).build()
        ).getSessionState();
    }

    public RootdProto.ChildHandle spawnChild(RootdProto.SpawnChildCommand command) throws IOException {
        return call(envelope().setSpawnChild(command).build()).getChild();
    }

    public void killChild(long childId) throws IOException {
        call(envelope().setKillChild(RootdProto.KillChildCommand.newBuilder().setChildId(childId)).build());
    }

    public long readTproxyMarkBytes() throws IOException {
        return call(envelope().setReadCounters(RootdProto.ReadCountersCommand.getDefaultInstance()).build())
            .getCounters()
            .getTproxyMarkBytes();
    }

    /** Raw /proc/net/dev the daemon reads in its permitted context. Needs the "netdev" cap. */
    public String readNetDev() throws IOException {
        return call(envelope().setReadNetDev(RootdProto.ReadNetDevCommand.getDefaultInstance()).build())
            .getNetDev()
            .getContent();
    }

    private RootdProto.ClientEnvelope.Builder envelope() {
        return RootdProto.ClientEnvelope.newBuilder().setCallId(nextCallId.getAndIncrement());
    }

    private RootdProto.ReplyFrame call(RootdProto.ClientEnvelope request) throws IOException {
        synchronized (callLock) {
            writeFrame(request.toByteArray());
            RootdProto.DaemonEnvelope response = RootdProto.DaemonEnvelope.parseFrom(readFrame());
            if (response.getCallId() != request.getCallId()) {
                throw new IOException(
                    "call id mismatch: sent " + request.getCallId() + ", got " + response.getCallId()
                );
            }
            if (response.hasError()) {
                throw new IOException("wingsvd: " + response.getError().getMessage());
            }
            if (!response.hasReply()) {
                throw new IOException("wingsvd sent an empty frame");
            }
            return response.getReply();
        }
    }

    private void writeFrame(byte[] body) throws IOException {
        output.writeInt(body.length);
        output.write(body);
        output.flush();
    }

    private byte[] readFrame() throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("wingsvd sent a frame of " + length + " bytes");
        }
        byte[] body = new byte[length];
        input.readFully(body);
        return body;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException error) {
            Log.w(TAG, "closing the rootd socket failed", error);
        }
    }
}
