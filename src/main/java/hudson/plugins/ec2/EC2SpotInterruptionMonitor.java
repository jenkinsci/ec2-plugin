/*
 * The MIT License
 *
 * Copyright (c) 2026, CloudBees, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.PeriodicWork;
import hudson.remoting.VirtualChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;

/**
 * Retires spot agents that EC2 has given notice on.
 *
 * <p>An interrupted instance has about two minutes left. Work handed to it in that window is work
 * that will be lost, so an agent under notice is taken out of service straight away and terminated
 * once whatever it was already running has finished. A build that is already under way cannot be
 * saved, but nothing new needs to be thrown after it.
 *
 * <p>The notice is read from the instance rather than from EC2. It is the same signal AWS documents
 * for the purpose, it arrives without waiting for an API to catch up, and asking costs nothing that
 * counts against an API limit, which matters when the question is asked of every agent every few
 * seconds. An on-demand instance simply answers that there is no notice.
 */
@Extension
public class EC2SpotInterruptionMonitor extends PeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(EC2SpotInterruptionMonitor.class.getName());

    private static final long PERIOD_MS =
            Long.getLong("jenkins.ec2.spotInterruptionCheckPeriodMs", TimeUnit.SECONDS.toMillis(20));

    /**
     * Instances already under notice, so the question is asked once and the answer acted on until
     * the agent is gone, rather than asked again of an agent that is only waiting to go idle.
     */
    private final Set<String> underNotice = ConcurrentHashMap.newKeySet();

    @Override
    public long getRecurrencePeriod() {
        return PERIOD_MS;
    }

    @Override
    protected void doRun() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        for (Computer c : jenkins.getComputers()) {
            if (!(c instanceof EC2Computer computer)) {
                continue;
            }
            EC2AbstractSlave node = computer.getNode();
            if (node == null) {
                continue;
            }
            String instanceId = node.getInstanceId();
            if (instanceId == null || instanceId.isEmpty()) {
                continue;
            }
            if (underNotice.contains(instanceId)) {
                terminateIfIdle(computer, node, instanceId);
                continue;
            }
            if (computer.isOffline() || computer.getChannel() == null) {
                continue;
            }
            // Each agent is asked on its own thread: the question goes over that agent's channel,
            // and one agent that has stopped answering should not hold up the rest.
            Computer.threadPoolForRemoting.submit(() -> check(computer, node, instanceId));
        }
        underNotice.removeIf(id -> !stillKnown(jenkins, id));
    }

    private void check(EC2Computer computer, EC2AbstractSlave node, String instanceId) {
        VirtualChannel channel = computer.getChannel();
        if (channel == null) {
            return;
        }
        String notice;
        try {
            notice = channel.call(new ReadInterruptionNotice());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, "Could not ask " + computer.getName() + " whether it is being reclaimed", e);
            return;
        }
        if (notice == null) {
            return;
        }
        if (!underNotice.add(instanceId)) {
            return;
        }
        String message = "EC2 is reclaiming spot instance " + instanceId + ": " + notice;
        LOGGER.log(Level.INFO, message);
        if (computer.getListener() != null) {
            computer.getListener().getLogger().println(message);
        }
        /*
         * Offline before anything else, so that the executors are shut to the queue for whatever
         * time is left. Terminating an idle agent is the tidy end of the same act; one that is busy
         * is left to finish, and a later pass takes it once it goes idle.
         */
        computer.setTemporarilyOffline(true, new EC2OfflineCause(message));
        terminateIfIdle(computer, node, instanceId);
    }

    private void terminateIfIdle(EC2Computer computer, EC2AbstractSlave node, String instanceId) {
        if (!computer.isIdle()) {
            return;
        }
        LOGGER.log(Level.INFO, "Terminating idle agent {0}, whose instance is being reclaimed", computer.getName());
        underNotice.remove(instanceId);
        node.terminate();
    }

    private static boolean stillKnown(Jenkins jenkins, String instanceId) {
        for (Computer c : jenkins.getComputers()) {
            if (c instanceof EC2Computer computer) {
                EC2AbstractSlave node = computer.getNode();
                if (node != null && instanceId.equals(node.getInstanceId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Asks the instance metadata service whether this instance has been given notice.
     *
     * <p>Runs on the agent because the service only answers to the instance it describes.
     */
    private static final class ReadInterruptionNotice extends MasterToSlaveCallable<String, IOException> {

        @Serial
        private static final long serialVersionUID = 1L;

        private static final String BASE = "http://169.254.169.254";

        /** Short, because this runs against a link-local address that either answers or is absent. */
        private static final int TIMEOUT_MS = 2000;

        /**
         * @return the notice, or {@code null} where there is none. A failure to ask counts as no
         *     notice: an agent that cannot reach the service would otherwise be retired every time
         *     it was asked.
         */
        @Override
        @CheckForNull
        public String call() {
            try {
                return read("/latest/meta-data/spot/instance-action", token());
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /**
         * @return a session token where the instance requires one, or {@code null} where it does
         *     not. Sent regardless when obtained, since a service that allows unauthenticated reads
         *     accepts an authenticated one too.
         */
        @CheckForNull
        private static String token() {
            try {
                HttpURLConnection connection = open("/latest/api/token");
                connection.setRequestMethod("PUT");
                connection.setRequestProperty("X-aws-ec2-metadata-token-ttl-seconds", "21600");
                return connection.getResponseCode() == HttpURLConnection.HTTP_OK ? body(connection) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @CheckForNull
        private static String read(String path, @CheckForNull String token) throws IOException {
            HttpURLConnection connection = open(path);
            if (token != null) {
                connection.setRequestProperty("X-aws-ec2-metadata-token", token);
            }
            // Absent is the answer almost every time: the path exists only once notice is given.
            return connection.getResponseCode() == HttpURLConnection.HTTP_OK ? body(connection) : null;
        }

        private static HttpURLConnection open(String path) throws IOException {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(BASE + path).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            return connection;
        }

        private static String body(HttpURLConnection connection) throws IOException {
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        }
    }
}
