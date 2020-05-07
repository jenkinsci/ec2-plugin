/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Plugin;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Added to handle backwards compatibility of xstream class name mapping.
 */
@Extension
public class PluginImpl extends Plugin implements Describable<PluginImpl> {
    private static final Logger LOGGER = Logger.getLogger(PluginImpl.class.getName());
    
    // Whether the SshHostKeyVerificationAdministrativeMonitor should show messages when we have templates using
    // accept-new or check-new-soft strategies
    private long dismissInsecureMessages; 

    public void saveDismissInsecureMessages(long dismissInsecureMessages) {
        this.dismissInsecureMessages = dismissInsecureMessages;
        try {
            save();
        } catch(IOException io) {
            LOGGER.warning("There was a problem saving that you want to dismiss all messages related to insecure EC2 templates");
        }
    }

    public long getDismissInsecureMessages() {
        return dismissInsecureMessages;
    }
    
    @Override
    public void start() throws Exception {
        // backward compatibility with the legacy class name
        Jenkins.XSTREAM.alias("hudson.plugins.ec2.EC2Cloud", AmazonEC2Cloud.class);
        Jenkins.XSTREAM.alias("hudson.plugins.ec2.EC2Slave", EC2OndemandSlave.class);
        // backward compatibility with the legacy instance type
        Jenkins.XSTREAM.registerConverter(new InstanceTypeConverter());

        load();
    }

    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) Jenkins.get().getDescriptorOrDie(getClass());
    }

    public static PluginImpl get() {
        return Jenkins.get().getPlugin(PluginImpl.class);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<PluginImpl> {
        @Override
        public String getDisplayName() {
            return "EC2 PluginImpl";
        }
    }

    @Override
    public void postInitialize() {
        MinimumInstanceChecker.checkForMinimumInstances();
    }
}
