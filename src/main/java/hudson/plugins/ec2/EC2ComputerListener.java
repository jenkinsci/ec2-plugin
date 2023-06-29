package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.agents.ComputerListener;

@Extension
public class EC2ComputerListener extends ComputerListener {

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        if (c instanceof EC2Computer) {
            ((EC2Computer) c).onConnected();
        }
    }
}
