package hudson.plugins.ec2.util;

import hudson.init.Terminator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.DelegatingServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.kex.KexProposalOption;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.core.CoreModuleProperties;

public final class SSHClientManager {

    private static final Logger LOGGER = Logger.getLogger(SSHClientManager.class.getName());

    /**
     * Number of NIO selector threads for the shared SSH client.
     * Default: {@code Runtime.getRuntime().availableProcessors() + 1} (SSHD default).
     */
    private static final int nioWorkers =
            SystemProperties.getInteger(SSHClientManager.class.getName() + "." + "nioWorkers", 0);

    /**
     * Idle timeout in milliseconds. Sessions with no activity are closed after this duration.
     * Default: 0 (disabled).
     */
    private static final long idleTimeoutMs =
            SystemProperties.getLong(SSHClientManager.class.getName() + "." + "idleTimeoutMs", 0L);

    /**
     * Heartbeat interval in milliseconds. Sends keep-alive messages to detect dead connections.
     * Default: 0 (disabled).
     */
    private static final long heartbeatIntervalMs =
            SystemProperties.getLong(SSHClientManager.class.getName() + "." + "heartbeatIntervalMs", 0L);

    /**
     * Authentication timeout in milliseconds. Caps how long a failing auth attempt blocks.
     * Default: 0 (use SSHD default of 2 minutes).
     */
    private static final long authTimeoutMs =
            SystemProperties.getLong(SSHClientManager.class.getName() + "." + "authTimeoutMs", 0L);

    public static final AttributeRepository.AttributeKey<List<String>> PREFERRED_ALGORITHMS_KEY =
            new AttributeRepository.AttributeKey<>();

    private static final class ClientHolder {
        static final SshClient INSTANCE = createClient();

        private static SshClient createClient() {
            SshClient client = ClientBuilder.builder()
                    .serverKeyVerifier(new DelegatingServerKeyVerifier())
                    .build(true);

            if (nioWorkers > 0) {
                CoreModuleProperties.NIO_WORKERS.set(client, nioWorkers);
                LOGGER.log(Level.FINE, () -> "SSH NIO workers set to " + nioWorkers);
            }
            if (idleTimeoutMs > 0) {
                CoreModuleProperties.IDLE_TIMEOUT.set(client, Duration.ofMillis(idleTimeoutMs));
                LOGGER.log(Level.FINE, () -> "SSH idle timeout set to " + idleTimeoutMs + "ms");
            }
            if (heartbeatIntervalMs > 0) {
                CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofMillis(heartbeatIntervalMs));
                LOGGER.log(Level.FINE, () -> "SSH heartbeat interval set to " + heartbeatIntervalMs + "ms");
            }
            if (authTimeoutMs > 0) {
                CoreModuleProperties.AUTH_TIMEOUT.set(client, Duration.ofMillis(authTimeoutMs));
                LOGGER.log(Level.FINE, () -> "SSH auth timeout set to " + authTimeoutMs + "ms");
            }

            client.addSessionListener(new SessionListener() {
                @Override
                public void sessionNegotiationOptionsCreated(Session session, Map<KexProposalOption, String> proposal) {
                    List<String> preferredAlgorithms = getPreferredAlgorithms(session);

                    if (!preferredAlgorithms.isEmpty()) {
                        String currentAlgorithms = proposal.get(KexProposalOption.SERVERKEYS);
                        if (currentAlgorithms != null && !currentAlgorithms.isEmpty()) {
                            String reordered = reorderAlgorithms(currentAlgorithms, preferredAlgorithms);
                            proposal.put(KexProposalOption.SERVERKEYS, reordered);
                            LOGGER.log(Level.FINE, () -> "Reordered host key algorithms for session: " + reordered);
                        }
                    }
                }

                private List<String> getPreferredAlgorithms(Session session) {
                    List<String> result = session.getAttribute(PREFERRED_ALGORITHMS_KEY);
                    if (result != null) {
                        return result;
                    }

                    if (session instanceof ClientSession clientSession) {
                        AttributeRepository context = clientSession.getConnectionContext();
                        if (context != null) {
                            result = context.getAttribute(PREFERRED_ALGORITHMS_KEY);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                    return Collections.emptyList();
                }
            });

            client.start();
            LOGGER.fine("Shared SshClient started");
            return client;
        }
    }

    public static SshClient sshClient() {
        return ClientHolder.INSTANCE;
    }

    static String reorderAlgorithms(String currentAlgorithms, List<String> preferredAlgorithms) {
        List<String> algorithms = new ArrayList<>(Arrays.asList(currentAlgorithms.split(",")));
        List<String> preferred = new ArrayList<>();
        List<String> others = new ArrayList<>();

        for (String algo : algorithms) {
            String trimmed = algo.trim();
            boolean isPreferred = preferredAlgorithms.stream().anyMatch(pref -> trimmed.toLowerCase()
                    .contains(pref.toLowerCase().replace("ssh-", "")));
            if (isPreferred) {
                preferred.add(trimmed);
            } else {
                others.add(trimmed);
            }
        }
        preferred.addAll(others);
        return String.join(",", preferred);
    }

    @Terminator
    public static void stop() throws Exception {
        ClientHolder.INSTANCE.stop();
    }

    private SSHClientManager() {}
}
