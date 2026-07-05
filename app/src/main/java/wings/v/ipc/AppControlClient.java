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
        // An IP-literal target avoids a real DNS lookup (grpc's dns resolver
        // short-circuits literals), so the custom socketFactory connects the unix
        // socket without resolving "localhost" - which fails under the active
        // VPN/restricted network (connection never attempted -> UNAVAILABLE).
        this.channel = OkHttpChannelBuilder.forTarget("127.0.0.1:1")
            .socketFactory(new LocalSocketChannelFactory(socketPath))
            .overrideAuthority("localhost")
            .usePlaintext()
            .build();
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
