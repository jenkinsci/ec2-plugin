package hudson.plugins.ec2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
        r.startJenkins();
        r.runRemotely(FileBasedSSHKeyTest::verifyKeyFile);
        r.runRemotely(FileBasedSSHKeyTest::verifyCorrectKeyIsResolved);
    }

    private static void verifyKeyFile(JenkinsRule r) throws Throwable {
        assertNotNull("file content should not have been empty", EC2PrivateKey.fetchFromDisk());
        assertEquals("file content did not match", EC2PrivateKey.fetchFromDisk().getPrivateKey(), "hello, world!");
    }

    private static void verifyCorrectKeyIsResolved(JenkinsRule r) throws Throwable {
        AmazonEC2Cloud cloud = new AmazonEC2Cloud(
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
        AmazonEC2Cloud c = r.jenkins.clouds.get(AmazonEC2Cloud.class);
        assertEquals("An unexpected key was returned!", c.resolvePrivateKey().getPrivateKey(), "hello, world!");
    }
}
