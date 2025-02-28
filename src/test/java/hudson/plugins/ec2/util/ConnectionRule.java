package hudson.plugins.ec2.util;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import hudson.plugins.ec2.win.winrm.RuntimeIOException;
import java.io.IOException;
import java.time.Duration;
import java.util.logging.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.junit.AssumptionViolatedException;
import org.junit.rules.ExternalResource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/**
 * Connect to a remote SSH server
 *
 * @author Kohsuke Kawaguchi
 */
public class ConnectionRule extends ExternalResource {
    private GenericContainer sshContainer;

    public static final String USER = "jenkins";
    public static final int SSH_PORT = 22;
    public static final String privateKey =
            "-----BEGIN RSA PRIVATE KEY-----\n" + "MIIEowIBAAKCAQEA3x7Q+RNxkeqlDAbosRm7tXrFLuN1fcyZ4ERLEume/JLVSYny\n"
                    + "BM4v0KhKMkTFsyVXiMukHCS0/mYnfTvjGld76pzYdoXSzncc8zZruDnMVgzAUoSS\n"
                    + "P1H5wtL6ft6ZS1aHXhWte5TmkO4GDXilMwjrgwWscUhD6YhasfDiEVvaCNVnaIYt\n"
                    + "KMhBLY1Mb6ZMuFnqbSMWWZ9S2B49RMk/QL6XS29LeCqleTl2pd5UEFGidhiPBj6J\n"
                    + "WZ/dZO/cNnNvhF37L5SiqO5lLBTYcCtDgZyXsY7jfhUGl5yRbGKcTkNiRh/bkpKT\n"
                    + "PiAcXBAx0Hv1HDGN6g+PV/rch7dD/rD4tRORDQIDAQABAoIBAEwcYwTUUSWJeYvE\n"
                    + "v5PKR3H8007PYMDtDoCmS0XEU+us2v0fBWQGQeFXxxemxhn6XwXXEcBX9TXi+w2J\n"
                    + "ZEsUFL1Pi7fCpsqvbzy4D77kWIPyDZkYiBr5h82h0rl8jaZZegvqMSe6/3vo9j+a\n"
                    + "LCBgppYnVU+/aws67FVO6o8pWhMwteoemjhKkgPYRQMXDX4tNb1DiGLP8+tAridZ\n"
                    + "jTF8Kb2g5xbypbkO2cj7/MplXWhdkEV72xCfl2MbS2jH8Vfi+lUiBENCDaTuaJlO\n"
                    + "JCBSGIr39YMbxScAEcsdhtlb1dR4bYIeSUDNSTntSZq6cz3ue4NhhY3/8Zt66fBR\n"
                    + "CFxUqwECgYEA8RTutNAKji8wQ/tmmUqJ7Tl+R8sBFPYh/A+fnCy1GYA/V5m9hJR8\n"
                    + "QPbsaqTvIEZa+RffcWgk0+agBqhOeu7dGov09Tt6xZx7IbomMx+P3kU78vcPxvM2\n"
                    + "Sbi9DnMvDRY/RKLho2ZlHGWxK0rLcfVw+Gu2Uxt1vGpT5eQ7s/poGRECgYEA7O1Z\n"
                    + "xJPdgbnzlPFuXmb06IQEA9iuA07mqjDQiqE8b0DUA+btDVal0SUZgxx8NPyLDmhB\n"
                    + "UIGM5o9GRzHbL6xm4Y3RIUw3kEYhOyJoNr0hAXYHW2ZBemxSNW9n+IA4Bgikm/IT\n"
                    + "SSvjvg8fXOisAD0KCZYEfJjMZsBmSGakcGksGD0CgYEAoqtqKk0aYjhLDAQNha+7\n"
                    + "A3vAzraW40r1QXxVSW8NP8i+dOCC9Xuvn7I9cfQaeh+e8Ob/2SjZeLXsErHsSpz0\n"
                    + "Sh5XykU5IS/mEarmbaaFUAhNXDMCzU58uh/SSXbFL8JsLGbvc277GL8xXbHZNurT\n"
                    + "MHyViNxFhD4GoF9xPY7gQNECgYAg8tkbB100n0GKoxCwPC0u8L0GM+nvN9fIL0Wx\n"
                    + "Ib8f0aoqaMDqq/QfY8NqglmbnMtR05nRslJ/9cjWOc67kIQ2NdyxfsHzZG1WpfBM\n"
                    + "PH0MkPdw9IWCmvHL0JRq8JnZ7PXHYiDgeiQP2FaKOylAVzzAHIa/NRin6XXP98ZC\n"
                    + "g73IGQKBgGybl0Ir9XkPtZ41rFHN8m7CRH5Vvd9lzRnWCfhpkejkWecYtl7cz56G\n"
                    + "V0caNcOvjvZ790lGOxMz4yymS8OIQ6Wdjf2ds66agQn9bmwKZKhjJzGP6xoFWEOu\n"
                    + "nePTvdCtsGHcSCn3oNLPONIslk72Dlp21EkKIjZkBoz2nAxwO5zf\n"
                    + "-----END RSA PRIVATE KEY-----";
    public static final String publicKey =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDfHtD5E3GR6qUMBuixGbu1esUu43V9zJngREsS6Z78ktVJifIEzi/QqEoyRMWzJVeIy6QcJLT+Zid9O+MaV3vqnNh2hdLOdxzzNmu4OcxWDMBShJI/UfnC0vp+3plLVodeFa17lOaQ7gYNeKUzCOuDBaxxSEPpiFqx8OIRW9oI1Wdohi0oyEEtjUxvpky4WeptIxZZn1LYHj1EyT9AvpdLb0t4KqV5OXal3lQQUaJ2GI8GPolZn91k79w2c2+EXfsvlKKo7mUsFNhwK0OBnJexjuN+FQaXnJFsYpxOQ2JGH9uSkpM+IBxcEDHQe/UcMY3qD49X+tyHt0P+sPi1E5EN user@test";

    // The public ed-25510 host key of the server
    public String ED255219_PUB_KEY;

    private SshClient sshClient = SshClient.setUpDefaultClient();

    private ClientSession connection;

    public ClientSession connect(ServerKeyVerifier verifier) throws Exception {
        int port = sshContainer.getMappedPort(SSH_PORT);
        String ip = sshContainer.getHost();
        Logger log = Logger.getLogger(this.getClass().getName());

        sshClient.setServerKeyVerifier(verifier);

        ConnectFuture connectFuture = sshClient.connect(USER, ip, port);

        connection = connectFuture.verify().getSession();
        connection.addPublicKeyIdentity(KeyHelper.decodeKeyPair(privateKey, ""));
        connection.auth().await(Duration.ofSeconds(10));

        assertTrue(connection.isAuthenticated());

        return connection;
    }

    public void close() throws IOException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    public Container.ExecResult execInContainer(String... command)
            throws UnsupportedOperationException, IOException, InterruptedException {
        return sshContainer.execInContainer(command);
    }

    @Override
    public void before() {
        try {
            sshContainer = new GenericContainer("jenkins/ssh-slave")
                    .withEnv("JENKINS_SLAVE_SSH_PUBKEY", publicKey)
                    .withExposedPorts(SSH_PORT);

            sshContainer.start();

            sshClient.start();

        } catch (RuntimeException re) {
            throw new AssumptionViolatedException("The container to connect to cannot be started", re);
        }

        sshContainer.start();
        try {
            // We get the key after it's generated
            ED255219_PUB_KEY = sshContainer
                    .execInContainer("cat", "/etc/ssh/ssh_host_ed25519_key.pub")
                    .getStdout();
        } catch (UnsupportedOperationException | IOException | InterruptedException e) {
            throw new RuntimeIOException("Cannot get the public ssh host key from the docker instance", e);
        }
    }

    @Override
    public void after() {
        sshClient.start();
        sshContainer.stop();

        if (connection != null) {
            try {
                connection.close();
            } catch (IOException e) {
                fail(e.getMessage());
            }
        }

        if (sshContainer.isRunning()) {
            sshContainer.stop();
        }
    }
}
