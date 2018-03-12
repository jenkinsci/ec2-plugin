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

import com.amazonaws.services.ec2.model.Instance;
import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.*;

/**
 * Returns the instance provisioned.
 *
 * Used like:
 *
 * <pre>
 * node {
 *     def x = ec2 cloud: 'myCloud', template: 'aws-CentOS-7'
 * }
 * </pre>
 *
 * @author Alicia Doblas
 */
public class EC2Step extends Step {

    private String cloud;
    private String template;

    @DataBoundConstructor
    public EC2Step(String cloud, String template) {
        this.cloud = cloud;
        this.template = template;
    }
    public String getCloud() {
        return cloud;
    }

    public String getTemplate() {
        return template;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new EC2Step.Execution( this, context);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "ec2";
        }

        @Override
        public String getDisplayName() {
            return "Cloud template provisioning";
        }


        public ListBoxModel doFillCloudItems() {
            ListBoxModel r = new ListBoxModel();
            r.add("", "");
            Jenkins.CloudList clouds = jenkins.model.Jenkins.getActiveInstance().clouds;
            for (Cloud cList : clouds) {
                if (cList instanceof AmazonEC2Cloud) {
                    r.add(cList.getDisplayName(), cList.getDisplayName());
                }
            }
            return r;
        }

        public ListBoxModel doFillTemplateItems(@QueryParameter String cloud) {
            cloud = Util.fixEmpty(cloud);
            ListBoxModel r = new ListBoxModel();
            for (Cloud cList : jenkins.model.Jenkins.getActiveInstance().clouds) {
                if (cList.getDisplayName().equals(cloud)) {
                    List<SlaveTemplate> templates = ((AmazonEC2Cloud) cList).getTemplates();
                    for (SlaveTemplate template : templates) {
                        for (String labelList : template.labels.split(" ")) {
                            r.add(labelList + "  (AMI: " + template.getAmi() + ", REGION: " + ((AmazonEC2Cloud) cList).getRegion() + ", TYPE: " + template.type.name() + ")", labelList);
                        }
                    }
                }
            }
            return r;
        }


        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }

    }

    public static class Execution extends SynchronousNonBlockingStepExecution<Instance> {
        private final String cloud;
        private final String template;


        Execution(EC2Step step, StepContext context) {
            super(context);
            this.cloud = step.cloud;
            this.template = step.template;
        }

        @Override
        protected Instance run() throws Exception {
            Cloud cl = getByDisplayName(jenkins.model.Jenkins.getActiveInstance().clouds, this.cloud);
            if (cl instanceof AmazonEC2Cloud) {
                SlaveTemplate t;
                t = ((AmazonEC2Cloud) cl).getTemplate(this.template);
                if (t != null) {
                    SlaveTemplate.ProvisionOptions universe = SlaveTemplate.ProvisionOptions.ALLOW_CREATE;
                    EnumSet<SlaveTemplate.ProvisionOptions> opt = EnumSet.noneOf(SlaveTemplate.ProvisionOptions.class);
                    opt.add(universe);

                    EC2AbstractSlave node = t.provision(TaskListener.NULL, null, opt);
                    Jenkins.getInstance().addNode(node);
                    Instance myInstance = EC2AbstractSlave.getInstance(node.getInstanceId(), node.getCloud());
                    return myInstance;
                } else {
                    throw new IllegalArgumentException("Error in AWS Cloud. Please review AWS template defined in Jenkins configuration.");
                }
            } else {
                throw new IllegalArgumentException("Error in AWS Cloud. Please review EC2 settings in Jenkins configuration.");
            }
        }

        public Cloud getByDisplayName(Jenkins.CloudList clouds, String name) {
            Iterator i$ = clouds.iterator();
            Cloud c;
            c = (Cloud) i$.next();

            while (!c.getDisplayName().equals(name)) {
                if (!i$.hasNext()) {
                    return null;
                }
                c = (Cloud) i$.next();
            }
            return c;
        }
    }

}
