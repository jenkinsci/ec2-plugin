package hudson.plugins.ec2.ssh;

import com.amazonaws.AmazonClientException;
import com.trilead.ssh2.Connection;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Computer;

import java.io.IOException;
import java.io.PrintStream;

public class EC2WindowsSSHLauncher extends EC2SSHLauncher {

    @Override
    public void instanceLaunchScript(EC2Computer computer, String javaPath, Connection conn, PrintStream logger, TaskListener listener) throws IOException, AmazonClientException, InterruptedException {
        executeRemote(computer, conn, javaPath + " -fullversion", "choco install temurin17 -y;", logger, listener);
    }

}
