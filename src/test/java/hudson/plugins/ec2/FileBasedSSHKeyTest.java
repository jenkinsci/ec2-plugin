package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import hudson.Functions;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

class FileBasedSSHKeyTest {

    @RegisterExtension
    private final RealJenkinsExtension r = new RealJenkinsExtension()
            .javaOptions("-D" + EC2Cloud.class.getName() + ".sshPrivateKeyFilePath="
                    + getClass()
                            .getClassLoader()
                            .getResource("hudson/plugins/ec2/test.pem")
                            .getPath());

    @Test
    void testFileBasedSShKey() throws Throwable {
        assumeFalse(Functions.isWindows());
        r.startJenkins();
        r.runRemotely(FileBasedSSHKeyTest::verifyKeyFile);
        r.runRemotely(FileBasedSSHKeyTest::verifyCorrectKeyIsResolved);
    }

    private static void verifyKeyFile(JenkinsRule r) {
        assertNotNull(EC2PrivateKey.fetchFromDisk(), "file content should not have been empty");
        assertEquals("hello, world!", EC2PrivateKey.fetchFromDisk().getPrivateKey(), "file content did not match");
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
        assertEquals("hello, world!", c.resolvePrivateKey().getPrivateKey(), "An unexpected key was returned!");
    }
}
