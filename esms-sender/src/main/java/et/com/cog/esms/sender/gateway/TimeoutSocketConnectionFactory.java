package et.com.cog.esms.sender.gateway;

import org.jsmpp.session.connection.Connection;
import org.jsmpp.session.connection.ConnectionFactory;
import org.jsmpp.session.connection.socket.SocketConnection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A jsmpp {@link ConnectionFactory} that puts a bound on the TCP connect.
 *
 * jsmpp's own {@code SocketConnectionFactory} calls {@code new Socket(host, port)},
 * which blocks for the operating system's default SYN-retry budget — about
 * 130 seconds on Linux. The {@code timeout} argument to
 * {@code SMPPSession.connectAndBind(host, port, bindParam, timeout)} does NOT
 * cover this: it only bounds the wait for the bind RESPONSE, which cannot even
 * begin until the socket is already open. So with the SMSC unreachable,
 * app.gateway.nib-smsc.bind-timeout-ms=10000 produced failures 133 seconds
 * apart, and the "retrying in 30000ms" cadence was really every ~2m45s.
 *
 * {@code Socket.connect(addr, timeout)} is the only way to bound that phase.
 */
public class TimeoutSocketConnectionFactory implements ConnectionFactory {

    private final int connectTimeoutMs;

    public TimeoutSocketConnectionFactory(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public Connection createConnection(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            return new SocketConnection(socket);
        } catch (IOException e) {
            // The socket owns a file descriptor even when connect() fails.
            // SocketConnection never took ownership, so nothing else will close
            // it — and the reconnect loop runs forever, so a leak here would
            // accumulate descriptors for as long as the SMSC stays unreachable.
            try {
                socket.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
