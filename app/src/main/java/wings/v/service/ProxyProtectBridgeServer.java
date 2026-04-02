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
            try {
                LocalSocket client = serverSocket.accept();
                clientExecutor.execute(() -> handleClient(client));
            } catch (IOException error) {
                if (!closed) {
                    Log.w(TAG, "accept failed", error);
                }
                return;
            }
        }
    }

    private void handleClient(LocalSocket client) {
        try (LocalSocket socket = client;
             InputStream inputStream = socket.getInputStream();
             OutputStream outputStream = socket.getOutputStream()) {
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
                outputStream.write(protectedOk ? 1 : 0);
                outputStream.flush();
            }
        } catch (IOException ignored) {
        }
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
        } catch (IOException ignored) {
        }
        clientExecutor.shutdownNow();
    }
}
