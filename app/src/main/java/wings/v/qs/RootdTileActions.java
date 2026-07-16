package wings.v.qs;

import android.content.Context;
import android.util.Log;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import wings.v.proto.RootdProto;
import wings.v.root.rootd.RootExecutor;
import wings.v.root.rootd.RootdClient;

/**
 * Lets the tile reach the root helper without going through the tunnel service.
 *
 * <p>This is the case the module exists for. When Android kills the app, its routing
 * session lives on in the daemon, and the app's own state snapshot is worthless -
 * ProxyTunnelService.isActive() reads a store that nobody has updated since the process
 * died. The tile, on the other hand, is always reachable: SystemUI binds it when the
 * shade opens and Android starts the process for the binding. So the tile asks the
 * daemon directly and, if a session is up, ends it - with no service to start, no
 * foreground notification, and nothing that can lose a race with the system.
 */
final class RootdTileActions {

    private static final String TAG = "WINGSV-Rootd";
    /** The tile click runs on the main thread; socket work must not. */
    private static final long CALL_TIMEOUT_MS = 2500;

    private RootdTileActions() {}

    /**
     * Ends a session the daemon is holding for an app that is no longer around.
     * Returns whether there was one, so the caller can tell a stop from a start.
     */
    static boolean stopOrphanedSession(Context context) {
        Boolean stopped = runBlocking(() -> {
            RootdClient client = RootExecutor.acquire(context);
            if (client == null) {
                return false;
            }
            RootdProto.SessionState state = client.sessionState();
            if (!state.getRoutingActive()) {
                return false;
            }
            client.clearRouting();
            return true;
        });
        return Boolean.TRUE.equals(stopped);
    }

    /**
     * Runs the call off the main thread but waits for it: a tile click has to decide
     * what it did before it returns, and the daemon is local, so the wait is short.
     * A daemon that hangs must not take the system UI with it, hence the timeout.
     */
    private static Boolean runBlocking(Callable<Boolean> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> future = executor.submit(work);
            return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            Log.w(TAG, "tile call to wingsvd failed", error);
            RootExecutor.invalidate();
            return false;
        } finally {
            executor.shutdownNow();
        }
    }
}
