package wings.v.guardian;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import wings.v.core.AppPrefs;
import wings.v.proto.GuardianProto;
import wings.v.service.ProxyTunnelService;

public final class GuardianCommandHandler {

    private static final String TAG = "GuardianCmd";

    /**
     * Outbound channel used to ship command responses back to the panel. A
     * caller that has a live WS client (foreground client, periodic worker)
     * passes a responder backed by {@code client.sendFrame}; the background
     * paths used to pass {@code null} here, which is exactly why command acks
     * and REPORT_NOW snapshots never left the device.
     */
    @FunctionalInterface
    public interface Responder {
        void send(GuardianProto.Frame frame);
    }

    private GuardianCommandHandler() {}

    public static void handle(Context context, GuardianProto.Command command, Responder responder) {
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
                    sendStateReport(ctx, responder);
                    break;
                case COMMAND_TYPE_REFRESH_SUBSCRIPTION:
                case COMMAND_TYPE_REFRESH_ALL_SUBSCRIPTIONS:
                    refreshSubscriptionsAsync(ctx, responder);
                    break;
                case COMMAND_TYPE_REFRESH_INSTALLED_APPS:
                    sendInstalledAppsAsync(ctx, responder);
                    break;
                case COMMAND_TYPE_GENERATE_VK_LINK:
                    // VK link generation needs the foreground VK OAuth session;
                    // background sync without a live UI cannot mint one. Ack
                    // below so the panel stops waiting.
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
        sendAck(responder, command.getId(), ok, error);
    }

    static void sendAck(Responder responder, String commandId, boolean ok, String error) {
        if (responder == null) {
            return;
        }
        responder.send(
            GuardianProto.Frame.newBuilder()
                .setCommandAck(
                    GuardianProto.CommandAck.newBuilder()
                        .setId(commandId)
                        .setOk(ok)
                        .setErrorMessage(error == null ? "" : error)
                )
                .build()
        );
    }

    static void sendStateReport(Context context, Responder responder) {
        if (responder == null) {
            return;
        }
        responder.send(buildStateReport(context));
    }

    private static void sendInstalledAppsAsync(Context context, Responder responder) {
        if (responder == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(
            () -> {
                try {
                    GuardianProto.InstalledApps inventory = InstalledAppsBuilder.build(appContext);
                    if (inventory != null) {
                        responder.send(GuardianProto.Frame.newBuilder().setInstalledApps(inventory).build());
                    }
                } catch (Throwable error) {
                    ProxyTunnelService.writeRuntimeLogLine(
                        "[guardian] installed apps inventory failed: " + error.getMessage()
                    );
                }
            },
            "guardian-apps-inventory"
        )
            .start();
    }

    private static void refreshSubscriptionsAsync(Context context, Responder responder) {
        Context appContext = context.getApplicationContext();
        new Thread(
            () -> {
                try {
                    wings.v.core.XraySubscriptionUpdater.refreshAll(appContext);
                    ProxyTunnelService.writeRuntimeLogLine("[guardian] subscription refresh ok");
                    sendStateReport(appContext, responder);
                } catch (Exception error) {
                    ProxyTunnelService.writeRuntimeLogLine(
                        "[guardian] subscription refresh failed: " + error.getMessage()
                    );
                }
            },
            "guardian-sub-refresh"
        )
            .start();
    }

    static GuardianProto.Frame buildStateReport(Context context) {
        Context ctx = context.getApplicationContext();
        boolean vkAuthorized = wings.v.vk.VkOAuthAuth.isClientConfigured() && wings.v.vk.VkOAuthAuth.isAuthorized(ctx);
        GuardianProto.RuntimeState runtime = GuardianProto.RuntimeState.newBuilder()
            .setTunnelActive(ProxyTunnelService.isActive())
            .setPhase(GuardianProto.TunnelPhase.TUNNEL_PHASE_UNSPECIFIED)
            .setHasRootAccess(wings.v.core.RootUtils.isRootAccessGranted(ctx))
            .setVkOauthAuthorized(vkAuthorized)
            .build();
        GuardianProto.StateReport.Builder report = GuardianProto.StateReport.newBuilder().setRuntime(runtime);
        try {
            wings.v.proto.WingsvProto.Config snapshot = wings.v.core.WingsImportParser.buildGuardianSnapshotProto(ctx);
            // Strip Guardian credentials from the snapshot we send back —
            // they're already known to the panel and including them would only
            // leak the token through StateReport echoes.
            wings.v.proto.WingsvProto.Config sanitised = snapshot.toBuilder().clearGuardian().build();
            report.setSnapshot(sanitised);
        } catch (Exception error) {
            Log.w(TAG, "snapshot build failed: " + error.getMessage());
        }
        return GuardianProto.Frame.newBuilder().setStateReport(report).build();
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
            AppPrefs.applyImportedConfig(ctx, imported);
            long version = push.getConfig().getConfigVersion();
            if (version > 0) {
                AppPrefs.setGuardianLastAppliedConfigVersion(ctx, version);
            }
        } catch (Exception error) {
            Log.w(TAG, "config push apply failed: " + error.getMessage());
        }
    }
}
