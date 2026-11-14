package wings.v.guardian;

import android.content.Context;
import wings.v.core.AppPrefs;
import wings.v.proto.GuardianProto;

/**
 * In-activity Guardian connection for the FOREGROUND_ONLY sync mode. No service, no
 * notification: it lives only while some activity of ours is on screen
 */
public final class GuardianForegroundClient {

    private static GuardianClient client;

    private GuardianForegroundClient() {}

    /** True while an in-activity foreground connection owns the guardian socket */
    public static synchronized boolean isActive() {
        return client != null;
    }

    public static synchronized void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!AppPrefs.isGuardianEnabled(app) || !AppPrefs.isGuardianConfigured(app)) {
            stop();
            return;
        }
        if (client != null) return;
        client = new GuardianClient(
            app,
            new GuardianClient.Listener() {
                @Override
                public void onConnected(String host) {
                    GuardianStateBroadcast.send(app, true, host);
                    // Snapshot on connect, same as GuardianService: waiting for a
                    // REPORT_NOW that may never come leaves the panel with a hello and no
                    // config at all
                    GuardianCommandHandler.sendStateReport(app, frame -> {
                        if (client != null) {
                            client.sendFrame(frame);
                        }
                    });
                }

                @Override
                public void onDisconnected() {
                    GuardianStateBroadcast.send(app, false, "");
                }

                @Override
                public void onCommand(GuardianProto.Command command) {
                    GuardianCommandHandler.handle(app, command, frame -> {
                        if (client != null) {
                            client.sendFrame(frame);
                        }
                    });
                }

                @Override
                public void onConfigPush(GuardianProto.ConfigPush push) {
                    GuardianCommandHandler.applyConfigPush(app, push);
                }

                @Override
                public void onLogControl(GuardianProto.LogControl control) {
                    AppPrefs.setGuardianLogControl(
                        app,
                        control.getRuntimeEnabled(),
                        control.getProxyEnabled(),
                        control.getXrayEnabled()
                    );
                }

                @Override
                public void requestStateReport() {}
            }
        );
        client.start();
    }

    public static synchronized void stop() {
        if (client != null) {
            client.stop();
            client = null;
        }
    }
}
