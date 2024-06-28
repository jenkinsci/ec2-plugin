package hudson.plugins.ec2.ssh;

import com.amazonaws.AmazonClientException;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Computer;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.PrintStream;

public class EC2WindowsSSHLauncher extends EC2SSHLauncher {

    @Override
    public void instanceLaunchScript(EC2Computer computer, String javaPath, Connection conn, PrintStream logger, TaskListener listener) throws IOException, AmazonClientException, InterruptedException {
        executeRemote(computer, conn, javaPath + " -fullversion", "choco install temurin17 -y;", logger, listener);
    }

    @Override
    protected boolean runInitScript(EC2Computer computer, TaskListener listener, String initScript, Connection conn, PrintStream logger, SCPClient scp, String tmpDir) throws IOException, InterruptedException {
        if (initScript != null && !initScript.trim().isEmpty()
                && conn.exec("test -e " + tmpDir + " init.bat", logger) != 0) {
            logInfo(computer, listener, "Executing init script");
            scp.put(initScript.getBytes("UTF-8"), "init.bat", tmpDir, "0700");
            Session sess = conn.openSession();
            sess.requestDumbPTY(); // so that the remote side bundles stdout
            // and stderr
            sess.execCommand(buildUpCommand(computer, tmpDir + "/init.bat"));

            sess.getStdin().close(); // nothing to write here
            sess.getStderr().close(); // we are not supposed to get anything
            // from stderr
            IOUtils.copy(sess.getStdout(), logger);

            int exitStatus = waitCompletion(sess);
            if (exitStatus != 0) {
                logWarning(computer, listener, "init script failed: exit code=" + exitStatus);
                return true;
            }
            sess.close();

            logInfo(computer, listener, "Creating " + tmpDir + ".jenkins-init");

            // Needs a tty to run sudo.
            sess = conn.openSession();
            sess.requestDumbPTY(); // so that the remote side bundles stdout
            // and stderr
            sess.execCommand(buildUpCommand(computer, "Set-Content -Path " + tmpDir + ".jenkins-init -Value $null"));

            sess.getStdin().close(); // nothing to write here
            sess.getStderr().close(); // we are not supposed to get anything
            // from stderr
            IOUtils.copy(sess.getStdout(), logger);

            exitStatus = waitCompletion(sess);
            if (exitStatus != 0) {
                logWarning(computer, listener, "init script failed: exit code=" + exitStatus);
                return true;
            }
            sess.close();
        }
        return false;
    }

}
