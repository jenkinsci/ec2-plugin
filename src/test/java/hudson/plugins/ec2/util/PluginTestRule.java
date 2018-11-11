package hudson.plugins.ec2.util;

import hudson.plugins.ec2.AmazonEC2Cloud;
import org.jvnet.hudson.test.JenkinsRule;

public class PluginTestRule extends JenkinsRule {

    public void addCloud (AmazonEC2Cloud cl) {
        jenkins.clouds.add(cl);
    }
}
