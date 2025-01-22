package hudson.plugins.ec2.ssh.proxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.apache.sshd.client.session.ClientProxyConnector;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

/**
 * {@link ClientProxyConnector} that issue an HTTP CONNECT to connect through an HTTP proxy.
 */
public class ProxyCONNECTListener implements ClientProxyConnector {

    private static final long timeout = Duration.ofSeconds(10).toMillis();

    public final String targetHost;
    public final int targetPort;
    public final String proxyUser;
    public final String proxyPass;

    public ProxyCONNECTListener(String targetHost, int targetPort, String proxyUser, String proxyPass) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.proxyUser = proxyUser;
        this.proxyPass = proxyPass;
    }

    @Override
    public void sendClientProxyMetadata(ClientSession session) throws Exception {
        proxyCONNECT(session.getIoSession());
    }

    public void proxyCONNECT(IoSession ioSession) {
        StringBuilder connectRequest = new StringBuilder();

        // Based on https://www.rfc-editor.org/rfc/rfc7231#section-4.3.6
        connectRequest
                .append("CONNECT ")
                .append(targetHost)
                .append(':')
                .append(targetPort)
                .append(" HTTP/1.0\r\n");
        // Host should be included https://datatracker.ietf.org/doc/html/rfc2616#section-14.23
        connectRequest
                .append("Host: ")
                .append(targetHost)
                .append(':')
                .append(targetPort)
                .append("\r\n");

        if ((proxyUser != null) && (proxyPass != null)) {
            String credentials = proxyUser + ":" + proxyPass;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.ISO_8859_1));
            connectRequest.append("Proxy-Authorization: Basic ");
            connectRequest.append(encoded);
            connectRequest.append("\r\n");
        }

        // End of the header
        connectRequest.append("\r\n");

        try {
            ioSession
                    .writeBuffer(new ByteArrayBuffer(connectRequest.toString().getBytes(StandardCharsets.US_ASCII)))
                    .await(timeout);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
