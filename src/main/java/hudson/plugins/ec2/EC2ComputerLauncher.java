package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.io.PrintStream;

/**
 * {@link ComputerLauncher} for EC2 that waits for the instance to really come up before proceeding to
 * the real user-specified {@link ComputerLauncher}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2ComputerLauncher extends ComputerLauncher {
    public void launch(SlaveComputer _computer, StreamTaskListener listener) {
        try {
            EC2Computer computer = (EC2Computer)_computer;
            PrintStream logger = listener.getLogger();

            Instance inst = computer.describeInstance();

            // wait until EC2 instance comes up and post console output
            boolean reportedWaiting = false;
            OUTER:
            while(true) {
                switch (computer.getState()) {
                    case PENDING:
                    case RUNNING:
                        String console = computer.getConsoleOutput();
                        if(console==null || console.length()==0) {
                            if(!reportedWaiting) {
                                reportedWaiting = true;
                                logger.println("Waiting for the EC2 instance to boot up");
                            }
                            Thread.sleep(5000); // check every 5 secs
                            continue OUTER;
                        }
                        break OUTER;
                    case SHUTTING_DOWN:
                    case TERMINATED:
                        // abort
                        logger.println("The instance "+computer.getInstanceId()+" appears to be shut down. Aborting launch.");
                        return;
                }
            }

            launch(computer, logger, inst);
        } catch (EC2Exception e) {
            e.printStackTrace(listener.error(e.getMessage()));
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    /**
     * Stage 2 of the launch. Called after the EC2 instance comes up.
     */
    protected abstract void launch(EC2Computer computer, PrintStream logger, Instance inst)
            throws EC2Exception, IOException, InterruptedException;
}