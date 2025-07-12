package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import java.util.Optional;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.recipes.LocalData;

@WithJenkins
class EC2CloudMigrationTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    // config.xml file contains an ec2-cloud configuration from a version previous to ec2-1.52
    @Test
    @LocalData
    void testPrivateKeyMigrationToSshCredentials() {
        assertEquals(1, r.jenkins.clouds.size());
        EC2Cloud cloud = (EC2Cloud) Jenkins.get().getCloud("ec2-myEc2Cloud");

        String credsId = cloud.getSshKeysCredentialsId();
        assertNotNull(credsId);

        Optional<BasicSSHUserPrivateKey> keyCredential =
                SystemCredentialsProvider.getInstance().getCredentials().stream()
                        .filter(BasicSSHUserPrivateKey.class::isInstance)
                        .filter(cred -> ((BasicSSHUserPrivateKey) cred)
                                .getPrivateKey()
                                .trim()
                                .equals("myPrivateKey"))
                        .map(cred -> (BasicSSHUserPrivateKey) cred)
                        .findFirst();

        assertTrue(keyCredential.isPresent());
    }
}
