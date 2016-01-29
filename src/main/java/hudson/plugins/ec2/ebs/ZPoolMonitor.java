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
package hudson.plugins.ec2.ebs;

import hudson.model.PeriodicWork;
import hudson.model.Hudson;
import hudson.model.AdministrativeMonitor;
import hudson.util.TimeUnit2;
import hudson.Extension;
import org.jvnet.solaris.libzfs.LibZFS;
import org.jvnet.solaris.libzfs.ZFSFileSystem;
import org.jvnet.solaris.libzfs.ZFSPool;

import java.net.URL;
import java.io.IOException;

/**
 * Once an hour, check if the main zpool is that hosts $HUDSON_HOME has still enough free space.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class ZPoolMonitor extends PeriodicWork {
    private static Boolean isInsideEC2;

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit2.HOURS.toMillis(1);
    }

    @Override
    protected void doRun() {
        ZFSFileSystem fs = null;
        try {
            if (isInsideEC2())
                fs = new LibZFS().getFileSystemByMountPoint(Hudson.getInstance().getRootDir());
        } catch (LinkageError e) {
            // probably not running on OpenSolaris
        }
        if (fs == null) {
            cancel();
            return;
        }
        ZFSPool pool = fs.getPool();
        long a = pool.getAvailableSize();
        long t = pool.getSize();

        // if the disk is 90% filled up and the available space is less than
        // 1GB,
        // notify the user
        ZPoolExpandNotice zen = AdministrativeMonitor.all().get(ZPoolExpandNotice.class);
        zen.activated = t / a > 10 && a < 1000L * 1000 * 1000;
    }

    /**
     * Returns true if this JVM runs inside EC2.
     */
    public static synchronized boolean isInsideEC2() {
        if (isInsideEC2 == null) {
            try {
                new URL("http://169.254.169.254/latest").openStream().close();
                isInsideEC2 = true;
            } catch (IOException e) {
                isInsideEC2 = false;
            }
        }
        return isInsideEC2;
    }
}
