package hudson.plugins.ec2;

import hudson.model.TaskListener;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.SlaveComputer;

public class EC2SpotSSHLauncher extends EC2UnixLauncher {

    @Override
    public void launch(SlaveComputer _computer, TaskListener listener) {
        // TODO ikikko : it may be better to add 'getState' method in EC2SpotSlave
        // http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-spot-instances-bid-status.html

        try {
            while (true) {
                if (((EC2Computer) _computer).getInstanceId() != null) {
                    break;
                }
                Thread.sleep(5000);
            }

            super.launch(_computer, listener);
        } catch (InterruptedException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        }
    }

}
