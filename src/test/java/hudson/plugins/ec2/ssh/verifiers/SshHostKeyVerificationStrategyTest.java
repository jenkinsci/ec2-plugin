package hudson.plugins.ec2.ssh.verifiers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;

import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.InstanceState;
import hudson.plugins.ec2.MockEC2Computer;
import hudson.plugins.ec2.util.ConnectionRule;
import java.io.IOException;
import java.net.SocketAddress;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.hamcrest.core.StringContains;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.testcontainers.containers.Container;

public class SshHostKeyVerificationStrategyTest {
    @ClassRule
    public static ConnectionRule conRule = new ConnectionRule();

    @ClassRule
    public static LoggerRule loggerRule;

    @ClassRule
    public static JenkinsRule jenkins = new JenkinsRule();

    /**
     * Check every defined strategy
     * @throws Exception
     */
    @Test
    public void verifyAllStrategiesTest() throws Exception {
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
        strategiesToCheck.add(forHardStrategyPrinted());
        strategiesToCheck.add(forHardStrategyPrintedAndChanged());
        strategiesToCheck.add(forSoftStrategy());
        strategiesToCheck.add(forAceptNewStrategy());
        strategiesToCheck.add(forOffStrategy());

        return strategiesToCheck;
    }

    // Check the hard strategy with a key not printed
    private StrategyTest forHardStrategyNotPrinted() throws Exception {
        return new StrategyTest("-hardStrategyNotPrinted", new CheckNewHardStrategy())
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
                        .isOfflineByKey(true)
                        .setMessagesInLog(new String[] {
                            "didn't print the host key. Expected a line starting with",
                            "presented by the instance has not been found on the instance console"
                        }));
    }

    // Check the hard strategy with the key printed
    private StrategyTest forHardStrategyPrinted() throws Exception {
        return new StrategyTest("-hardStrategyPrinted", new CheckNewHardStrategy())
                .addConnectionAttempt(builder().setState(InstanceState.PENDING).setMessagesInLog(new String[] {
                    "is not running, waiting to validate the key against the console",
                    "The instance console is blank. Cannot check the key"
                }))
                .addConnectionAttempt(builder().setMessagesInLog(new String[] {
                    "has a blank console. Maybe the console is yet not available",
                    "The instance console is blank. Cannot check the key"
                }))
                .addConnectionAttempt(builder()
                        .setConsole("A text before the key\n" + conRule.ED255219_PUB_KEY + "\n a bit more text")
                        .setMessagesInLog(new String[] {"has been successfully checked against the instance console"}));
    }

    // Check the hard strategy with the key printed and the host key is changed afterward
    private StrategyTest forHardStrategyPrintedAndChanged() throws Exception {
        return new StrategyTest("-hardStrategyPrintedAndChanged", new CheckNewHardStrategy())
                .addConnectionAttempt(builder().setState(InstanceState.PENDING).setMessagesInLog(new String[] {
                    "is not running, waiting to validate the key against the console",
                    "The instance console is blank. Cannot check the key"
                }))
                .addConnectionAttempt(builder().setMessagesInLog(new String[] {
                    "has a blank console. Maybe the console is yet not available",
                    "The instance console is blank. Cannot check the key"
                }))
                .addConnectionAttempt(builder()
                        .setConsole("A text before the key\n" + conRule.ED255219_PUB_KEY + "\n a bit more text")
                        .setMessagesInLog(new String[] {"has been successfully checked against the instance console"}))
                .addConnectionAttempt(builder()
                        .setConsole("The console doesn't matter, the key is already stored. We check against this one")
                        .isOfflineByKey(true)
                        .isChangeHostKey(true)
                        .setMessagesInLog(new String[] {"presented by the instance has changed since first saved "}));
    }

    // Check the soft strategy
    private StrategyTest forSoftStrategy() throws Exception {
        return new StrategyTest("-softStrategy", new CheckNewSoftStrategy())
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

    // Check the accept-new strategy
    private StrategyTest forAceptNewStrategy() throws Exception {
        return new StrategyTest("-acceptNewStrategy", new AcceptNewStrategy())
                // We don't even check the console
                .addConnectionAttempt(builder()
                        .setState(InstanceState.PENDING)
                        .setMessagesInLog(new String[] {"has been automatically trusted for connections"}))
                .addConnectionAttempt(builder()
                        .setMessagesInLog(new String[] {"Connection allowed after the host key has been verified"}));
    }

    // Check the off strategy
    private StrategyTest forOffStrategy() throws Exception {
        return new StrategyTest("-offStrategy", new NonVerifyingKeyVerificationStrategy())
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
        ServerHostKeyVerifierImpl verifier;

        public void check() throws Exception {
            for (ConnectionAttempt connectionAttempt : connectionAttempts) {
                connectionAttempt.attempt();
            }
        }

        private StrategyTest(String computerSuffix, SshHostKeyVerificationStrategy strategy) throws Exception {
            computer = MockEC2Computer.createComputer(computerSuffix);
            verifier = new ServerHostKeyVerifierImpl(computer, strategy);
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
            loggerRule = new LoggerRule();
            loggerRule.recordPackage(CheckNewHardStrategy.class, Level.INFO).capture(10);

            computer.setConsole(console);
            computer.setState(state);

            if (changeHostKey) {
                // Regenerate all the keys in the container
                Container.ExecResult removeResult = conRule.execInContainer("sh", "-c", "rm -f /etc/ssh/ssh_host_*");
                assertThat(removeResult.getStderr(), emptyString());
                assertThat(removeResult.getStdout(), emptyString());
                Container.ExecResult regenResult = conRule.execInContainer("ssh-keygen", "-A");
                assertThat(regenResult.getStderr(), emptyString());
            }
        }

        private void connect() throws Exception {
            try {
                // Try to connect to it
                ClientSession con = conRule.connect(verifier);
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
                        LoggerRule.recorded(StringContains.containsString(messageInLog)));
            }
        }

        /**
         * A builder to build the attempt easily
         */
        static class Builder {
            ConnectionAttempt connectionAttempt;

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

    // A verifier using the set strategy
    private static class ServerHostKeyVerifierImpl implements ServerKeyVerifier {
        private final EC2Computer computer;
        private final SshHostKeyVerificationStrategy strategy;

        public ServerHostKeyVerifierImpl(final EC2Computer computer, final SshHostKeyVerificationStrategy strategy) {
            this.computer = computer;
            this.strategy = strategy;
        }

        @Override
        public boolean verifyServerKey(ClientSession clientSession, SocketAddress remoteAddress, PublicKey serverKey) {
            // TODO: change by the verifier defined on the instance template or the default one
            try {
                return strategy.verify(computer, new HostKey(serverKey.getAlgorithm(), serverKey.getEncoded()), null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
