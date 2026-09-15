/*
 * The MIT License
 *
 * Copyright (c) 2026, CloudBees, Inc., and a number of other of contributors
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import java.time.Instant;
import software.amazon.awssdk.services.ec2.model.Instance;

/**
 * One running EC2 instance that no Jenkins agent is using, as shown to an administrator.
 *
 * <p>Holds a snapshot rather than a live handle. The inventory it comes from is a point-in-time
 * answer, and an instance can be adopted or terminated between the lookup and the page being read.
 */
public class EC2OrphanedInstance {

    private final String instanceId;
    private final String instanceType;
    private final String state;
    private final String availabilityZone;
    private final String subnetId;
    private final Instant launchTime;
    private final SlaveTemplate template;
    private final boolean neverCarriedAnAgent;

    EC2OrphanedInstance(@NonNull Instance instance, @CheckForNull SlaveTemplate template) {
        this.neverCarriedAnAgent = SlaveTemplate.hasNeverCarriedAnAgent(instance);
        this.instanceId = instance.instanceId();
        this.instanceType = instance.instanceTypeAsString();
        this.state = instance.state() == null ? null : instance.state().nameAsString();
        this.availabilityZone =
                instance.placement() == null ? null : instance.placement().availabilityZone();
        this.subnetId = instance.subnetId();
        this.launchTime = instance.launchTime();
        this.template = template;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getInstanceType() {
        return instanceType;
    }

    public String getState() {
        return state;
    }

    public String getAvailabilityZone() {
        return availabilityZone;
    }

    public String getSubnetId() {
        return subnetId;
    }

    /**
     * @return the template that launched it, or {@code "unknown"} when no current template claims
     *     the slave type tag it carries.
     */
    public String getTemplateDescription() {
        return template == null ? "unknown" : template.getDescription();
    }

    public long getAgeMillis() {
        return launchTime == null ? 0L : Math.max(0L, System.currentTimeMillis() - launchTime.toEpochMilli());
    }

    /** @return how long it has been running, for display. */
    public String getAge() {
        return launchTime == null ? "unknown" : Util.getTimeSpanString(getAgeMillis());
    }

    /**
     * @return whether a future launch could take this instance over instead of starting a new one.
     *     An instance that cannot be adopted has no way back into service and is only waiting to be
     *     terminated.
     */
    public boolean isAdoptable() {
        if (template == null) {
            return false;
        }
        return !template.isAvoidUsingOrphanedNodes() || neverCarriedAnAgent;
    }

    /** @return why this instance is not doing any work, in a form an administrator can act on. */
    public String getReason() {
        if (template == null) {
            return "No template claims its slave type tag, so nothing will adopt it. "
                    + "Usually a template that was renamed or removed while its instances were running.";
        }
        if (template.isAvoidUsingOrphanedNodes() && !neverCarriedAnAgent) {
            return "Jenkins has already put an agent on it, whether or not that agent came online, "
                    + "and its template avoids re-using such instances, so it will never be adopted "
                    + "and is only waiting to be terminated.";
        }
        return "No agent holds it. A launch from its template can adopt it instead of starting a new instance.";
    }
}
