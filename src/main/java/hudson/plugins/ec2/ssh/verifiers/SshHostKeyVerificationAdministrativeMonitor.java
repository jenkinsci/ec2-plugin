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
package hudson.plugins.ec2.ssh.verifiers;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.HostKeyVerificationStrategyEnum;
import hudson.plugins.ec2.PluginImpl;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

@Extension
public class SshHostKeyVerificationAdministrativeMonitor extends AdministrativeMonitor {
    private final static int MAX_TEMPLATES_FOUND = 5;
    
    List<String> veryInsecureTemplates = new ArrayList<>(MAX_TEMPLATES_FOUND);
    List<String> insecureTemplates = new ArrayList<>(MAX_TEMPLATES_FOUND);

    @Override
    public String getDisplayName() {
        return Messages.AdminMonitor_DisplayName();
    }

    public String getVeryInsecureTemplates() {
        return veryInsecureTemplates.stream().collect(Collectors.joining(", "));
    }

    public String getInsecureTemplates() {
        return insecureTemplates.stream().collect(Collectors.joining(", "));
    }

    public boolean showVeryInsecureTemplates() {
        return !veryInsecureTemplates.isEmpty();
    }

    public boolean showInsecureTemplates() {
        PluginImpl plugin = PluginImpl.get();
        // On some tests it may be null
        if (plugin == null) {
            return true;
        }
        
        Instant whenDismissed = Instant.ofEpochMilli(plugin.getDismissInsecureMessages()); // if not dismissed, it is EPOCH
        return (whenDismissed.equals(Instant.EPOCH) || Instant.now().isBefore(whenDismissed)) && !insecureTemplates.isEmpty();
    }

    /**
     * Let's activate the monitor if we find insecure templates, instead of look for running computers.
     * @return true if the monitor is activated
     */
    @Override
    public boolean isActivated() {
        boolean maxTemplatesReached = false;
        
        ListIterator<Cloud> cloudIterator = Jenkins.get().clouds.listIterator();
        
        // Let's clear the previously calculated wrong templates to populate the lists with them again
        veryInsecureTemplates.clear();
        insecureTemplates.clear();
        
        while (cloudIterator.hasNext() && !maxTemplatesReached) {
            Cloud cloud = cloudIterator.next();
            if (cloud instanceof  EC2Cloud) {
                maxTemplatesReached = gatherInsecureTemplate((EC2Cloud) cloud);
            }
        }

        if (showInsecureTemplates() || showVeryInsecureTemplates()) {
            return true;
        } else {
            return false;
        }
    }

    private boolean gatherInsecureTemplate(EC2Cloud cloud) {
        List<SlaveTemplate> templates = cloud.getTemplates();
        for (SlaveTemplate template : templates) {
            // It's only for unix templates
            if (!template.isUnixSlave() || !template.isMacAgent()) {
                continue;
            }

            HostKeyVerificationStrategyEnum strategy = template.getHostKeyVerificationStrategy();
            if (veryInsecureTemplates.size() < MAX_TEMPLATES_FOUND && strategy.equals(HostKeyVerificationStrategyEnum.OFF)) {
                veryInsecureTemplates.add(template.getDisplayName());
            } else if (insecureTemplates.size() < MAX_TEMPLATES_FOUND && (!strategy.equals(HostKeyVerificationStrategyEnum.CHECK_NEW_HARD))) {
                // it is check-new-soft or accept-new
                insecureTemplates.add(template.getDisplayName());
            }

            // stop collecting the status of the computers, we already have 5 each type
            if (veryInsecureTemplates.size() >= MAX_TEMPLATES_FOUND || insecureTemplates.size() >= MAX_TEMPLATES_FOUND) {
                return true;
            }
        }
        
        return false;
    }
    
    @RequirePOST
    public HttpResponse doAct(@QueryParameter String dismiss, @QueryParameter String dismissAllMessages) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (dismiss != null) {
            PluginImpl.get().saveDismissInsecureMessages(System.currentTimeMillis());
        } 
        
        if (dismissAllMessages != null) {
            disable(true);
        }
        return HttpResponses.forwardToPreviousPage();
    }
}
