package hudson.plugins.ec2.ssh;

import hudson.model.TaskListener;
import hudson.plugins.ec2.HostKeyVerificationStrategyEnum;
import hudson.plugins.ec2.MockEC2Computer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class EC2SSHLauncherTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void testServerKeyVerifier() throws Exception {
        for (String publicKeyFile : List.of(
                "ssh_host_dss_1024.pub",
                "ssh_host_rsa_1024.pub",
                "ssh_host_rsa_2048.pub",
                "ssh_host_rsa_3072.pub",
                "ssh_host_rsa_4096.pub",
                // Special case of Open SSH Certificate
                // ssh-keygen -t rsa -b 2048 -f ./ssh_host_rsa -N ""
                // ssh-keygen -s ./ssh_host_rsa -I ec2SshLauncherTest -h -n localhost -V +0s:+999999999s
                // ./ssh_host_rsa.pub
                "ssh_host_rsa-cert.pub")) {

            String sshHostKeyPath = Files.readString(Path.of(getClass()
                            .getClassLoader()
                            .getResource("hudson/plugins/ec2/ssh/" + publicKeyFile)
                            .getPath()))
                    .trim();

            MockEC2Computer computer = MockEC2Computer.createComputer(publicKeyFile);
            r.jenkins.addNode(computer.getNode());

            computer.getSlaveTemplate().setHostKeyVerificationStrategy(HostKeyVerificationStrategyEnum.OFF);
            Assert.assertTrue(new EC2SSHLauncher.ServerKeyVerifierImpl(computer, TaskListener.NULL)
                    .verifyServerKey(
                            null,
                            null,
                            PublicKeyEntry.parsePublicKeyEntry(sshHostKeyPath).resolvePublicKey(null, null, null)));
            computer.getSlaveTemplate().setHostKeyVerificationStrategy(HostKeyVerificationStrategyEnum.ACCEPT_NEW);
            Assert.assertTrue(new EC2SSHLauncher.ServerKeyVerifierImpl(computer, TaskListener.NULL)
                    .verifyServerKey(
                            null,
                            null,
                            PublicKeyEntry.parsePublicKeyEntry(sshHostKeyPath).resolvePublicKey(null, null, null)));

            r.jenkins.removeNode(computer.getNode());
        }
    }
}
