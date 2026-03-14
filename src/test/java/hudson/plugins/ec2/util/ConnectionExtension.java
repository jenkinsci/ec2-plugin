package hudson.plugins.ec2.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/**
 * Connect to a remote SSH server
 *
 * @author Kohsuke Kawaguchi
 */
public class ConnectionExtension implements BeforeAllCallback, AfterAllCallback {

    private GenericContainer<?> sshContainer;

    private static final String USER = "jenkins";
    private static final int SSH_PORT = 22;
    private static final String privateKey =
            """
                    -----BEGIN RSA PRIVATE KEY-----
                    MIIEowIBAAKCAQEA3x7Q+RNxkeqlDAbosRm7tXrFLuN1fcyZ4ERLEume/JLVSYny
                    BM4v0KhKMkTFsyVXiMukHCS0/mYnfTvjGld76pzYdoXSzncc8zZruDnMVgzAUoSS
                    P1H5wtL6ft6ZS1aHXhWte5TmkO4GDXilMwjrgwWscUhD6YhasfDiEVvaCNVnaIYt
                    KMhBLY1Mb6ZMuFnqbSMWWZ9S2B49RMk/QL6XS29LeCqleTl2pd5UEFGidhiPBj6J
                    WZ/dZO/cNnNvhF37L5SiqO5lLBTYcCtDgZyXsY7jfhUGl5yRbGKcTkNiRh/bkpKT
                    PiAcXBAx0Hv1HDGN6g+PV/rch7dD/rD4tRORDQIDAQABAoIBAEwcYwTUUSWJeYvE
                    v5PKR3H8007PYMDtDoCmS0XEU+us2v0fBWQGQeFXxxemxhn6XwXXEcBX9TXi+w2J
                    ZEsUFL1Pi7fCpsqvbzy4D77kWIPyDZkYiBr5h82h0rl8jaZZegvqMSe6/3vo9j+a
                    LCBgppYnVU+/aws67FVO6o8pWhMwteoemjhKkgPYRQMXDX4tNb1DiGLP8+tAridZ
                    jTF8Kb2g5xbypbkO2cj7/MplXWhdkEV72xCfl2MbS2jH8Vfi+lUiBENCDaTuaJlO
                    JCBSGIr39YMbxScAEcsdhtlb1dR4bYIeSUDNSTntSZq6cz3ue4NhhY3/8Zt66fBR
                    CFxUqwECgYEA8RTutNAKji8wQ/tmmUqJ7Tl+R8sBFPYh/A+fnCy1GYA/V5m9hJR8
                    QPbsaqTvIEZa+RffcWgk0+agBqhOeu7dGov09Tt6xZx7IbomMx+P3kU78vcPxvM2
                    Sbi9DnMvDRY/RKLho2ZlHGWxK0rLcfVw+Gu2Uxt1vGpT5eQ7s/poGRECgYEA7O1Z
                    xJPdgbnzlPFuXmb06IQEA9iuA07mqjDQiqE8b0DUA+btDVal0SUZgxx8NPyLDmhB
                    UIGM5o9GRzHbL6xm4Y3RIUw3kEYhOyJoNr0hAXYHW2ZBemxSNW9n+IA4Bgikm/IT
                    SSvjvg8fXOisAD0KCZYEfJjMZsBmSGakcGksGD0CgYEAoqtqKk0aYjhLDAQNha+7
                    A3vAzraW40r1QXxVSW8NP8i+dOCC9Xuvn7I9cfQaeh+e8Ob/2SjZeLXsErHsSpz0
                    Sh5XykU5IS/mEarmbaaFUAhNXDMCzU58uh/SSXbFL8JsLGbvc277GL8xXbHZNurT
                    MHyViNxFhD4GoF9xPY7gQNECgYAg8tkbB100n0GKoxCwPC0u8L0GM+nvN9fIL0Wx
                    Ib8f0aoqaMDqq/QfY8NqglmbnMtR05nRslJ/9cjWOc67kIQ2NdyxfsHzZG1WpfBM
                    PH0MkPdw9IWCmvHL0JRq8JnZ7PXHYiDgeiQP2FaKOylAVzzAHIa/NRin6XXP98ZC
                    g73IGQKBgGybl0Ir9XkPtZ41rFHN8m7CRH5Vvd9lzRnWCfhpkejkWecYtl7cz56G
                    V0caNcOvjvZ790lGOxMz4yymS8OIQ6Wdjf2ds66agQn9bmwKZKhjJzGP6xoFWEOu
                    nePTvdCtsGHcSCn3oNLPONIslk72Dlp21EkKIjZkBoz2nAxwO5zf
                    -----END RSA PRIVATE KEY-----""";
    private static final String publicKey =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDfHtD5E3GR6qUMBuixGbu1esUu43V9zJngREsS6Z78ktVJifIEzi/QqEoyRMWzJVeIy6QcJLT+Zid9O+MaV3vqnNh2hdLOdxzzNmu4OcxWDMBShJI/UfnC0vp+3plLVodeFa17lOaQ7gYNeKUzCOuDBaxxSEPpiFqx8OIRW9oI1Wdohi0oyEEtjUxvpky4WeptIxZZn1LYHj1EyT9AvpdLb0t4KqV5OXal3lQQUaJ2GI8GPolZn91k79w2c2+EXfsvlKKo7mUsFNhwK0OBnJexjuN+FQaXnJFsYpxOQ2JGH9uSkpM+IBxcEDHQe/UcMY3qD49X+tyHt0P+sPi1E5EN user@test";

    private final SshClient sshClient = SshClient.setUpDefaultClient();

    private ClientSession connection;

    public ClientSession connect(ServerKeyVerifier verifier) throws Exception {
        int port = sshContainer.getMappedPort(SSH_PORT);
        String ip = sshContainer.getHost();

        sshClient.setServerKeyVerifier(verifier);

        ConnectFuture connectFuture = sshClient.connect(USER, ip, port);

        connection = connectFuture.verify().getSession();
        connection.addPublicKeyIdentity(KeyHelper.decodeKeyPair(privateKey, ""));
        connection.auth().await(Duration.ofSeconds(10));

        return connection;
    }

    public Container.ExecResult execInContainer(String... command)
            throws UnsupportedOperationException, IOException, InterruptedException {
        return sshContainer.execInContainer(command);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        try {
            sshContainer = new GenericContainer("jenkins/ssh-slave")
                    .withEnv("JENKINS_SLAVE_SSH_PUBKEY", publicKey)
                    .withExposedPorts(SSH_PORT);

            sshContainer.start();

            sshClient.start();

            // Backup all SSH Host Keys to allow tests to modify them
            Container.ExecResult backup = execInContainer(
                    "sh", "-c", "mkdir -p /etc/ssh/originals/ && cp /etc/ssh/ssh_host_* /etc/ssh/originals/");
            assertThat(backup.getStderr(), emptyString());
            assertThat(backup.getStdout(), emptyString());

        } catch (RuntimeException re) {
            throw new TestAbortedException("The container to connect to cannot be started", re);
        }

        sshContainer.start();
    }

    public String getPublicKey(String algorithm) throws IOException {
        try {
            return sshContainer
                    .execInContainer("cat", "/etc/ssh/ssh_host_" + algorithm.toLowerCase(Locale.ROOT) + "_key.pub")
                    .getStdout();
        } catch (UnsupportedOperationException | IOException | InterruptedException e) {
            throw new IOException("Cannot get the public ssh host key from the docker instance", e);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        sshClient.start();
        sshContainer.stop();

        if (connection != null) {
            assertDoesNotThrow(() -> connection.close());
        }

        if (sshContainer.isRunning()) {
            sshContainer.stop();
        }
    }
}
