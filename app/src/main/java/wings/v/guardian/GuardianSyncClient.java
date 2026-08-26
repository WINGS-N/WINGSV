package wings.v.guardian;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import wings.v.core.AppPrefs;
import wings.v.proto.GuardianGrpc;
import wings.v.proto.GuardianProto;
import wings.v.service.ProxyTunnelService;

/**
 * Background Guardian sync over a single gRPC round trip.
 *
 * <p>The periodic path used to open the live channel and sit on it for a fixed
 * budget hoping frames would arrive, which is exactly what a dozing device is
 * worst at: the handshake, the reconnect backoff and the heartbeat watchdog all
 * had to succeed inside a window the system was actively trying to close. Here
 * the device posts its state and takes back everything the panel had queued in
 * one request, so the work is done in a round trip rather than a vigil.
 */
public final class GuardianSyncClient {

    private static final String TAG = "GuardianSync";
    private static final long CALL_DEADLINE_SECONDS = 25L;
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;
    // Commands can produce acks and a fresh report, which ride the next request.
    // Two follow-ups is enough for a command that queues another one, and bounds
    // a panel that would otherwise keep us going indefinitely.
    private static final int MAX_FOLLOW_UPS = 2;

    private GuardianSyncClient() {}

    /** Runs one sync exchange. Returns false when the panel could not be reached. */
    public static boolean sync(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (GuardianEndpoint.host(app).isEmpty()) {
            return false;
        }
        ManagedChannel channel = GuardianEndpoint.openChannel(app, true);
        try {
            return exchange(app, channel);
        } catch (StatusRuntimeException error) {
            Log.w(TAG, "sync failed: " + error.getStatus());
            ProxyTunnelService.writeRuntimeLogLine("[guardian] grpc sync failed: " + error.getStatus());
            return false;
        } finally {
            shutdown(channel);
        }
    }

    private static boolean exchange(@NonNull Context app, @NonNull ManagedChannel channel) {
        GuardianProto.StateReport report = GuardianCommandHandler.buildStateReport(app).getStateReport();
        List<GuardianProto.Frame> pending = new ArrayList<>();
        GuardianProto.SyncRequest.Builder request = GuardianProto.SyncRequest.newBuilder()
            .setHello(GuardianEndpoint.buildHello(app))
            .setState(report);

        for (int round = 0; round <= MAX_FOLLOW_UPS; round++) {
            GuardianProto.SyncResponse response = stub(channel).sync(request.build());
            if (!response.getHello().getAccepted()) {
                Log.w(TAG, "panel rejected sync: " + response.getHello().getErrorMessage());
                ProxyTunnelService.writeRuntimeLogLine(
                    "[guardian] grpc sync rejected: " + response.getHello().getErrorMessage()
                );
                return false;
            }
            GuardianStateBroadcast.send(app, true, GuardianEndpoint.host(app));
            applyResponse(app, response);

            pending.clear();
            for (GuardianProto.Command command : response.getCommandsList()) {
                GuardianCommandHandler.handle(app, command, pending::add);
            }
            if (pending.isEmpty()) {
                return true;
            }
            request = followUp(app, pending);
        }
        return true;
    }

    private static void applyResponse(@NonNull Context app, @NonNull GuardianProto.SyncResponse response) {
        if (response.hasLogControl()) {
            GuardianProto.LogControl control = response.getLogControl();
            AppPrefs.setGuardianLogControl(
                app,
                control.getRuntimeEnabled(),
                control.getProxyEnabled(),
                control.getXrayEnabled()
            );
        }
        if (response.hasConfigPush()) {
            GuardianCommandHandler.applyConfigPush(app, response.getConfigPush());
        }
    }

    /**
     * Folds whatever the command handlers produced into the next request. Acks and
     * installed-app lists travel as their own fields; a state report replaces the
     * one we opened with so the panel sees the post-command result.
     */
    private static GuardianProto.SyncRequest.Builder followUp(
        @NonNull Context app,
        @NonNull List<GuardianProto.Frame> frames
    ) {
        GuardianProto.SyncRequest.Builder next = GuardianProto.SyncRequest.newBuilder().setHello(
            GuardianEndpoint.buildHello(app)
        );
        boolean reported = false;
        for (GuardianProto.Frame frame : frames) {
            if (frame.hasCommandAck()) {
                next.addAcks(frame.getCommandAck());
            } else if (frame.hasStateReport()) {
                next.setState(frame.getStateReport());
                reported = true;
            } else if (frame.hasInstalledApps()) {
                next.setInstalledApps(frame.getInstalledApps());
            }
        }
        if (!reported) {
            next.setState(GuardianCommandHandler.buildStateReport(app).getStateReport());
        }
        return next;
    }

    private static GuardianGrpc.GuardianBlockingStub stub(@NonNull ManagedChannel channel) {
        return GuardianGrpc.newBlockingStub(channel).withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    private static void shutdown(@NonNull ManagedChannel channel) {
        channel.shutdownNow();
        try {
            channel.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
