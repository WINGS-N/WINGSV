package wings.v.ipc;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.SocketFactory;

/**
 * SocketFactory that connects grpc-okhttp to a filesystem unix domain socket via
 * Android's LocalSocket. The vk-turn-proxy client serves its AppControl gRPC on
 * such a socket in the app's private directory; the grpc target host/port is
 * ignored and the fixed socket path is used instead.
 */
public final class LocalSocketChannelFactory extends SocketFactory {

    private final String socketPath;

    public LocalSocketChannelFactory(String socketPath) {
        this.socketPath = socketPath;
    }

    // The no-arg createSocket() returns an UNCONNECTED socket (grpc-okhttp connects
    // it via connect()); the host/port overloads follow the SocketFactory contract
    // and return a CONNECTED socket, which is the path grpc-okhttp actually uses
    // (socketFactory.createSocket(InetAddress, port)). Returning an unconnected
    // socket there left the transport with dead streams -> UNAVAILABLE.
    @Override
    public Socket createSocket() {
        return new LocalUnixSocket(socketPath);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        LocalUnixSocket socket = new LocalUnixSocket(socketPath);
        socket.connectLocal();
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        LocalUnixSocket socket = new LocalUnixSocket(socketPath);
        socket.connectLocal();
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
        throws IOException {
        return createSocket(address, port);
    }

    private static final class LocalUnixSocket extends Socket {

        private final String path;
        private final LocalSocket local = new LocalSocket();
        private boolean connected;

        LocalUnixSocket(String path) {
            this.path = path;
        }

        // Connects the underlying LocalSocket to the filesystem unix socket exactly
        // once, whichever way grpc-okhttp drives the socket (connect-on-create via a
        // host/port createSocket, or createSocket() + a later connect()).
        synchronized void connectLocal() throws IOException {
            if (connected) {
                return;
            }
            local.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
            connected = true;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            connectLocal();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return local.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return local.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            local.close();
        }

        @Override
        public boolean isConnected() {
            return local.isConnected();
        }

        @Override
        public InetAddress getInetAddress() {
            return InetAddress.getLoopbackAddress();
        }

        @Override
        public void setTcpNoDelay(boolean on) {
            // Not applicable to a unix socket.
        }

        @Override
        public void setSoTimeout(int timeout) {
            // Not applicable to a unix socket.
        }

        @Override
        public void setKeepAlive(boolean on) {
            // Not applicable to a unix socket.
        }

        @Override
        public void shutdownOutput() throws IOException {
            local.shutdownOutput();
        }

        @Override
        public void shutdownInput() throws IOException {
            local.shutdownInput();
        }
    }
}
