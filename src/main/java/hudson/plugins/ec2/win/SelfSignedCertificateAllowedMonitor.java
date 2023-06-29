/*
 * The MIT License
 *
 * Copyright (c) 2020-, M Ramon Leon, CloudBees, Inc.
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
package hudson.plugins.ec2.win;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.plugins.ec2.AMITypeData;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.AgentTemplate;
import hudson.plugins.ec2.WindowsData;
import hudson.agents.Cloud;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

@Extension
public class SelfSignedCertificateAllowedMonitor extends AdministrativeMonitor {
    private final static int MAX_TEMPLATES_FOUND = 5;
    
    List<String> insecureTemplates = new ArrayList<>(MAX_TEMPLATES_FOUND);

    @Override
    public String getDisplayName() {
        return Messages.AdminMonitor_DisplayName();
    }

    @SuppressWarnings("unused") // used by message.jelly
    public String getSelfSignedCertAllowedTemplates() {
        return String.join(", ", insecureTemplates);
    }

    /**
     * Let's activate the monitor if we find insecure (self-signed certificate allowed) windows templates, instead of
     * look for running computers.
     * @return true if the monitor is activated
     */
    @Override
    public boolean isActivated() {
        boolean maxTemplatesReached = false;
        
        ListIterator<Cloud> cloudIterator = Jenkins.get().clouds.listIterator();
        
        // Let's clear the previously calculated wrong templates to populate the lists with them again
        insecureTemplates.clear();
        
        while (cloudIterator.hasNext() && !maxTemplatesReached) {
            Cloud cloud = cloudIterator.next();
            if (cloud instanceof  EC2Cloud) {
                maxTemplatesReached = gatherInsecureTemplate((EC2Cloud) cloud);
            }
        }

        return !insecureTemplates.isEmpty();
    }

    private boolean gatherInsecureTemplate(EC2Cloud cloud) {
        List<AgentTemplate> templates = cloud.getTemplates();
        for (AgentTemplate template : templates) {
            // It's only for window templates
            if (!template.isWindowsAgent()) {
                continue;
            }

            AMITypeData amiTypeData = template.getAmiType();
            if (insecureTemplates.size() < MAX_TEMPLATES_FOUND && amiTypeData.isWindows() && ((WindowsData)amiTypeData).isAllowSelfSignedCertificate()) {
                // it is insecure
                insecureTemplates.add(template.getDisplayName());
            }

            // stop collecting the status of the computers, we already have the max allowed
            if (insecureTemplates.size() >= MAX_TEMPLATES_FOUND) {
                return true;
            }
        }
        
        return false;
    }
    
    @RequirePOST
    @SuppressWarnings("unused") // used by message.jelly
    public HttpResponse doAct(@QueryParameter String dismiss) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (dismiss != null) {
            disable(true);
        } 
        return HttpResponses.forwardToPreviousPage();
    }
}
