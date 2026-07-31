package wings.v.root.rootd;

import android.content.Context;
import android.util.Log;
import androidx.annotation.Nullable;
import java.io.IOException;
import wings.v.BuildConfig;
import wings.v.proto.RootdProto;

/**
 * The single place that answers "is the root helper usable right now?".
 *
 * <p>Callers ask this rather than deciding for themselves, so that adding a root
 * operation cannot accidentally skip the fallback: the module is optional, and with it
 * absent - or speaking a protocol we do not - everything must keep working exactly as
 * it did over su.
 *
 * <p>Availability is probed live, never remembered in prefs. The module can be flashed,
 * updated or removed while the app is installed, and a remembered answer would be a lie
 * the moment the user does any of that.
 */
public final class RootExecutor {

    private static final String TAG = "WINGSV-Rootd";

    /** Oldest daemon protocol this app can still drive. */
    private static final int MIN_SUPPORTED_DAEMON = 1;

    public enum Status {
        /** Not probed yet in this process. */
        UNKNOWN,
        /** Daemon answered and we agree on the protocol. */
        AVAILABLE,
        /** No module, or its daemon is not listening. */
        ABSENT,
        /** Daemon is there but one side is too old; we fall back and warn the user. */
        VERSION_MISMATCH,
    }

    private static final Object LOCK = new Object();
    private static Status status = Status.UNKNOWN;

    @Nullable
    private static RootdClient client;

    @Nullable
    private static RootdProto.HelloReply hello;

    private RootExecutor() {}

    /** Current status without probing; for UI that must not block. */
    public static Status lastKnownStatus() {
        synchronized (LOCK) {
            return status;
        }
    }

    /** The daemon's answer to our hello, or null if we never got one. */
    @Nullable
    public static RootdProto.HelloReply lastHello() {
        synchronized (LOCK) {
            return hello;
        }
    }

    /**
     * Connected client, or null when the module cannot be used. Reconnects if the last
     * connection broke, so a daemon that restarted is picked up without restarting the
     * app.
     */
    @Nullable
    public static RootdClient acquire(Context context) {
        synchronized (LOCK) {
            if (client != null) {
                return client;
            }
            RootdClient connected = RootdClient.connectOrNull();
            if (connected == null) {
                status = Status.ABSENT;
                hello = null;
                return null;
            }
            try {
                RootdProto.HelloReply reply = connected.hello(BuildConfig.VERSION_NAME);
                if (!isCompatible(reply)) {
                    Log.w(
                        TAG,
                        "protocol mismatch: app speaks " +
                            RootdClient.PROTOCOL_VERSION +
                            ", daemon speaks " +
                            reply.getProtocolVersion() +
                            " (min " +
                            reply.getMinSupported() +
                            ")"
                    );
                    connected.close();
                    status = Status.VERSION_MISMATCH;
                    hello = reply;
                    return null;
                }
                hello = reply;
                status = Status.AVAILABLE;
                client = connected;
                return connected;
            } catch (IOException error) {
                Log.w(TAG, "handshake failed", error);
                connected.close();
                status = Status.ABSENT;
                hello = null;
                return null;
            }
        }
    }

    /**
     * Both directions have to hold: we must be new enough for the daemon, and the
     * daemon new enough for us. Either way round is a mismatch.
     */
    private static boolean isCompatible(RootdProto.HelloReply reply) {
        return (
            RootdClient.PROTOCOL_VERSION >= reply.getMinSupported() &&
            reply.getProtocolVersion() >= MIN_SUPPORTED_DAEMON
        );
    }

    /** Drops the connection after an I/O error so the next call redials. */
    public static void invalidate() {
        synchronized (LOCK) {
            if (client != null) {
                client.close();
                client = null;
            }
            if (status == Status.AVAILABLE) {
                status = Status.UNKNOWN;
            }
        }
    }

    /**
     * Session the daemon believes is live, or null when there is no usable daemon.
     * This is what the app reconciles against after being killed, and what the tile
     * shows instead of the app's own snapshot - which goes stale the moment the app
     * dies, precisely when it matters.
     */
    @Nullable
    public static RootdProto.SessionState sessionStateOrNull(Context context) {
        RootdClient active = acquire(context);
        if (active == null) {
            return null;
        }
        try {
            return active.sessionState();
        } catch (IOException error) {
            Log.w(TAG, "session state failed", error);
            invalidate();
            return null;
        }
    }

    /**
     * /proc/net/dev read by the daemon in its own permitted context, or null when there
     * is no usable daemon or it is too old to support the read (no "netdev" cap). The
     * app's own untrusted_app context is denied this read on Android 16, which broke the
     * speed graph and per-interface traffic accounting in root/tproxy mode. See issue #76.
     */
    @Nullable
    public static String readNetDevViaDaemon(Context context) {
        RootdClient active = acquire(context);
        if (active == null) {
            return null;
        }
        RootdProto.HelloReply reply = lastHello();
        if (reply == null || !reply.getCapsList().contains("netdev")) {
            return null;
        }
        try {
            return active.readNetDev();
        } catch (IOException error) {
            Log.w(TAG, "read net dev failed", error);
            invalidate();
            return null;
        }
    }

    /**
     * Bytes matched by the tproxy mark rule, read via the daemon's counters RPC, or null
     * when there is no usable daemon or it is too old (no "counters" cap). This is a cheap
     * socket round trip, so the caller can poll it every sample instead of living with the
     * ~1 Hz throttle that shelling out iptables for the same number forces.
     */
    @Nullable
    public static Long readTproxyMarkBytesViaDaemon(Context context) {
        RootdClient active = acquire(context);
        if (active == null) {
            return null;
        }
        RootdProto.HelloReply reply = lastHello();
        if (reply == null || !reply.getCapsList().contains("counters")) {
            return null;
        }
        try {
            return active.readTproxyMarkBytes();
        } catch (IOException error) {
            Log.w(TAG, "read tproxy mark bytes failed", error);
            invalidate();
            return null;
        }
    }
}
