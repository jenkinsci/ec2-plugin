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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * Records on the instance itself that an agent has run on it.
 *
 * <p>Whether an instance has ever carried an agent is the only thing that distinguishes capacity
 * that was launched and never claimed from capacity that has already been used, and the Jenkins
 * side of that record is exactly what is missing by the time the question is asked: the node is
 * gone. Writing it to EC2 keeps the answer available to whoever finds the instance later, and
 * across a restart of the controller.
 *
 * @see EC2Tag#TAG_NAME_JENKINS_AGENT_CONNECTED
 */
@Extension
public class EC2AgentConnectedTagger extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(EC2AgentConnectedTagger.class.getName());

    private static final int TAG_ATTEMPTS = Integer.getInteger("jenkins.ec2.connectedTagAttempts", 5);

    private static final long TAG_RETRY_DELAY_MS =
            Long.getLong("jenkins.ec2.connectedTagRetryDelayMs", TimeUnit.SECONDS.toMillis(15));

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        if (!(c instanceof EC2Computer ec2Computer)) {
            return;
        }
        EC2AbstractSlave slave = ec2Computer.getNode();
        if (slave == null) {
            return;
        }
        String instanceId = slave.getInstanceId();
        EC2Cloud cloud = slave.getCloud();
        if (instanceId == null || instanceId.isEmpty() || cloud == null) {
            return;
        }
        // Off the connect path: an agent that is up should not be held back by a tagging call, and
        // a failure here costs a re-used instance rather than a broken agent.
        markUsed(cloud, instanceId, EC2Tag.TAG_NAME_JENKINS_AGENT_CONNECTED);
    }

    /**
     * Records against an instance that Jenkins has put an agent on it, under the given tag.
     *
     * @param tagKey {@link EC2Tag#TAG_NAME_JENKINS_AGENT_CONNECTED} where the agent came online, or
     *     {@link EC2Tag#TAG_NAME_JENKINS_AGENT_ATTEMPTED} where it never did. Either way the
     *     instance stops counting as capacity nobody has touched.
     */
    static void markUsed(@NonNull EC2Cloud cloud, @NonNull String instanceId, @NonNull String tagKey) {
        Computer.threadPoolForRemoting.submit(() -> write(cloud, instanceId, tagKey, 1));
    }

    /**
     * Writes the tag, retrying until it sticks.
     *
     * <p>An untagged instance is indistinguishable from one nothing has ever used, so a write that
     * is dropped and forgotten quietly exempts that instance from a template's refusal to re-use
     * used capacity. Retrying matters more than being prompt: the answer is only read once the
     * instance has been left behind, which is long after this runs. The call sets a fixed key on
     * one instance, so repeating it costs nothing if an earlier attempt did land after all.
     */
    private static void write(EC2Cloud cloud, String instanceId, String tagKey, int attempt) {
        try {
            cloud.connect()
                    .createTags(CreateTagsRequest.builder()
                            .resources(instanceId)
                            .tags(Tag.builder()
                                    .key(tagKey)
                                    .value(Instant.now().toString())
                                    .build())
                            .build());
        } catch (RuntimeException e) {
            if (attempt < TAG_ATTEMPTS) {
                LOGGER.log(
                        Level.FINE, "Attempt " + attempt + " to mark " + instanceId + " with " + tagKey + " failed", e);
                Timer.get()
                        .schedule(
                                () -> write(cloud, instanceId, tagKey, attempt + 1),
                                TAG_RETRY_DELAY_MS,
                                TimeUnit.MILLISECONDS);
                return;
            }
            LOGGER.log(
                    Level.WARNING,
                    "Gave up after " + attempt + " attempts to mark " + instanceId + " with " + tagKey
                            + ". If it is left behind, it will look like capacity that was never used, and a template"
                            + " set to avoid re-using used instances will adopt it anyway.",
                    e);
        }
    }
}
