package wings.v.guardian;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import java.util.LinkedHashSet;
import java.util.Set;

/** In-process listener registry for GuardianService state changes. */
public final class GuardianStateBroadcast {

    public interface Listener {
        void onGuardianStateChanged(boolean connected, String host);
    }

    private static final Set<Listener> LISTENERS = new LinkedHashSet<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile boolean lastConnected;
    private static volatile String lastHost = "";

    private GuardianStateBroadcast() {}

    public static void send(Context context, boolean connected, String host) {
        lastConnected = connected;
        lastHost = host == null ? "" : host;
        Listener[] snapshot;
        synchronized (LISTENERS) {
            snapshot = LISTENERS.toArray(new Listener[0]);
        }
        for (Listener l : snapshot) {
            MAIN_HANDLER.post(() -> l.onGuardianStateChanged(lastConnected, lastHost));
        }
    }

    public static void register(@NonNull Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.add(listener);
        }
        MAIN_HANDLER.post(() -> listener.onGuardianStateChanged(lastConnected, lastHost));
    }

    public static void unregister(@NonNull Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }
}
