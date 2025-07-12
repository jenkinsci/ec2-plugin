package hudson.plugins.ec2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeFalse;

import hudson.Functions;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;

public class FileBasedSSHKeyTest {

    @Rule
    public RealJenkinsRule r = new RealJenkinsRule()
            .javaOptions("-D" + EC2Cloud.class.getName() + ".sshPrivateKeyFilePath="
                    + getClass()
                            .getClassLoader()
                            .getResource("hudson/plugins/ec2/test.pem")
                            .getPath());

    @Test
    public void testFileBasedSShKey() throws Throwable {
        assumeFalse(Functions.isWindows());
        r.startJenkins();
        r.runRemotely(FileBasedSSHKeyTest::verifyKeyFile);
        r.runRemotely(FileBasedSSHKeyTest::verifyCorrectKeyIsResolved);
    }

    private static void verifyKeyFile(JenkinsRule r) {
        assertNotNull("file content should not have been empty", EC2PrivateKey.fetchFromDisk());
        assertEquals(
                "file content did not match",
                "hello, world!",
                EC2PrivateKey.fetchFromDisk().getPrivateKey());
    }

    private static void verifyCorrectKeyIsResolved(JenkinsRule r) {
        EC2Cloud cloud = new EC2Cloud(
                "us-east-1",
                true,
                "abc",
                "us-east-1",
                null,
                "ghi",
                "3",
                Collections.emptyList(),
                "roleArn",
                "roleSessionName");
        r.jenkins.clouds.add(cloud);
        EC2Cloud c = r.jenkins.clouds.get(EC2Cloud.class);
        assertEquals(
                "An unexpected key was returned!",
                "hello, world!",
                c.resolvePrivateKey().getPrivateKey());
    }
}
