package hudson.plugins.ec2.ssh;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.HTTPProxyData;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Computer;
import hudson.remoting.Channel;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

class TrileadSshClient implements SshClient {
    private String host;
    private int port;
    private int connectTimeout;
    private String user;
    private char[] privateKey;
    private EC2Logger logger;

    private Connection conn;

    TrileadSshClient(EC2Logger logger, String host, int port, int connectTimeout,
                            String user, char[] pemPrivateKey) {
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.user = user;
        this.privateKey = pemPrivateKey;
        this.logger = logger;
    }

    @Override
    public void connect() throws IOException, InterruptedException {
        conn = new Connection(host, port);

        ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
        Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(host);

        if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            HTTPProxyData proxyData;
            if (proxyConfig != null && null != proxyConfig.getUserName()) {
                proxyData = new HTTPProxyData(address.getHostName(), address.getPort(), proxyConfig.getUserName(), proxyConfig.getPassword());
            } else {
                proxyData = new HTTPProxyData(address.getHostName(), address.getPort());
            }
            conn.setProxyData(proxyData);
            logger.info("[ssh] Using HTTP Proxy Configuration");
        }

        // currently OpenSolaris offers no way of verifying the host
        // certificate, so just accept it blindly,
        // hoping that no man-in-the-middle attack is going on.
        conn.connect(new ServerHostKeyVerifier() {
            public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey)
                    throws Exception {
                return true;
            }
        }, connectTimeout, connectTimeout);

        if (!conn.authenticateWithPublicKey(user, privateKey, "")) {
            logger.warn("[ssh] Authentication failed");
            return; // failed to connect as root. - FIXME: shall throw exception!
        }

        logger.info("[ssh] Connected via SSH.");
    }

    @Override
    public void put(byte[] data, String remoteFileName, String remoteTargetDirectory, String mode) throws IOException, InterruptedException {
        SCPClient scp = new SCPClient(conn);

        scp.put(data, remoteFileName, remoteTargetDirectory, mode);
    }

    @Override
    public void startCommandPipe(String command, EC2Computer computer, TaskListener listener)
            throws IOException, InterruptedException {
        if (conn == null)
            throw new IOException("[ssh] Not connected");

        logger.info("[ssh] runAsync cmd="+command);

        final Session sess = conn.openSession();
        sess.execCommand(command);

        computer.setChannel(sess.getStdout(), sess.getStdin(), listener, new Channel.Listener() {
            @Override
            public void onClosed(Channel channel, IOException cause) {
                sess.close();
                conn.close();
            }
        });
    }

    @Override
    public int run(String command, OutputStream output) throws IOException, InterruptedException {
        if (conn == null)
            throw new IOException("Not connected");

        Session sess = conn.openSession();
        sess.requestDumbPTY(); // so that the remote side bundles stdout
        // and stderr
        sess.execCommand(command);

        sess.getStdin().close(); // nothing to write here
        sess.getStderr().close(); // we are not supposed to get anything
        // from stderr
        IOUtils.copy(sess.getStdout(), output);

        int exitStatus = waitCompletion(sess);
        if (exitStatus != 0) {
            logger.warn("[ssh] init script failed: exit code=" + exitStatus);
            return exitStatus;
        }
        sess.close();

        return 0;
    }

    private int waitCompletion(Session session) throws InterruptedException {
        // I noticed that the exit status delivery often gets delayed. Wait up to 1 sec.
        for (int i = 0; i < 10; i++) {
            Integer r = session.getExitStatus();
            if (r != null)
                return r;
            Thread.sleep(100);
        }
        return -1;
    }

    @Override
    public void close() {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }
}
