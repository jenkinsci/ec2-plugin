package hudson.plugins.ec2;

import java.io.PrintStream;

import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

public class EC2SpotComputerLauncher extends ComputerLauncher {

    @Override
    public void launch(SlaveComputer _computer, TaskListener listener) {
        EC2Computer computer = (EC2Computer) _computer;
        PrintStream logger = listener.getLogger();

        logger.println("The instance " + computer.getNode().getNodeName() + " is a Spot slave.");
        logger.println("Waiting for the instance to register itself with JNLP as " + computer.getNode().getNodeName());
    }
}
