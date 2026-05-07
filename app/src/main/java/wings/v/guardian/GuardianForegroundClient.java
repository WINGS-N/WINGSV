package wings.v.guardian;

import android.content.Context;
import wings.v.core.AppPrefs;
import wings.v.proto.GuardianProto;

/**
 * In-activity Guardian connection used by the FOREGROUND_ONLY sync mode.
 * No service, no notification: lives only while the user keeps the app
 * (any of its activities) on screen.
 */
public final class GuardianForegroundClient {

    private static GuardianClient client;

    private GuardianForegroundClient() {}

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
                }

                @Override
                public void onDisconnected() {
                    GuardianStateBroadcast.send(app, false, "");
                }

                @Override
                public void onCommand(GuardianProto.Command command) {
                    GuardianCommandHandler.handle(app, command, ack -> {
                        if (client != null) {
                            client.sendFrame(GuardianProto.Frame.newBuilder().setCommandAck(ack).build());
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
