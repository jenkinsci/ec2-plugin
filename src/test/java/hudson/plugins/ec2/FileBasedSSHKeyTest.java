package hudson.plugins.ec2;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class FileBasedSSHKeyTest {
    @Rule
    public RealJenkinsRule r = new RealJenkinsRule().javaOptions("-D" + EC2Cloud.class.getName() + ".sshPrivateKeyFilePath=" +
            getClass().getClassLoader().getResource("hudson/plugins/ec2/test.pem").getPath());

    @Test
    public void testFileBasedSShKey() throws Throwable {
        r.startJenkins();
        r.runRemotely(FileBasedSSHKeyTest::verifyKeyFile);
    }

    private static void verifyKeyFile(JenkinsRule r) throws Throwable {
            assertNotNull("file content should not have been empty", EC2PrivateKey.fetchFromDisk());
            assertEquals("file content did not match", EC2PrivateKey.fetchFromDisk().getPrivateKey(),"hello, world!");
    }
}
