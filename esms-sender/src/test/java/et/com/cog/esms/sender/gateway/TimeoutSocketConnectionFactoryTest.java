package et.com.cog.esms.sender.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of this factory is that a connect to an unreachable SMSC gives up on
 * OUR schedule rather than the operating system's, so these tests are about
 * elapsed time as much as outcome.
 */
class TimeoutSocketConnectionFactoryTest {

    /**
     * 203.0.113.0/24 is TEST-NET-3 (RFC 5737) — reserved for documentation and
     * guaranteed not to be routed, so a SYN there is black-holed exactly the way
     * the NIC firewall black-holes the SMSC. A refused connection would return
     * immediately and prove nothing; this has to be a silent drop.
     */
    private static final String BLACKHOLE_HOST = "203.0.113.1";

    @Test
    @DisplayName("an unreachable host fails within the configured budget, not the OS default")
    void connectTimesOutOnSchedule() {
        TimeoutSocketConnectionFactory factory = new TimeoutSocketConnectionFactory(1_500);

        long start = System.nanoTime();
        assertThrows(IOException.class, () -> factory.createConnection(BLACKHOLE_HOST, 5019));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Generous upper bound: the failure mode being guarded against is the
        // ~130s OS SYN-retry budget, so anything in seconds proves the point
        // without making the test flaky on a loaded machine.
        assertTrue(elapsedMs < 20_000,
                "connect should have given up near the 1500ms budget, took " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("a reachable host still yields a usable connection")
    void connectsSuccessfully() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            TimeoutSocketConnectionFactory factory = new TimeoutSocketConnectionFactory(5_000);

            var connection = factory.createConnection("127.0.0.1", server.getLocalPort());

            assertNotNull(connection);
            assertTrue(connection.isOpen());
            assertNotNull(connection.getInputStream());
            assertNotNull(connection.getOutputStream());
            connection.close();
        }
    }
}
