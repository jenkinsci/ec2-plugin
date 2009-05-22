package org.jvnet.hudson.ec2.launcher;

import com.xerox.amazonws.ec2.ReservationDescription;

/**
 * EC2 instance state.
 *
 * @author Kohsuke Kawaguchi
 */
public enum InstanceState {
    PENDING, RUNNING, SHUTTING_DOWN, TERMINATED;

    public static InstanceState parse(ReservationDescription.Instance i) {
        if(i.isPending())       return PENDING;
        if(i.isRunning())       return RUNNING;
        if(i.isShuttingDown())  return SHUTTING_DOWN;
        return TERMINATED;
    }
}
