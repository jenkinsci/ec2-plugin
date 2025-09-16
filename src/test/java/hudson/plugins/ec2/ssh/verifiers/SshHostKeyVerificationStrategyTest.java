package hudson.plugins.ec2.ssh.verifiers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;

import hudson.plugins.ec2.HostKeyVerificationStrategyEnum;
import hudson.plugins.ec2.InstanceState;
import hudson.plugins.ec2.MockEC2Computer;
import hudson.plugins.ec2.ssh.EC2SSHLauncher;
import hudson.plugins.ec2.util.ConnectionExtension;
import hudson.util.LogTaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.testcontainers.containers.Container;

@WithJenkins
class SshHostKeyVerificationStrategyTest {

    @RegisterExtension
    private static final ConnectionExtension connection = new ConnectionExtension();

    private static LogRecorder loggerRule;

    private static JenkinsRule jenkins;

    @BeforeAll
    static void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

    /**
     * Check every defined strategy
     * @throws Exception
     */
    @Test
    void verifyAllStrategiesTest() throws Exception {
        List<StrategyTest> strategiesToCheck = getStrategiesToTest();

        for (StrategyTest strategyToCheck : strategiesToCheck) {
            strategyToCheck.check();
        }
    }

    // Return a list with all the strategies to check. Each element represents the strategy to check with the connection
    // to the host and the assertion of the expected result on the console and the offline status of the instance.
    private List<StrategyTest> getStrategiesToTest() throws Exception {
        List<StrategyTest> strategiesToCheck = new ArrayList<>();

        strategiesToCheck.add(forHardStrategyNotPrinted());
        strategiesToCheck.add(forHardStrategyPrinted("ecdsa"));
        strategiesToCheck.add(forHardStrategyPrinted("ed25519"));
        strategiesToCheck.add(forHardStrategyPrinted("rsa"));
        strategiesToCheck.add(forSoftStrategy());
        strategiesToCheck.add(forSoftStrategyPrinted("ecdsa"));
        strategiesToCheck.add(forSoftStrategyPrinted("ed25519"));
        strategiesToCheck.add(forSoftStrategyPrinted("rsa"));
        strategiesToCheck.add(forAcceptNewStrategy());
        strategiesToCheck.add(forOffStrategy());

        return strategiesToCheck;
    }

    // Check the hard strategy with a key not printed
    private StrategyTest forHardStrategyNotPrinted() throws Exception {
        return new StrategyTest("-hardStrategyNotPrinted", HostKeyVerificationStrategyEnum.CHECK_NEW_HARD)
                .addConnectionAttempt(builder().setState(InstanceState.PENDING).setMessagesInLog(new String[] {
                    "is not running, waiting to validate the key against the console",
                    "The instance console is blank. Cannot check the key"
                }))
                .addConnectionAttempt(builder().setMessagesInLog(new String[] {
                    "has a blank console. Maybe the console is yet not available",
                    "The instance console is blank. Cannot check the key"
                }))
                .addConnectionAttempt(builder()
                        .setConsole("A console without the key")
                        // Uptime calculation may fail, so we don't check the message about waiting 2 minutes
                        // .isOfflineByKey(true)
                        .setMessagesInLog(new String[] {
                            "didn't print the host key. Expected a line starting with",
                            "presented by the instance has not been found on the instance console"
                        }));
    }

    // Check the hard strategy with the key printed
    private StrategyTest forHardStrategyPrinted(String algorithm) throws Exception {
        return new StrategyTest("-hardStrategyPrinted-" + algorithm, HostKeyVerificationStrategyEnum.CHECK_NEW_HARD)
                .addConnectionAttempt(builder()
                        .setAlgorithm(algorithm)
                        .setConsole(
                                "A text before the key\n" + connection.getPublicKey(algorithm) + "\n a bit more text")
                        .setMessagesInLog(new String[] {"has been successfully checked against the instance console"}))
                .addConnectionAttempt(builder()
                        .setConsole("The console doesn't matter, the key is already stored. We check against this one")
                        .isOfflineByKey(true)
                        .isChangeHostKey(true)
                        .setMessagesInLog(new String[] {
                            "presented by the instance has changed since first saved ",
                            "is closed to prevent a possible man-in-the-middle attack"
                        }));
    }

    // Check the soft strategy
    private StrategyTest forSoftStrategy() throws Exception {
        return new StrategyTest("-softStrategy", HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT)
                .addConnectionAttempt(builder().setState(InstanceState.PENDING).setMessagesInLog(new String[] {
                    "is not running, waiting to validate the key against the console",
                    "The instance console is blank. Cannot check the key"
                }))
                .addConnectionAttempt(builder().setMessagesInLog(new String[] {
                    "has a blank console. Maybe the console is yet not available",
                    "The instance console is blank. Cannot check the key"
                }))
                // Allowed and persisted
                .addConnectionAttempt(
                        builder().setConsole("A console without the key").setMessagesInLog(new String[] {
                            "didn't print the host key. Expected a line starting with",
                            "Cannot check the key but the connection to ",
                            " is allowed"
                        }))
                // The key was stored on the previous step, gathered from known_hosts
                .addConnectionAttempt(builder()
                        .setConsole("A console without the key")
                        .setMessagesInLog(new String[] {"Connection allowed after the host key has been verified"}));
    }

    // Check the soft strategy
    private StrategyTest forSoftStrategyPrinted(String algorithm) throws Exception {
        return new StrategyTest("-softStrategyPrinted-" + algorithm, HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT)
                // The key was stored on the previous step, gathered from known_hosts
                .addConnectionAttempt(builder()
                        .setAlgorithm(algorithm)
                        .setConsole(
                                "A text before the key\n" + connection.getPublicKey(algorithm) + "\n a bit more text")
                        .setMessagesInLog(new String[] {"has been successfully checked against the instance console"}))
                .addConnectionAttempt(builder()
                        .setConsole("The console doesn't matter, the key is already stored. We check against this one")
                        .isOfflineByKey(true)
                        .isChangeHostKey(true)
                        .setMessagesInLog(new String[] {
                            "presented by the instance has changed since first saved ",
                            "is closed to prevent a possible man-in-the-middle attack"
                        }));
    }

    // Check the accept-new strategy
    private StrategyTest forAcceptNewStrategy() throws Exception {
        return new StrategyTest("-acceptNewStrategy", HostKeyVerificationStrategyEnum.ACCEPT_NEW)
                // We don't even check the console
                .addConnectionAttempt(builder()
                        .setState(InstanceState.PENDING)
                        .setMessagesInLog(new String[] {"has been automatically trusted for connections"}))
                .addConnectionAttempt(builder()
                        .setMessagesInLog(new String[] {"Connection allowed after the host key has been verified"}));
    }

    // Check the off strategy
    private StrategyTest forOffStrategy() throws Exception {
        return new StrategyTest("-offStrategy", HostKeyVerificationStrategyEnum.OFF)
                .addConnectionAttempt(builder()
                        .setState(InstanceState.PENDING)
                        .setMessagesInLog(new String[] {"No SSH key verification"}));
    }

    private ConnectionAttempt.Builder builder() {
        return new ConnectionAttempt.Builder();
    }

    /**
     * A class to test a strategy. It stores the computer to connect to, the different configurations the computer is
     * passing through and the verifier used to connect to that computer.
     */
    private static class StrategyTest {
        List<ConnectionAttempt> connectionAttempts = new ArrayList<>();
        MockEC2Computer computer;
        ServerKeyVerifier verifier;

        public void check() throws Exception {
            for (ConnectionAttempt connectionAttempt : connectionAttempts) {
                connectionAttempt.attempt();
            }
        }

        private StrategyTest(String computerSuffix, HostKeyVerificationStrategyEnum strategy) throws Exception {
            computer = MockEC2Computer.createComputer(computerSuffix);
            jenkins.jenkins.addNode(computer.getNode());
            computer.getSlaveTemplate().setHostKeyVerificationStrategy(strategy);
            verifier = new EC2SSHLauncher.ServerKeyVerifierImpl(
                    computer,
                    new LogTaskListener(
                            Logger.getLogger(SshHostKeyVerificationStrategyTest.class.getName()), Level.ALL));
        }

        private StrategyTest addConnectionAttempt(ConnectionAttempt.Builder computerStateBuilder) {
            // The computer and verifier are the same for every computerState of the strategy. We set them here
            connectionAttempts.add(computerStateBuilder.build(computer, verifier, connectionAttempts.size() + 1));
            return this;
        }
    }

    /**
     * A connection attempt. We establish how the computer is configured previously to the connection attempt, what
     * verifier should be used to connect to it and the expected state of the computer after the attempt.
     */
    private static class ConnectionAttempt {

        private String algorithm;
        // The console that the computer will have
        private String console = null;
        // The state the computer is on
        private InstanceState state = InstanceState.RUNNING;
        // Whether the real host key of the computer is changed before this step
        private boolean changeHostKey = false;

        // The expected messages the computer has printed out on the logs
        private String[] messagesInLog = new String[] {};
        // Whether the computer is set offline because a problem with the host key (it could be offline at the
        // beginning)
        private boolean isOfflineByKey = false;

        // The computer and verifier used during the try of connection
        private MockEC2Computer computer;
        private ServerKeyVerifier verifier;

        // The number of this attempt (for logging purposes)
        private int stage;

        /**
         * Attempt a connection. It configures the computer, try to connect to it and assert its final state.
         * @throws Exception
         */
        private void attempt() throws Exception {
            configure();
            connect();
            assertState();
        }

        private void configure() throws IOException, InterruptedException {
            // Let's start again recording all the strategy classes
            loggerRule = new LogRecorder();
            loggerRule.recordPackage(CheckNewHardStrategy.class, Level.INFO).capture(10);

            computer.setConsole(console);
            computer.setState(state);

            if (changeHostKey) {
                // Regenerate all the keys in the container
                Container.ExecResult removeResult = connection.execInContainer("sh", "-c", "rm -f /etc/ssh/ssh_host_*");
                assertThat(removeResult.getStderr(), emptyString());
                assertThat(removeResult.getStdout(), emptyString());
                Container.ExecResult regenResult = connection.execInContainer("ssh-keygen", "-A");
                assertThat(regenResult.getStderr(), emptyString());

                if (algorithm != null) {
                    // Keep the new key of the algorithm used in this test, restore the rest
                    Container.ExecResult algorithmResult = connection.execInContainer(
                            "sh",
                            "-c",
                            String.format(
                                    "mv /etc/ssh/ssh_host_%1$s_key /etc/ssh/keep_ssh_host_%1$s_key && rm -f /etc/ssh/ssh_host_* && mv /etc/ssh/keep_ssh_host_%1$s_key.pub /etc/ssh/ssh_host_%1$s_key.pub",
                                    algorithm));
                    assertThat(algorithmResult.getStderr(), emptyString());
                    assertThat(algorithmResult.getStdout(), emptyString());
                }

            } else if (algorithm != null) {
                // Restore the original keys
                Container.ExecResult algorithmResult = connection.execInContainer(
                        "sh",
                        "-c",
                        String.format(
                                "rm -f /etc/ssh/ssh_host_* && cp /etc/ssh/originals/ssh_host_%1$s_key* /etc/ssh/",
                                algorithm));
                assertThat(algorithmResult.getStderr(), emptyString());
                assertThat(algorithmResult.getStdout(), emptyString());
            }
        }

        private void connect() throws Exception {
            try {
                // Try to connect to it
                ClientSession con = connection.connect(verifier);
                con.close();
            } catch (IOException ignored) {
                // When the connection is not verified, the connect method throws an IOException
            }
        }

        private void assertState() {
            if (isOfflineByKey) {
                assertThat(
                        String.format(
                                "Stage %d. isOffline failed on %s using %s strategy",
                                stage, computer.getName(), verifier.getClass().getSimpleName()),
                        computer.isOffline(),
                        is(true));
                assertThat(
                        String.format(
                                "Stage %d. Offline reason failed on %s using %s strategy",
                                stage, computer.getName(), verifier.getClass().getSimpleName()),
                        computer.getOfflineCauseReason(),
                        is(Messages.OfflineCause_SSHKeyCheckFailed()));
            }

            for (String messageInLog : messagesInLog) {
                assertThat(
                        String.format(
                                "Stage %d. Log message not found on %s using %s strategy",
                                stage, computer.getName(), verifier.getClass().getSimpleName()),
                        loggerRule,
                        LogRecorder.recorded(StringContains.containsString(messageInLog)));
            }
        }

        /**
         * A builder to build the attempt easily
         */
        static class Builder {
            ConnectionAttempt connectionAttempt;

            Builder setAlgorithm(String algorithm) {
                connectionAttempt.algorithm = algorithm;
                return this;
            }

            Builder setConsole(String console) {
                connectionAttempt.console = console;
                return this;
            }

            Builder setState(InstanceState state) {
                connectionAttempt.state = state;
                return this;
            }

            Builder setMessagesInLog(String[] messagesInLog) {
                connectionAttempt.messagesInLog = messagesInLog;
                return this;
            }

            Builder isOfflineByKey(boolean isOfflineByKey) {
                connectionAttempt.isOfflineByKey = isOfflineByKey;
                return this;
            }

            Builder isChangeHostKey(boolean changeHostKey) {
                connectionAttempt.changeHostKey = changeHostKey;
                return this;
            }

            Builder() {
                connectionAttempt = new ConnectionAttempt();
            }

            private ConnectionAttempt build(MockEC2Computer computer, ServerKeyVerifier verifier, int stage) {
                connectionAttempt.stage = stage;
                connectionAttempt.computer = computer;
                connectionAttempt.verifier = verifier;
                return connectionAttempt;
            }
        }
    }
}
