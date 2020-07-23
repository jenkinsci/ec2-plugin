/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
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

import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.model.queue.SubTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.amazonaws.AmazonClientException;
import jenkins.model.CauseOfInterruption;

import javax.annotation.Nonnull;

/**
 * {@link ComputerLauncher} for EC2 that wraps the real user-specified {@link ComputerLauncher}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2ComputerLauncher extends ComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(EC2ComputerLauncher.class.getName());

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener listener) {
        try {
            EC2Computer computer = (EC2Computer) slaveComputer;
            launchScript(computer, listener);
        } catch (AmazonClientException | IOException e) {
            e.printStackTrace(listener.error(e.getMessage()));
            if (slaveComputer.getNode() instanceof  EC2AbstractSlave) {
                LOGGER.log(Level.FINE, String.format("Terminating the ec2 agent %s due a problem launching or connecting to it", slaveComputer.getName()), e);
                EC2AbstractSlave ec2AbstractSlave = (EC2AbstractSlave) slaveComputer.getNode();
                if (ec2AbstractSlave != null) {
                    ec2AbstractSlave.terminate();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace(listener.error(e.getMessage()));
            if (slaveComputer.getNode() instanceof  EC2AbstractSlave) {
                LOGGER.log(Level.FINE, String.format("Terminating the ec2 agent %s due a problem launching or connecting to it", slaveComputer.getName()), e);
                EC2AbstractSlave ec2AbstractSlave = (EC2AbstractSlave) slaveComputer.getNode();
                if (ec2AbstractSlave != null) {
                    ec2AbstractSlave.terminate();
                }
            }
        }

    }

    /**
     * Stage 2 of the launch. Called after the EC2 instance comes up.
     */
    protected abstract void launchScript(EC2Computer computer, TaskListener listener)
            throws AmazonClientException, IOException, InterruptedException;


    /**
     * This method is called after a node disconnects. See {@link ComputerLauncher#afterDisconnect(SlaveComputer, TaskListener)}
     * This method is overriden to perform a check to see if the node that is disconnected is a spot instance and
     * whether the disconnection is a spot interruption event. If it is a spot interruption event, the tasks that the
     * node was processing will be resubmitted if a user selects the option to do so.
     * @param computer
     * @param listener
     */
    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        if (computer == null) return;  // potential edge case where computer is null

        Slave node = computer.getNode();
        if (node instanceof EC2SpotSlave) {

            // checking if its an unexpected disconnection
            final boolean isUnexpectedDisconnection = computer.isOffline() && computer.getOfflineCause()
                    instanceof OfflineCause.ChannelTermination;
            boolean shouldRestart = ((EC2SpotSlave) node).getRestartSpotInterruption();
            if (isUnexpectedDisconnection && shouldRestart) {
                SpotInstanceRequest spotRequest = ((EC2SpotSlave) node).getSpotRequest();
                if (spotRequest == null) {
                    LOGGER.log(Level.WARNING, String.format("Could not get spot request for spot instance node %s",
                            node.getNodeName()));
                    return;
                }
                String code = spotRequest.getStatus().getCode();
                // list of status codes - https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/spot-bid-status.html#spot-instance-bid-status-understand
                if (code.equals("instance-stopped-by-price") || code.equals("instance-stopped-no-capacity") ||
                        code.equals("instance-terminated-by-price") || code.equals("instance-terminated-no-capacity")) {
                    LOGGER.log(Level.INFO, String.format("Node %s was terminated due to spot interruption. Retriggering " +
                            "job", node.getNodeName()));
                    List<Executor> executors = computer.getExecutors();
                    for (Executor executor : executors) {
                        Queue.Executable currentExecutable = executor.getCurrentExecutable();
                        if (currentExecutable !=null) {  // interrupting all executables
                            executor.interrupt(Result.ABORTED, new EC2SpotInterruptedCause(node.getNodeName()));
                            SubTask subTask = currentExecutable.getParent();
                            Queue.Task task = subTask.getOwnerTask();
                            // Get actions (if any)
                            List<Action> actions = new ArrayList<>();
                            if (currentExecutable instanceof Actionable) {
                                actions = ((Actionable) currentExecutable).getActions(Action.class);
                            }
                            LOGGER.log(Level.INFO, String.format("Spot instance for node %s was terminated. " +
                                    "Resubmitting task %s with actions %s", node.getNodeName(), task, actions));
                            Queue.getInstance().schedule2(task, 10, actions);
                        }
                    }
                }
            }
        }
    }

    /**
     * This {@link CauseOfInterruption} is used when a Node is disconnected due to a Spot Interruption event
     */
    static class EC2SpotInterruptedCause extends CauseOfInterruption {

        @Nonnull
        private final String nodeName;

        public EC2SpotInterruptedCause(@Nonnull String nodeName) {
            this.nodeName = nodeName;
        }

        @Override
        public String getShortDescription() {
            return "EC2 spot instance for node " + nodeName + " was terminated";
        }

        @Override
        public int hashCode() {
            return nodeName.hashCode();
        }

        @Override
        public String toString() {
            return getShortDescription();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof EC2SpotInterruptedCause) {
                return nodeName.equals(((EC2SpotInterruptedCause) obj).nodeName);
            } else {
                return false;
            }
        }
    }
}
