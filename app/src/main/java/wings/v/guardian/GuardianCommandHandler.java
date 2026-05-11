package wings.v.guardian;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import wings.v.proto.GuardianProto;
import wings.v.service.ProxyTunnelService;

public final class GuardianCommandHandler {

    private static final String TAG = "GuardianCmd";

    @FunctionalInterface
    public interface AckSender {
        void ack(GuardianProto.CommandAck ack);
    }

    private GuardianCommandHandler() {}

    public static void handle(Context context, GuardianProto.Command command, AckSender ackSender) {
        Context ctx = context.getApplicationContext();
        boolean ok = true;
        String error = "";
        try {
            switch (command.getType()) {
                case COMMAND_TYPE_START_TUNNEL:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(ProxyTunnelService.createStartIntent(ctx));
                    } else {
                        ctx.startService(ProxyTunnelService.createStartIntent(ctx));
                    }
                    break;
                case COMMAND_TYPE_STOP_TUNNEL:
                    ProxyTunnelService.requestStop(ctx);
                    break;
                case COMMAND_TYPE_RECONNECT:
                    ProxyTunnelService.requestReconnect(ctx, "guardian-command");
                    break;
                case COMMAND_TYPE_REPORT_NOW:
                    // The caller's listener handles re-sending state report.
                    break;
                case COMMAND_TYPE_REFRESH_SUBSCRIPTION:
                case COMMAND_TYPE_REFRESH_ALL_SUBSCRIPTIONS:
                    new Thread(() -> {
                        try {
                            wings.v.core.XraySubscriptionUpdater.refreshAll(ctx);
                        } catch (Exception ignored) {}
                    })
                        .start();
                    break;
                case COMMAND_TYPE_REFRESH_INSTALLED_APPS:
                    // Handled by the caller (it has access to the WS client).
                    break;
                default:
                    ok = false;
                    error = "unknown command";
            }
        } catch (Throwable t) {
            ok = false;
            error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            Log.w(TAG, "command failed: " + error);
        }
        if (ackSender != null) {
            ackSender.ack(
                GuardianProto.CommandAck.newBuilder()
                    .setId(command.getId())
                    .setOk(ok)
                    .setErrorMessage(error == null ? "" : error)
                    .build()
            );
        }
    }

    public static void applyConfigPush(Context context, GuardianProto.ConfigPush push) {
        Context ctx = context.getApplicationContext();
        if (push == null || push.getConfig() == null) return;
        try {
            wings.v.core.WingsImportParser.ImportedConfig imported = wings.v.core.WingsImportParser.parseProtoConfig(
                push.getConfig()
            );
            // Strip Guardian credentials but keep sync_mode/interval — those
            // are panel-driven behavioural knobs and must propagate live.
            imported.guardianWsUrl = null;
            imported.guardianClientId = null;
            imported.guardianClientToken = null;
            imported.guardianClientName = null;
            wings.v.core.AppPrefs.applyImportedConfig(ctx, imported);
            long version = push.getConfig().getConfigVersion();
            if (version > 0) {
                wings.v.core.AppPrefs.setGuardianLastAppliedConfigVersion(ctx, version);
            }
        } catch (Exception error) {
            Log.w(TAG, "config push apply failed: " + error.getMessage());
        }
    }
}
