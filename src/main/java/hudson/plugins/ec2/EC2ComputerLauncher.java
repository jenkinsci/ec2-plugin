package hudson.plugins.ec2;

import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.io.PrintStream;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;

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

            OUTER:
            while(true) {
                switch (computer.getState()) {
                    case PENDING:
                        Thread.sleep(5000); // check every 5 secs
                        continue OUTER;
                    case RUNNING:
                        break OUTER;
                    case SHUTTING_DOWN:
                    case TERMINATED:
                        // abort
                        logger.println("The instance "+computer.getInstanceId()+" appears to be shut down. Aborting launch.");
                        return;
                }
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
}