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

import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;

/**
 * {@link ComputerLauncher} for EC2 that waits for the instance to really come up before proceeding to
 * the real user-specified {@link ComputerLauncher}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2ComputerLauncher extends ComputerLauncher {
    @Override
    public void launch(SlaveComputer _computer, TaskListener listener) {
        try {
            EC2Computer computer = (EC2Computer)_computer;
            PrintStream logger = listener.getLogger();

            final String baseMsg = "Node " + computer.getName() + "("+computer.getInstanceId()+")";
            String msg;

            OUTER:
            while(true) {
                switch (computer.getState()) {
                    case PENDING:
                        msg = baseMsg + " is still pending/launching, waiting 5s";
                        break;
                    case STOPPING:
                        msg = baseMsg + " is still stopping, waiting 5s";
                        break;
                    case RUNNING:
                        msg = baseMsg + " is ready";
                        LOGGER.finer(msg);
                        logger.println(msg);
                        break OUTER;
                    case STOPPED:
                        msg = baseMsg + " is stopped, sending start request";
                        LOGGER.finer(msg);
                        logger.println(msg);

                    	AmazonEC2 ec2 = computer.getCloud().connect();
                        List<String> instances = new ArrayList<String>();
                        instances.add(computer.getInstanceId());

                        StartInstancesRequest siRequest = new StartInstancesRequest(instances);
                        StartInstancesResult siResult = ec2.startInstances(siRequest);

                        msg = baseMsg + ": sent start request, result: " + siResult;
                        LOGGER.finer(baseMsg);
                        logger.println(baseMsg);
                        continue OUTER;
                    case SHUTTING_DOWN:
                    case TERMINATED:
                        // abort
                        msg = baseMsg + " is terminated or terminating, aborting launch";
                        LOGGER.info(msg);
                        logger.println(msg);
                        return;
                    default:
                        msg = baseMsg + " is in an unknown state, retrying in 5s";
                        break;
                }

                // check every 5 secs
                Thread.sleep(5000);
                // and report to system log and console
                LOGGER.finest(msg);
                logger.println(msg);
            }

            launch(computer, logger, computer.describeInstance());
        } catch (AmazonClientException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        } catch (IOException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        } catch (InterruptedException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        }

    }

    /**
     * Stage 2 of the launch. Called after the EC2 instance comes up.
     */
    protected abstract void launch(EC2Computer computer, PrintStream logger, Instance inst)
            throws AmazonClientException, IOException, InterruptedException;

    private static final Logger LOGGER = Logger.getLogger(EC2ComputerLauncher.class.getName());
}
