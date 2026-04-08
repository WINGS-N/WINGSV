package wings.v.service;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidUsingVolatile",
        "PMD.NullAssignment",
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.FieldDeclarationsShouldBeAtStartOfClass",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.ImplicitFunctionalInterface",
        "PMD.LongVariable",
        "PMD.CognitiveComplexity",
        "PMD.LawOfDemeter",
    }
)
final class ProxyProtectBridgeServer implements Closeable {

    interface VpnServiceProvider {
        VpnService getVpnService();
    }

    private static final String TAG = "WINGSV/ProtectBridge";

    private final String socketName;
    private final LocalServerSocket serverSocket;
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private final VpnServiceProvider vpnServiceProvider;
    private final Thread acceptThread;
    private volatile boolean closed;

    ProxyProtectBridgeServer(String socketName, VpnServiceProvider vpnServiceProvider) throws IOException {
        this.socketName = socketName;
        this.vpnServiceProvider = vpnServiceProvider;
        this.serverSocket = new LocalServerSocket(socketName);
        this.acceptThread = new Thread(this::acceptLoop, "wingsv-protect-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    String getSocketName() {
        return socketName;
    }

    private void acceptLoop() {
        while (!closed) {
            LocalSocket client = null;
            try {
                client = serverSocket.accept();
                if (closed || clientExecutor.isShutdown()) {
                    closeQuietly(client);
                    return;
                }
                LocalSocket acceptedClient = client;
                client = null;
                clientExecutor.execute(() -> handleClient(acceptedClient));
            } catch (RejectedExecutionException error) {
                closeQuietly(client);
                if (!closed) {
                    Log.w(TAG, "protect client rejected after shutdown", error);
                }
                return;
            } catch (IOException error) {
                closeQuietly(client);
                if (!closed) {
                    Log.w(TAG, "accept failed", error);
                }
                return;
            }
        }
    }

    private void handleClient(LocalSocket client) {
        try (
            LocalSocket socket = client;
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream()
        ) {
            byte[] requestBuffer = new byte[1];
            while (!closed) {
                int read = inputStream.read(requestBuffer);
                if (read < 0) {
                    return;
                }
                FileDescriptor[] fileDescriptors = socket.getAncillaryFileDescriptors();
                boolean protectedOk = false;
                if (fileDescriptors != null) {
                    for (FileDescriptor fileDescriptor : fileDescriptors) {
                        if (fileDescriptor != null) {
                            protectedOk = protect(fileDescriptor);
                            break;
                        }
                    }
                }
                if (!protectedOk) {
                    Log.w(TAG, "protect request rejected");
                }
                outputStream.write(protectedOk ? 1 : 0);
                outputStream.flush();
            }
        } catch (IOException ignored) {}
    }

    private boolean protect(FileDescriptor fileDescriptor) {
        VpnService vpnService = vpnServiceProvider != null ? vpnServiceProvider.getVpnService() : null;
        if (vpnService == null || fileDescriptor == null) {
            return false;
        }
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(fileDescriptor)) {
            return vpnService.protect(duplicate.getFd());
        } catch (Exception error) {
            Log.w(TAG, "protect failed", error);
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {}
        clientExecutor.shutdownNow();
    }

    private static void closeQuietly(LocalSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {}
    }
}
