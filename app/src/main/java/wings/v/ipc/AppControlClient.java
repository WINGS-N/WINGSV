package wings.v.ipc;

import com.google.protobuf.ByteString;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import wings.v.proto.appcontrol.AppControlGrpc;
import wings.v.proto.appcontrol.AppControlProto;

/**
 * Client for the vk-turn-proxy AppControl IPC. It talks to the embedded client
 * (libvkturn.so) over its unix domain socket via grpc-okhttp, presenting the
 * shared bearer token, to push VK cookies in, pull rotated cookies out, and
 * request a provisioned WireGuard config.
 */
public final class AppControlClient implements Closeable {

    private final ManagedChannel channel;
    private final AppControlGrpc.AppControlBlockingStub stub;

    public AppControlClient(String socketPath, String token) {
        if (socketPath.startsWith("tcp:")) {
            // Root/kernel-WG path: the relay listens on 127.0.0.1:port (SELinux forbids
            // the unix-socket connectto to its priv_app domain). The bearer token below
            // is the access control against other local apps.
            this.channel = OkHttpChannelBuilder.forTarget(socketPath.substring("tcp:".length()))
                .overrideAuthority("localhost")
                .usePlaintext()
                .build();
        } else {
            // An IP-literal target avoids a real DNS lookup (grpc's dns resolver
            // short-circuits literals), so the custom socketFactory connects the unix
            // socket without resolving "localhost" - which fails under the active
            // VPN/restricted network (connection never attempted -> UNAVAILABLE).
            this.channel = OkHttpChannelBuilder.forTarget("127.0.0.1:1")
                .socketFactory(new LocalSocketChannelFactory(socketPath))
                .overrideAuthority("localhost")
                .usePlaintext()
                .build();
        }
        AppControlGrpc.AppControlBlockingStub built = AppControlGrpc.newBlockingStub(channel);
        if (token != null && !token.isEmpty()) {
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
            ClientInterceptor interceptor = MetadataUtils.newAttachHeadersInterceptor(headers);
            built = built.withInterceptors(interceptor);
        }
        this.stub = built;
    }

    public void setVkCookies(String cookies, String userAgent) {
        stub.setVKCookies(
            AppControlProto.SetVKCookiesRequest.newBuilder()
                .setCookies(cookies == null ? "" : cookies)
                .setUserAgent(userAgent == null ? "" : userAgent)
                .build()
        );
    }

    public AppControlProto.GetVKCookiesResponse getVkCookies() {
        return stub.getVKCookies(AppControlProto.GetVKCookiesRequest.getDefaultInstance());
    }

    public AppControlProto.Telemetry getTelemetry() {
        return stub.getTelemetry(AppControlProto.GetTelemetryRequest.getDefaultInstance());
    }

    // Blocking server stream: each next() returns as soon as the relay pushes a new
    // connected-stream count, so telemetry is event-driven with no poll latency.
    public java.util.Iterator<AppControlProto.Telemetry> streamTelemetry() {
        return stub.streamTelemetry(AppControlProto.StreamTelemetryRequest.getDefaultInstance());
    }

    // Blocking server stream of relay control events (status/caps/captcha/lockout/
    // vk-auth) that formerly rode the stdout PROXY_EVENT JSONL lines. next() returns
    // the moment the relay pushes an event.
    public java.util.Iterator<AppControlProto.ProxyEvent> streamEvents() {
        return stub.streamEvents(AppControlProto.StreamEventsRequest.getDefaultInstance());
    }

    // Delivers the VK TURN credentials intercepted in the sign-in WebView (or a
    // cancel) back to the relay, replacing the vk_account_creds stdin line. Works on
    // the root/kernel-WG path too, where the relay has no writable stdin.
    public void submitVkAccountCreds(
        String link,
        String username,
        String credential,
        java.util.List<String> turnUrls,
        boolean cancel
    ) {
        AppControlProto.VKAccountCredsRequest.Builder request = AppControlProto.VKAccountCredsRequest.newBuilder()
            .setLink(link == null ? "" : link)
            .setUsername(username == null ? "" : username)
            .setCredential(credential == null ? "" : credential)
            .setCancel(cancel);
        if (turnUrls != null) {
            for (String url : turnUrls) {
                if (url != null && !url.isEmpty()) {
                    request.addTurnUrls(url);
                }
            }
        }
        stub.submitVKAccountCreds(request.build());
    }

    public AppControlProto.ProvisionResponse provision(String clientId, byte[] token, String hwid, int localPort) {
        return stub.provision(
            AppControlProto.ProvisionRequest.newBuilder()
                .setClientId(clientId == null ? "" : clientId)
                .setToken(ByteString.copyFrom(token == null ? new byte[0] : token))
                .setHwid(hwid == null ? "" : hwid)
                .setLocalPort(localPort)
                .build()
        );
    }

    public AppControlProto.ConfigureResponse configure(AppControlProto.ConfigureRequest request) {
        return stub.configure(request);
    }

    // Live-applies a delta of runtime-mutable settings without a relay restart. Only
    // the fields set on the request are patched; the relay reports per-field progress
    // over StreamEvents (PatchStatusEvent), so this returns as soon as the patch is
    // accepted.
    public AppControlProto.PatchConfigResponse patchConfig(AppControlProto.PatchConfigRequest request) {
        return stub.patchConfig(request);
    }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
